package com.example.mochi_mijia

import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.IBinder
import com.example.mochi_extension.ExtensionApiValidator
import com.example.mochi_extension.ExtensionConnectionState
import com.example.mochi_extension.ExtensionConnectionStatus
import com.example.mochi_extension.ExtensionExecutionContext
import com.example.mochi_extension.ExtensionMetadata
import com.example.mochi_extension.ExtensionToolDefinition
import com.example.mochi_extension.ExtensionToolRequest
import com.example.mochi_extension.ExtensionToolResult
import com.example.mochi_extension.IMochiAttachmentCallback
import com.example.mochi_extension.IMochiExtensionService
import com.example.mochi_extension.IMochiOperationCallback
import com.example.mochi_extension.IMochiToolCallback
import com.example.mochi_extension.MochiExtensionProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class MijiaExtensionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val json = Json { ignoreUnknownKeys = false }
    private val graph by lazy { MijiaGraph.get(this) }

    private val binder = object : IMochiExtensionService.Stub() {
        override fun getMetadata(): ExtensionMetadata {
            val packageInfo = currentPackageInfo()
            return ExtensionMetadata(
                protocolVersion = MochiExtensionProtocol.VERSION,
                minimumHostVersionCode = 10_001,
                extensionId = MochiExtensionProtocol.MIJIA_EXTENSION_ID,
                displayName = getString(R.string.app_name),
                packageName = packageName,
                serviceClassName = MochiExtensionProtocol.MIJIA_SERVICE,
                configurationActivityClassName =
                MochiExtensionProtocol.MIJIA_CONFIGURATION_ACTIVITY,
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = packageInfo.compatibleLongVersionCode(),
                capabilities = listOf("typed_tools", "attachments"),
            )
        }

        override fun getConnectionState(): ExtensionConnectionState =
            runBlocking(Dispatchers.IO) {
                try {
                    val session = graph.sessionStore.load()
                    if (session == null) {
                        ExtensionConnectionState(
                            status = ExtensionConnectionStatus.DISCONNECTED,
                            detail = null,
                            accountLabel = null,
                            selectedHomeCount = 0,
                            selectedDeviceCount = 0,
                        )
                    } else if (
                        graph.sessionStore.isAuthorizationExpired()
                    ) {
                        ExtensionConnectionState(
                            status = ExtensionConnectionStatus
                                .AUTHORIZATION_EXPIRED,
                            detail = "Reconnect Mi Home.",
                            accountLabel =
                            "Xiaomi ${session.userId.takeLast(4)}",
                            selectedHomeCount = 0,
                            selectedDeviceCount = 0,
                        )
                    } else {
                        ExtensionConnectionState(
                            status = ExtensionConnectionStatus.CONNECTED,
                            detail = if (session.region == null) {
                                "Select devices to complete setup."
                            } else {
                                null
                            },
                            accountLabel = "Xiaomi ${session.userId.takeLast(4)}",
                            selectedHomeCount = session.selectedHomeIds.size,
                            selectedDeviceCount = session.selectedDeviceIds.size,
                        )
                    }
                } catch (error: MijiaProviderException) {
                    ExtensionConnectionState(
                        status = ExtensionConnectionStatus.ERROR,
                        detail = error.message,
                        accountLabel = null,
                        selectedHomeCount = 0,
                        selectedDeviceCount = 0,
                    )
                }
            }

        override fun listTools(): List<ExtensionToolDefinition> =
            if (
                runBlocking(Dispatchers.IO) {
                    graph.sessionStore.load() != null &&
                        !graph.sessionStore.isAuthorizationExpired()
                }
            ) {
                MijiaToolExecutor.DEFINITIONS
            } else {
                emptyList()
            }

        override fun callTool(
            request: ExtensionToolRequest,
            callback: IMochiToolCallback,
        ) {
            val delivered = AtomicBoolean()
            ExtensionApiValidator.requestError(request)?.let { error ->
                deliverError(callback, delivered, request.requestId, "INVALID_ARGS", error)
                return
            }
            if (request.executionContext != ExtensionExecutionContext.FOREGROUND_MAIN) {
                deliverError(
                    callback,
                    delivered,
                    request.requestId,
                    "PERMISSION_DENIED",
                    "Mi Home Tools are foreground Main-Agent only.",
                )
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    withTimeout(request.timeoutMillis) {
                        val arguments = json.parseToJsonElement(
                            request.argumentsJson,
                        ).jsonObject
                        val content = graph.toolExecutor.execute(
                            request.toolName,
                            arguments,
                        )
                        deliver(
                            callback,
                            delivered,
                            ExtensionToolResult(
                                requestId = request.requestId,
                                success = true,
                                contentJson = content.content.toString(),
                                errorCode = null,
                                errorMessage = null,
                                attachments = content.attachments,
                            ),
                        )
                    }
                } catch (error: TimeoutCancellationException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "TIMEOUT",
                        mijiaToolTimeoutMessage(request.toolName),
                    )
                } catch (error: CancellationException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "CANCELLED",
                        "The Mi Home Tool call was cancelled.",
                    )
                } catch (error: MijiaAuthorizationExpiredException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "AUTHORIZATION_EXPIRED",
                        error.message ?: "Reconnect Mi Home.",
                    )
                } catch (error: MijiaAuthorizationException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "NOT_CONNECTED",
                        error.message ?: "Connect Mi Home first.",
                    )
                } catch (error: MijiaNotFoundException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "NOT_FOUND",
                        error.message ?: "Mi Home item not found.",
                    )
                } catch (error: IllegalArgumentException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "INVALID_ARGS",
                        error.message ?: "Invalid Mi Home Tool arguments.",
                    )
                } catch (error: SerializationException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "INVALID_ARGS",
                        "Mi Home Tool arguments must be a JSON object.",
                    )
                } catch (error: MijiaProviderException) {
                    deliverError(
                        callback,
                        delivered,
                        request.requestId,
                        "PROVIDER_ERROR",
                        error.message ?: "Mi Home request failed.",
                    )
                } finally {
                    jobs.remove(
                        request.requestId,
                        currentCoroutineContext()[Job],
                    )
                }
            }

            if (jobs.putIfAbsent(request.requestId, job) != null) {
                job.cancel()
                deliverError(
                    callback,
                    delivered,
                    request.requestId,
                    "CONFLICT",
                    "A Tool call with this request ID is already active.",
                )
                return
            }
            job.start()
        }

        override fun cancelTool(requestId: String) {
            jobs.remove(requestId)?.cancel()
        }

        override fun openAttachment(
            attachmentId: String,
            callback: IMochiAttachmentCallback,
        ) {
            try {
                val opened = graph.cameraEventClient.open(attachmentId)
                opened.fileDescriptor.use { descriptor ->
                    callback.onSuccess(opened.descriptor, descriptor)
                }
            } catch (error: MijiaNotFoundException) {
                callback.onError(
                    attachmentId,
                    "NOT_FOUND",
                    error.message ?: "The attachment is unavailable.",
                )
            } catch (error: Exception) {
                callback.onError(
                    attachmentId,
                    "PROVIDER_ERROR",
                    error.message ?: "The attachment could not be opened.",
                )
            }
        }

        override fun disconnect(callback: IMochiOperationCallback) {
            scope.launch {
                jobs.values.forEach(Job::cancel)
                jobs.clear()
                graph.cameraEventClient.clear()
                runCatching { graph.sessionStore.clear() }
                    .onSuccess { runCatching(callback::onSuccess) }
                    .onFailure { error ->
                        runCatching {
                            callback.onError(
                                "INTERNAL_ERROR",
                                error.message ?: "Could not disconnect Mi Home.",
                            )
                        }
                    }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun currentPackageInfo(): PackageInfo =
        packageManager.getPackageInfo(packageName, 0)

    @Suppress("DEPRECATION")
    private fun PackageInfo.compatibleLongVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }

    private fun deliverError(
        callback: IMochiToolCallback,
        delivered: AtomicBoolean,
        requestId: String,
        errorCode: String,
        errorMessage: String,
    ) {
        deliver(
            callback,
            delivered,
            ExtensionToolResult(
                requestId = requestId,
                success = false,
                contentJson = null,
                errorCode = errorCode,
                errorMessage = errorMessage,
                attachments = emptyList(),
            ),
        )
    }

    private fun deliver(
        callback: IMochiToolCallback,
        delivered: AtomicBoolean,
        result: ExtensionToolResult,
    ) {
        if (delivered.compareAndSet(false, true)) {
            runCatching { callback.onResult(result) }
        }
    }
}

internal fun mijiaToolTimeoutMessage(toolName: String): String =
    if (toolName == "mijia_get_latest_camera_event_image") {
        "The latest camera event image timed out. Mi Home is still connected."
    } else {
        "The Mi Home Tool call timed out."
    }
