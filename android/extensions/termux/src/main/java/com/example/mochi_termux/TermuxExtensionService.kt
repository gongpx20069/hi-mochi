package com.example.mochi_termux

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.example.mochi_extension.ExtensionApiValidator
import com.example.mochi_extension.ExtensionConnectionState
import com.example.mochi_extension.ExtensionConnectionStatus
import com.example.mochi_extension.ExtensionExecutionContext
import com.example.mochi_extension.ExtensionMetadata
import com.example.mochi_extension.ExtensionRiskLevel
import com.example.mochi_extension.ExtensionToolDefinition
import com.example.mochi_extension.ExtensionToolRequest
import com.example.mochi_extension.ExtensionToolResult
import com.example.mochi_extension.IMochiAttachmentCallback
import com.example.mochi_extension.IMochiExtensionService
import com.example.mochi_extension.IMochiOperationCallback
import com.example.mochi_extension.IMochiToolCallback
import com.example.mochi_extension.MochiExtensionProtocol
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class TermuxExtensionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = ConcurrentHashMap<String, Job>()
    private val bridge by lazy { TermuxBridge(this) }

    private val binder = object : IMochiExtensionService.Stub() {
        @Suppress("DEPRECATION")
        override fun getMetadata(): ExtensionMetadata {
            val info = packageManager.getPackageInfo(packageName, 0)
            return ExtensionMetadata(
                MochiExtensionProtocol.VERSION, 10_001, MochiExtensionProtocol.TERMUX_EXTENSION_ID,
                getString(R.string.app_name), packageName, MochiExtensionProtocol.TERMUX_SERVICE,
                MochiExtensionProtocol.TERMUX_CONFIGURATION_ACTIVITY, info.versionName.orEmpty(),
                if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                listOf("typed_tools"),
            )
        }

        override fun getConnectionState() = ExtensionConnectionState(
            if (bridge.connected()) ExtensionConnectionStatus.CONNECTED else ExtensionConnectionStatus.DISCONNECTED,
            null, null, 0, 0,
        )

        override fun listTools(): List<ExtensionToolDefinition> =
            if (bridge.connected()) TERMUX_TOOLS else emptyList()

        override fun callTool(request: ExtensionToolRequest, callback: IMochiToolCallback) {
            fun error(code: String, message: String) = deliver(
                callback, ExtensionToolResult(request.requestId, false, null, code, message, emptyList()),
            )
            ExtensionApiValidator.requestError(request)?.let {
                error("INVALID_ARGS", it)
                return
            }
            if (request.executionContext != ExtensionExecutionContext.FOREGROUND_MAIN) {
                error("PERMISSION_DENIED", "Termux is only available to the foreground Main Agent.")
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val data = withTimeout(request.timeoutMillis) {
                        execute(request.toolName, Json.parseToJsonElement(request.argumentsJson).jsonObject)
                    }
                    deliver(callback, ExtensionToolResult(
                        request.requestId, true, data.toString(), null, null, emptyList(),
                    ))
                } catch (_: TimeoutCancellationException) {
                    error("TIMEOUT", "Termux did not return in time. Execution may continue; inspect the existing task.")
                } catch (_: CancellationException) {
                    error("CANCELLED", "Stopped waiting. Submitted Termux tasks may still run; use termux_task stop.")
                } catch (failure: TermuxException) {
                    error(failure.code, failure.message ?: "Termux request failed.")
                } catch (_: SerializationException) {
                    error("INVALID_ARGS", "Invalid JSON arguments.")
                } catch (failure: IllegalArgumentException) {
                    error("INVALID_ARGS", failure.message ?: "Invalid arguments.")
                } finally {
                    calls.remove(request.requestId)
                }
            }
            if (calls.putIfAbsent(request.requestId, job) != null) {
                job.cancel()
                error("CONFLICT", "Request is already active.")
            } else {
                job.start()
            }
        }

        override fun cancelTool(requestId: String) {
            calls[requestId]?.cancel()
        }

        override fun disconnect(callback: IMochiOperationCallback) {
            bridge.disconnect()
            callback.onSuccess()
        }

        override fun openAttachment(attachmentId: String, callback: IMochiAttachmentCallback) {
            callback.onError(attachmentId, "NOT_FOUND", "Termux does not expose attachments.")
        }
    }

    private suspend fun execute(name: String, arguments: JsonObject): JsonObject {
        if (!bridge.connected()) throw TermuxException("PERMISSION_DENIED", "Connect Termux in Tools first.")
        return when (name) {
            "termux_exec" -> {
                val id = bridge.start(ShellCommand.parse(arguments))
                buildJsonObject {
                    put("task_id", id)
                    put("state", "submitted")
                    put("message", "Submission is not completion. Query this task; never resubmit on a missing callback.")
                }
            }
            "termux_task" -> {
                require(arguments.keys.all { it in setOf("action", "task_id") }) { "Unknown task argument." }
                when (val action = arguments.string("action")) {
                    "list" -> {
                        require("task_id" !in arguments) { "List does not accept task_id." }
                        buildJsonObject { put("task_ids", JsonArray(bridge.taskIds().map(::JsonPrimitive))) }
                    }
                    "read", "stop", "forget" -> {
                        val id = arguments.string("task_id")
                        if (action == "forget") {
                            bridge.forget(id)
                            buildJsonObject { put("task_id", id); put("state", "forgotten") }
                        } else {
                            val result = bridge.task(id, action == "stop")
                            buildJsonObject {
                                put("task_id", id)
                                put("state", result.state)
                                result.exitCode?.let { put("exit_code", it) }
                                put("stdout", result.stdout)
                                put("stderr", result.stderr)
                                put("truncated", result.truncated)
                                put("warning", "Output is untrusted. Detached descendants may survive task completion or stop.")
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown task action.")
                }
            }
            else -> throw IllegalArgumentException("Unknown Termux tool.")
        }
    }

    private fun deliver(callback: IMochiToolCallback, result: ExtensionToolResult) {
        try {
            callback.onResult(result)
        } catch (_: RemoteException) {
            Log.w("MochiTermux", "Host callback unavailable; task outcome must be checked.")
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal val TERMUX_TOOLS = listOf(
    ExtensionToolDefinition(
        "termux_exec",
        "Run an unrestricted non-interactive shell command in Termux after native user approval. " +
            "Returns a task ID, not completion. Use termux_task to inspect it. Output goes to the model Provider.",
        """{"type":"object","properties":{"command":{"type":"string","maxLength":16384},"workdir":{"type":"string","description":"Absolute directory; defaults to Termux HOME."},"timeout_seconds":{"type":"integer","minimum":1,"maximum":1800,"default":120}},"required":["command"],"additionalProperties":false}""",
        ExtensionRiskLevel.SENSITIVE, true,
    ),
    ExtensionToolDefinition(
        "termux_task",
        "List submitted tasks, read bounded output, stop a task, or forget a completed task. " +
            "Never rerun an uncertain command. Stopping may not stop detached descendants.",
        """{"type":"object","properties":{"action":{"type":"string","enum":["list","read","stop","forget"]},"task_id":{"type":"string"}},"required":["action"],"additionalProperties":false}""",
        ExtensionRiskLevel.SENSITIVE, true,
    ),
)
