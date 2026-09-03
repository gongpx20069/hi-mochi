package com.example.mochi_pet.core.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import com.example.mochi_extension.ExtensionApiValidator
import com.example.mochi_extension.ExtensionAttachmentDescriptor
import com.example.mochi_extension.ExtensionConnectionState
import com.example.mochi_extension.ExtensionConnectionStatus
import com.example.mochi_extension.ExtensionExecutionContext
import com.example.mochi_extension.ExtensionMetadata
import com.example.mochi_extension.ExtensionToolDefinition
import com.example.mochi_extension.ExtensionToolRequest
import com.example.mochi_extension.ExtensionToolResult
import com.example.mochi_extension.IMochiExtensionService
import com.example.mochi_extension.IMochiAttachmentCallback
import com.example.mochi_extension.IMochiOperationCallback
import com.example.mochi_extension.IMochiToolCallback
import com.example.mochi_extension.MochiExtensionProtocol
import com.example.mochi_pet.BuildConfig
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.AgentToolJson
import com.example.mochi_pet.core.agent.tool.ModelImageAttachment
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ExtensionActivityTarget(
    val packageName: String,
    val className: String,
)

data class OpenedExtensionAttachment(
    val descriptor: ExtensionAttachmentDescriptor,
    val fileDescriptor: ParcelFileDescriptor,
) : AutoCloseable {
    override fun close() {
        fileDescriptor.close()
    }
}

data class ExtensionImageAttachment(
    val descriptor: ExtensionAttachmentDescriptor,
    val bytes: ByteArray,
    val readyForModel: Boolean,
)

data class MijiaExtensionSnapshot(
    val installed: Boolean = false,
    val trusted: Boolean = false,
    val detail: String? = null,
    val metadata: ExtensionMetadata? = null,
    val connectionState: ExtensionConnectionState = ExtensionConnectionState(
        status = ExtensionConnectionStatus.DISCONNECTED,
        detail = null,
        accountLabel = null,
        selectedHomeCount = 0,
        selectedDeviceCount = 0,
    ),
    val tools: List<ExtensionToolDefinition> = emptyList(),
) {
    val connected: Boolean
        get() = trusted &&
            connectionState.status == ExtensionConnectionStatus.CONNECTED

    val configurationTarget: ExtensionActivityTarget?
        get() = metadata?.takeIf { trusted }?.let {
            ExtensionActivityTarget(
                packageName = it.packageName,
                className = it.configurationActivityClassName,
            )
        }
}

interface MochiExtensionClient {
    val attachmentEvents: Flow<ExtensionImageAttachment>

    suspend fun snapshot(): MijiaExtensionSnapshot

    fun agentTool(definition: ExtensionToolDefinition): AgentTool

    suspend fun disconnect()

    suspend fun openAttachment(
        descriptor: ExtensionAttachmentDescriptor,
    ): OpenedExtensionAttachment
}

object UnavailableMijiaExtensionClient : MochiExtensionClient {
    override val attachmentEvents: Flow<ExtensionImageAttachment> =
        emptyFlow()

    override suspend fun snapshot() = MijiaExtensionSnapshot()

    override fun agentTool(definition: ExtensionToolDefinition): AgentTool =
        error("Mi Home extension is unavailable")

    override suspend fun disconnect() = Unit

    override suspend fun openAttachment(
        descriptor: ExtensionAttachmentDescriptor,
    ): OpenedExtensionAttachment =
        error("Mi Home extension is unavailable")
}

class AndroidMijiaExtensionClient(
    context: Context,
) : MochiExtensionClient {
    private val context = context.applicationContext
    private val packageManager = context.packageManager
    private val component = ComponentName(
        MochiExtensionProtocol.MIJIA_PACKAGE,
        MochiExtensionProtocol.MIJIA_SERVICE,
    )
    private val mutableAttachmentEvents =
        MutableSharedFlow<ExtensionImageAttachment>(
            extraBufferCapacity = 1,
        )

    override val attachmentEvents: Flow<ExtensionImageAttachment> =
        mutableAttachmentEvents

    override suspend fun snapshot(): MijiaExtensionSnapshot =
        withContext(Dispatchers.IO) {
            val extensionPackage = extensionPackageInfo()
                ?: return@withContext MijiaExtensionSnapshot()
            trustError(extensionPackage)?.let { error ->
                return@withContext MijiaExtensionSnapshot(
                    installed = true,
                    detail = error,
                )
            }
            try {
                withService { service ->
                    val metadata = service.metadata
                    validateMetadata(metadata, extensionPackage)?.let { error ->
                        return@withService MijiaExtensionSnapshot(
                            installed = true,
                            detail = error,
                        )
                    }
                    val connection = service.connectionState
                    ExtensionApiValidator.connectionStateError(connection)
                        ?.let { error ->
                            return@withService MijiaExtensionSnapshot(
                                installed = true,
                                detail = error,
                            )
                        }
                    val tools = if (
                        connection.status == ExtensionConnectionStatus.CONNECTED
                    ) {
                        service.listTools()
                    } else {
                        emptyList()
                    }
                    ExtensionApiValidator.toolDefinitionsError(tools)
                        ?.let { error ->
                            return@withService MijiaExtensionSnapshot(
                                installed = true,
                                detail = error,
                            )
                        }
                    MijiaExtensionSnapshot(
                        installed = true,
                        trusted = true,
                        metadata = metadata,
                        connectionState = connection,
                        tools = tools,
                    )
                }
            } catch (error: ExtensionBindingException) {
                MijiaExtensionSnapshot(
                    installed = true,
                    detail = error.message,
                )
            } catch (error: SecurityException) {
                MijiaExtensionSnapshot(
                    installed = true,
                    detail = "Android rejected the extension connection.",
                )
            }
        }

    override fun agentTool(definition: ExtensionToolDefinition): AgentTool =
        ExtensionAgentTool(this, definition)

    override suspend fun disconnect() {
        withService { service ->
            withTimeout(EXTENSION_OPERATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    service.disconnect(
                        object : IMochiOperationCallback.Stub() {
                            override fun onSuccess() {
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(
                                errorCode: String,
                                errorMessage: String,
                            ) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        ExtensionBindingException(errorMessage),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    override suspend fun openAttachment(
        descriptor: ExtensionAttachmentDescriptor,
    ): OpenedExtensionAttachment {
        ExtensionApiValidator.attachmentError(descriptor)?.let { error ->
            throw ExtensionBindingException(error)
        }
        return withService { service ->
            withTimeout(EXTENSION_OPERATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    service.openAttachment(
                        descriptor.attachmentId,
                        object : IMochiAttachmentCallback.Stub() {
                            override fun onSuccess(
                                returnedDescriptor:
                                ExtensionAttachmentDescriptor,
                                fileDescriptor: ParcelFileDescriptor,
                            ) {
                                if (!continuation.isActive) {
                                    fileDescriptor.close()
                                    return
                                }
                                val validationError =
                                    ExtensionApiValidator.attachmentError(
                                        returnedDescriptor,
                                    )
                                if (
                                    validationError != null ||
                                    returnedDescriptor != descriptor
                                ) {
                                    fileDescriptor.close()
                                    continuation.resumeWithException(
                                        ExtensionBindingException(
                                            validationError
                                                ?: "Attachment metadata changed.",
                                        ),
                                    )
                                    return
                                }
                                continuation.resume(
                                    OpenedExtensionAttachment(
                                        returnedDescriptor,
                                        fileDescriptor,
                                    ),
                                )
                            }

                            override fun onError(
                                attachmentId: String,
                                errorCode: String,
                                errorMessage: String,
                            ) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        ExtensionBindingException(errorMessage),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    internal suspend fun execute(
        definition: ExtensionToolDefinition,
        arguments: JsonObject,
        modelImageInputAllowed: Boolean,
    ): ToolResultEnvelope {
        val request = ExtensionToolRequest(
            requestId = "request-${UUID.randomUUID()}",
            toolName = definition.name,
            argumentsJson = arguments.toString(),
            timeoutMillis = EXTENSION_TOOL_TIMEOUT_MILLIS,
            executionContext = ExtensionExecutionContext.FOREGROUND_MAIN,
        )
        ExtensionApiValidator.requestError(request)?.let {
            return ToolResultEnvelope.error(ToolErrorCode.INVALID_ARGS, it)
        }
        return try {
            val result = withService { service ->
                withTimeout(request.timeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation {
                            runCatching { service.cancelTool(request.requestId) }
                        }
                        service.callTool(
                            request,
                            object : IMochiToolCallback.Stub() {
                                override fun onResult(
                                    result: ExtensionToolResult,
                                ) {
                                    if (!continuation.isActive) return
                                    ExtensionApiValidator.resultError(
                                        result = result,
                                        expectedRequestId = request.requestId,
                                    )?.let { error ->
                                        continuation.resume(
                                            ExtensionToolResult(
                                                requestId = request.requestId,
                                                success = false,
                                                contentJson = null,
                                                errorCode = "PROVIDER_ERROR",
                                                errorMessage = error,
                                                attachments = emptyList(),
                                            ),
                                        )
                                        return
                                    }
                                    continuation.resume(result)
                                }
                            },
                        )
                    }
                }
            }
            val images = if (result.success) {
                result.attachments.map { descriptor ->
                    consumeAttachment(descriptor)
                }
            } else {
                emptyList()
            }
            images.forEach { image ->
                mutableAttachmentEvents.tryEmit(
                    ExtensionImageAttachment(
                        descriptor = image.descriptor,
                        bytes = image.modelImage.bytes,
                        readyForModel = modelImageInputAllowed,
                    ),
                )
            }
            result.toEnvelope(
                modelImages = if (modelImageInputAllowed) {
                    images.map(ConsumedExtensionImage::modelImage)
                } else {
                    emptyList()
                },
                imageReadyForModel =
                    modelImageInputAllowed && images.isNotEmpty(),
            )
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResultEnvelope.error(
                ToolErrorCode.TIMEOUT,
                "The Mi Home extension timed out.",
            )
        } catch (error: ExtensionBindingException) {
            ToolResultEnvelope.error(
                ToolErrorCode.PROVIDER_ERROR,
                error.message ?: "The Mi Home extension is unavailable.",
            )
        } catch (error: SecurityException) {
            ToolResultEnvelope.error(
                ToolErrorCode.PERMISSION_DENIED,
                "Android rejected the Mi Home extension connection.",
            )
        }
    }

    private suspend fun consumeAttachment(
        descriptor: ExtensionAttachmentDescriptor,
    ): ConsumedExtensionImage =
        withContext(Dispatchers.IO) {
            val opened = openAttachment(descriptor)
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                opened.fileDescriptor,
            ).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (
                        output.size().toLong() >
                        com.example.mochi_extension.ExtensionApiLimits
                            .MAX_ATTACHMENT_BYTES
                    ) {
                        throw ExtensionBindingException(
                            "Extension image exceeded the attachment limit.",
                        )
                    }
                }
                output.toByteArray()
            }
            if (bytes.size.toLong() != descriptor.byteCount) {
                throw ExtensionBindingException(
                    "Extension image length did not match its descriptor.",
                )
            }
            normalizeImage(descriptor, bytes)
        }

    private fun normalizeImage(
        descriptor: ExtensionAttachmentDescriptor,
        bytes: ByteArray,
    ): ConsumedExtensionImage {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (
            bounds.outWidth != descriptor.widthPixels ||
            bounds.outHeight != descriptor.heightPixels
        ) {
            throw ExtensionBindingException(
                "Extension image dimensions did not match its descriptor.",
            )
        }
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MODEL_IMAGE_MAX_DIMENSION ||
            bounds.outHeight / sampleSize > MODEL_IMAGE_MAX_DIMENSION ||
            bounds.outWidth.toLong() / sampleSize *
            (bounds.outHeight.toLong() / sampleSize) >
            MODEL_IMAGE_MAX_PIXELS
        ) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            },
        ) ?: throw ExtensionBindingException(
            "Extension image could not be decoded.",
        )
        return try {
            val normalized = listOf(85, 70, 55).firstNotNullOfOrNull {
                quality ->
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    output.toByteArray().takeIf {
                        it.size <= MODEL_IMAGE_MAX_BYTES
                    }
                }
            } ?: throw ExtensionBindingException(
                "Extension image could not fit the model input limit.",
            )
            ConsumedExtensionImage(
                descriptor = descriptor.copy(
                    mimeType = "image/jpeg",
                    byteCount = normalized.size.toLong(),
                    widthPixels = bitmap.width,
                    heightPixels = bitmap.height,
                ),
                modelImage = ModelImageAttachment(
                    mimeType = "image/jpeg",
                    bytes = normalized,
                ),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun ExtensionToolResult.toEnvelope(
        modelImages: List<ModelImageAttachment>,
        imageReadyForModel: Boolean,
    ): ToolResultEnvelope {
        if (!success) {
            return ToolResultEnvelope.error(
                errorCode.toToolErrorCode(),
                errorMessage ?: "The Mi Home extension failed.",
            )
        }
        val data = try {
            AgentToolJson.format.parseToJsonElement(
                checkNotNull(contentJson),
            )
        } catch (_: SerializationException) {
            return ToolResultEnvelope.error(
                ToolErrorCode.PROVIDER_ERROR,
                "The Mi Home extension returned invalid JSON.",
            )
        }
        val enrichedData = if (attachments.isNotEmpty() && data is JsonObject) {
            buildJsonObject {
                data.forEach { (name, value) -> put(name, value) }
                put("image_ready_for_model", imageReadyForModel)
            }
        } else {
            data
        }
        return ToolResultEnvelope.success(
            data = enrichedData,
            modelImages = modelImages,
        )
    }

    private fun String?.toToolErrorCode(): ToolErrorCode =
        when (this) {
            "INVALID_ARGS", "VALIDATION" -> ToolErrorCode.INVALID_ARGS
            "NOT_FOUND" -> ToolErrorCode.NOT_FOUND
            "CONFLICT" -> ToolErrorCode.CONFLICT
            "PERMISSION", "PERMISSION_DENIED" ->
                ToolErrorCode.PERMISSION_DENIED
            "CANCELLED" -> ToolErrorCode.CANCELLED
            "TIMEOUT" -> ToolErrorCode.TIMEOUT
            "PROVIDER", "PROVIDER_ERROR", "NOT_CONNECTED",
            "AUTHORIZATION_EXPIRED",
            ->
                ToolErrorCode.PROVIDER_ERROR
            else -> ToolErrorCode.INTERNAL_ERROR
        }

    private fun extensionPackageInfo(): PackageInfo? =
        try {
            packageInfo(MochiExtensionProtocol.MIJIA_PACKAGE)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun trustError(extensionPackage: PackageInfo): String? {
        val hostDigests = certificateDigests(packageInfo(context.packageName))
        val extensionDigests = certificateDigests(extensionPackage)
        if (
            hostDigests.isEmpty() ||
            extensionDigests.isEmpty() ||
            hostDigests.intersect(extensionDigests).isEmpty()
        ) {
            return "Extension signature does not match Mochi."
        }
        val service = try {
            @Suppress("DEPRECATION")
            packageManager.getServiceInfo(
                component,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return "Expected extension service is missing."
        }
        if (!service.exported || service.permission != MochiExtensionProtocol.BIND_PERMISSION) {
            return "Extension service permission is invalid."
        }
        val activity = try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(
                ComponentName(
                    MochiExtensionProtocol.MIJIA_PACKAGE,
                    MochiExtensionProtocol.MIJIA_CONFIGURATION_ACTIVITY,
                ),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return "Expected extension configuration activity is missing."
        }
        if (
            !activity.exported ||
            activity.permission != MochiExtensionProtocol.BIND_PERMISSION
        ) {
            return "Extension configuration permission is invalid."
        }
        return null
    }

    private fun validateMetadata(
        metadata: ExtensionMetadata,
        packageInfo: PackageInfo,
    ): String? {
        ExtensionApiValidator.metadataError(metadata)?.let { return it }
        if (metadata.protocolVersion != MochiExtensionProtocol.VERSION) {
            return "Extension protocol version is unsupported."
        }
        if (metadata.minimumHostVersionCode > BuildConfig.VERSION_CODE) {
            return "Update Mochi before using this extension."
        }
        if (
            metadata.extensionId != MochiExtensionProtocol.MIJIA_EXTENSION_ID ||
            metadata.packageName != MochiExtensionProtocol.MIJIA_PACKAGE ||
            metadata.serviceClassName != MochiExtensionProtocol.MIJIA_SERVICE ||
            metadata.configurationActivityClassName !=
            MochiExtensionProtocol.MIJIA_CONFIGURATION_ACTIVITY
        ) {
            return "Extension identity is invalid."
        }
        if (
            metadata.versionCode != PackageInfoCompat.getLongVersionCode(
                packageInfo,
            ) ||
            metadata.versionName != packageInfo.versionName.orEmpty()
        ) {
            return "Extension package version metadata is invalid."
        }
        return null
    }

    private suspend fun <T> withService(
        block: suspend (IMochiExtensionService) -> T,
    ): T {
        val bound = bind()
        return try {
            block(bound.service)
        } finally {
            bound.close()
        }
    }

    private suspend fun bind(): BoundExtension =
        suspendCancellableCoroutine { continuation ->
            lateinit var connection: ServiceConnection
            connection = object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    val service = IMochiExtensionService.Stub.asInterface(binder)
                    if (continuation.isActive) {
                        continuation.resume(
                            BoundExtension(context, connection, service),
                        )
                    } else {
                        runCatching { context.unbindService(connection) }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onNullBinding(name: ComponentName) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ExtensionBindingException(
                                "The extension returned no Binder service.",
                            ),
                        )
                    }
                    runCatching { context.unbindService(connection) }
                }

                override fun onBindingDied(name: ComponentName) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ExtensionBindingException(
                                "The extension process stopped.",
                            ),
                        )
                    }
                    runCatching { context.unbindService(connection) }
                }
            }
            val bound = try {
                context.bindService(
                    Intent(MOCHI_EXTENSION_ACTION).setComponent(component),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (error: SecurityException) {
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            if (!bound) {
                continuation.resumeWithException(
                    ExtensionBindingException(
                        "The Mi Home extension could not be bound.",
                    ),
                )
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                runCatching { context.unbindService(connection) }
            }
        }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES,
            )
        }

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            info.signatures
        }.orEmpty()
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private companion object {
        const val MOCHI_EXTENSION_ACTION =
            "com.example.mochi_extension.BIND_EXTENSION"
        const val EXTENSION_TOOL_TIMEOUT_MILLIS = 60_000L
        const val EXTENSION_OPERATION_TIMEOUT_MILLIS = 15_000L
        const val MODEL_IMAGE_MAX_DIMENSION = 2_048
        const val MODEL_IMAGE_MAX_PIXELS = 4_194_304L
        const val MODEL_IMAGE_MAX_BYTES = 2 * 1_024 * 1_024
    }
}

private data class ConsumedExtensionImage(
    val descriptor: ExtensionAttachmentDescriptor,
    val modelImage: ModelImageAttachment,
)

private class ExtensionAgentTool(
    private val client: AndroidMijiaExtensionClient,
    private val definition: ExtensionToolDefinition,
) : AgentTool {
    override val name: String = definition.name
    override val schema: JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", definition.name)
                put("description", definition.description)
                put(
                    "parameters",
                    AgentToolJson.format.parseToJsonElement(
                        definition.inputSchemaJson,
                    ),
                )
            },
        )
    }

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope = client.execute(
        definition,
        arguments,
        context.modelImageInputAllowed,
    )
}

private class BoundExtension(
    private val context: Context,
    private val connection: ServiceConnection,
    val service: IMochiExtensionService,
) {
    private val closed = AtomicBoolean()

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { context.unbindService(connection) }
        }
    }
}

private class ExtensionBindingException(message: String) :
    IllegalStateException(message)
