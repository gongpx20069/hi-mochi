package com.example.mochi_webdav

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
import java.io.IOException
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class WebdavExtensionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = ConcurrentHashMap<String, Job>()
    private val repository get() = (application as WebdavApplication).repository
    private val binder = object : IMochiExtensionService.Stub() {
        @Suppress("DEPRECATION")
        override fun getMetadata(): ExtensionMetadata {
            val info = packageManager.getPackageInfo(packageName, 0)
            return ExtensionMetadata(
                MochiExtensionProtocol.VERSION, 10_001, MochiExtensionProtocol.WEBDAV_EXTENSION_ID,
                getString(R.string.app_name), packageName, MochiExtensionProtocol.WEBDAV_SERVICE,
                MochiExtensionProtocol.WEBDAV_CONFIGURATION_ACTIVITY, info.versionName.orEmpty(),
                if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                listOf("typed_tools"),
            )
        }

        override fun getConnectionState(): ExtensionConnectionState {
            val state = repository.state.value
            return ExtensionConnectionState(
                when {
                    repository.ready() -> ExtensionConnectionStatus.CONNECTED
                    state.loading -> ExtensionConnectionStatus.CONNECTING
                    state.error != null -> ExtensionConnectionStatus.ERROR
                    else -> ExtensionConnectionStatus.DISCONNECTED
                },
                if (state.error != null) "WebDAV configuration is unavailable. Open connection settings." else if (
                    state.config?.allowLan == true && !repository.lanPermissionGranted()
                ) "Allow local network access in WebDAV connection settings." else null,
                null, 0, 0,
            )
        }

        override fun listTools(): List<ExtensionToolDefinition> {
            if (!repository.ready()) return emptyList()
            val revision = repository.state.value.config?.revision ?: return emptyList()
            return WEBDAV_TOOLS.map { definition ->
                val schema = Json.parseToJsonElement(definition.inputSchemaJson).jsonObject
                definition.copy(inputSchemaJson = JsonObject(schema + ("x-webdav-connection" to JsonPrimitive(revision))).toString())
            }
        }

        override fun callTool(request: ExtensionToolRequest, callback: IMochiToolCallback) {
            fun fail(code: String, message: String) =
                deliver(callback, ExtensionToolResult(request.requestId, false, null, code, message, emptyList()))
            ExtensionApiValidator.requestError(request)?.let { fail("INVALID_ARGS", "Invalid WebDAV request."); return }
            if (request.executionContext != ExtensionExecutionContext.FOREGROUND_MAIN) {
                fail("PERMISSION_DENIED", "WebDAV files are available only to the foreground Main Agent.")
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val data = withTimeout(request.timeoutMillis) {
                        executeWebdav(repository, request.toolName, Json.parseToJsonElement(request.argumentsJson).jsonObject)
                    }
                    val result = ExtensionToolResult(request.requestId, true, data.toString(), null, null, emptyList())
                    if (ExtensionApiValidator.resultError(result) == null) deliver(callback, result)
                    else fail("INVALID_RESPONSE", "WebDAV result exceeded the safe result limits.")
                } catch (_: TimeoutCancellationException) {
                    fail("TIMEOUT", "WebDAV timed out. A write may have completed; inspect the file before retrying.")
                } catch (_: CancellationException) {
                    fail("CANCELLED", "WebDAV request cancelled. Inspect any interrupted write before retrying.")
                } catch (failure: WebdavException) {
                    Log.w("MochiWebdav", "Tool failed: ${failure.code}; HTTP ${failure.httpStatus ?: 0}")
                    val code = when (failure.code) {
                        WebdavError.INVALID_ARGUMENT -> "INVALID_ARGS"
                        WebdavError.AUTHORIZATION, WebdavError.PERMISSION_DENIED -> "PERMISSION_DENIED"
                        WebdavError.NOT_FOUND -> "NOT_FOUND"
                        WebdavError.CONFLICT -> "CONFLICT"
                        else -> "EXECUTION_FAILED"
                    }
                    fail(code, "WebDAV ${failure.code.name.lowercase()}. Check connection settings or file state. " +
                        "Do not automatically retry an uncertain write.")
                } catch (_: SerializationException) {
                    fail("INVALID_ARGS", "Invalid WebDAV arguments or stored metadata.")
                } catch (_: IllegalArgumentException) {
                    fail("INVALID_ARGS", "WebDAV arguments must be a JSON object with valid values.")
                } catch (_: IOException) {
                    fail("EXECUTION_FAILED", "WebDAV file or configuration storage is unavailable.")
                } finally {
                    calls.remove(request.requestId)
                }
            }
            if (calls.putIfAbsent(request.requestId, job) != null) {
                job.cancel()
                fail("CONFLICT", "WebDAV request is already active.")
            } else job.start()
        }

        override fun cancelTool(requestId: String) { calls[requestId]?.cancel() }

        override fun disconnect(callback: IMochiOperationCallback) {
            calls.values.forEach { it.cancel() }
            scope.launch {
                try {
                    repository.disconnect()
                    callback.onSuccess()
                } catch (_: IOException) {
                    callback.onError("STORAGE", "WebDAV connection could not be removed.")
                } catch (_: WebdavException) {
                    callback.onError("STORAGE", "WebDAV transfer cleanup failed.")
                } catch (_: RemoteException) {
                    Log.w("MochiWebdav", "Disconnect callback unavailable.")
                }
            }
        }

        override fun openAttachment(attachmentId: String, callback: IMochiAttachmentCallback) {
            callback.onError(attachmentId, "NOT_FOUND", "Export binary files through WebDAV connection settings.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            var revision = repository.state.value.config?.revision
            repository.state.collect {
                if (revision != it.config?.revision) {
                    revision = it.config?.revision
                    calls.values.forEach { call -> call.cancel() }
                }
            }
        }
    }

    private fun deliver(callback: IMochiToolCallback, result: ExtensionToolResult) {
        try { callback.onResult(result) } catch (_: RemoteException) {
            Log.w("MochiWebdav", "Host callback unavailable; inspect writes before retrying.")
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

internal suspend fun executeWebdav(
    repository: WebdavRepository,
    name: String,
    arguments: JsonObject,
    createClient: (WebdavConfig) -> WebdavClient = { WebdavClient(it) },
): JsonObject {
    val config = repository.config()
    requireWebdav(arguments.optionalText("_connection_id") == config.revision, WebdavError.PERMISSION_DENIED)
    val args = JsonObject(arguments - "_connection_id")
    val allowed = when (name) {
        "webdav_list" -> setOf("path", "query", "recursive", "offset")
        "webdav_read" -> setOf("path", "metadata_only")
        "webdav_write" -> setOf("path", "content", "expected_etag", "confirmed")
        "webdav_manage" -> when (args.text("action")) {
            "mkdir" -> setOf("action", "path", "confirmed")
            "delete" -> setOf("action", "path", "expected_etag", "recursive", "confirmed")
            "move", "copy" -> setOf("action", "path", "destination", "expected_etag", "recursive", "confirmed")
            else -> throw WebdavException(WebdavError.INVALID_ARGUMENT)
        }
        "webdav_transfer" -> when (args.text("action")) {
            "list" -> setOf("action")
            "download" -> setOf("action", "path")
            "upload" -> setOf("action", "path", "file_id", "expected_etag", "confirmed")
            "forget" -> setOf("action", "file_id", "confirmed")
            else -> throw WebdavException(WebdavError.INVALID_ARGUMENT)
        }
        else -> throw WebdavException(WebdavError.INVALID_ARGUMENT)
    }
    requireWebdav(args.keys.all { it in allowed })
    val client = createClient(config)
    val path = if (name == "webdav_list" || name == "webdav_transfer" && args.text("action") in setOf("list", "forget")) {
        args.text("path", "")
    } else args.text("path")
    val result = when (name) {
        "webdav_list" -> {
            val query = args.text("query", "")
            requireWebdav(query.length <= 128)
            val recursive = args.flag("recursive")
            val offsetValue = args["offset"]
            requireWebdav(offsetValue == null || offsetValue is JsonPrimitive && !offsetValue.isString &&
                offsetValue.intOrNull in 0..1_000)
            val offset = (offsetValue as? JsonPrimitive)?.intOrNull ?: 0
            requireWebdav(!recursive || offset == 0)
            val pending = ArrayDeque<Pair<String, Int>>().apply { add(path to 0) }
            val visited = mutableSetOf<String>()
            val found = mutableListOf<DavEntry>()
            var truncated = false
            var scanned = 0
            var matched = 0
            while (pending.isNotEmpty() && visited.size < 32 && found.size < 100 && scanned < 2_000) {
                repository.assertCurrent(config.revision)
                val (folder, depth) = pending.removeFirst()
                if (!visited.add(folder)) continue
                val listing = client.list(folder)
                truncated = truncated || listing.truncated
                for (entry in listing.entries.sortedBy { it.path }) {
                    if (scanned >= 2_000) { truncated = true; break }
                    scanned++
                    if (entry.path.substringAfterLast('/').contains(query, ignoreCase = true)) {
                        if (matched++ >= offset) {
                            if (found.size < 100) found += entry else truncated = true
                        }
                    }
                    if (recursive && entry.directory) {
                        if (depth < 5 && pending.size < 64) pending.add(entry.path to depth + 1) else truncated = true
                    }
                }
            }
            buildJsonObject {
                put("entries", Json.encodeToJsonElement(found))
                put("truncated", truncated || pending.isNotEmpty())
                if (!recursive && matched > offset + found.size) put("next_offset", offset + found.size)
                put("search_kind", "filename")
                put("warning", "Listings may be limited by the server. Search is bounded, not a full-text index.")
            }
        }
        "webdav_read" -> if (args.flag("metadata_only")) {
            Json.encodeToJsonElement(client.stat(path)).jsonObject
        } else {
            val text = client.read(path)
            buildJsonObject { put("path", path); put("content", text.text); text.etag?.let { put("etag", it) } }
        }
        "webdav_write" -> {
            requireWebdav(args.flag("confirmed"), WebdavError.PERMISSION_DENIED)
            val code = client.write(path, args.text("content"), args.optionalText("expected_etag"))
            receipt("write", path, code)
        }
        "webdav_manage" -> {
            requireWebdav(args.flag("confirmed"), WebdavError.PERMISSION_DENIED)
            val action = args.text("action")
            val code = if (action == "mkdir") {
                client.mkdir(path)
            } else {
                requireWebdav(action in setOf("delete", "move", "copy"))
                val before = client.stat(path)
                val etag = args.text("expected_etag")
                requireWebdav(before.etag == etag, WebdavError.CONFLICT)
                if (before.directory) requireWebdav(args.flag("recursive"), WebdavError.PERMISSION_DENIED)
                repository.assertCurrent(config.revision)
                if (action == "delete") client.delete(path, etag, before.directory)
                else client.moveOrCopy(path, args.text("destination"), etag, action == "copy", before.directory)
            }
            receipt(action, path, code)
        }
        else -> when (args.text("action")) {
            "list" -> buildJsonObject { put("files", Json.encodeToJsonElement(repository.transfers())) }
            "download" -> {
                val transfer = repository.createTransfer(path, "download", config.revision) { file, maximum ->
                    client.download(path, file, maximum)
                }
                buildJsonObject {
                    put("file", Json.encodeToJsonElement(transfer))
                    put("state", "ready_to_export")
                    put("message", "Open Tools > WebDAV Files > Connection and files, then Save to device. This is not yet a file in Downloads.")
                }
            }
            "upload" -> {
                requireWebdav(args.flag("confirmed"), WebdavError.PERMISSION_DENIED)
                repository.withTransfer(args.text("file_id")) { transfer, file ->
                    requireWebdav(transfer.direction == "import", WebdavError.PERMISSION_DENIED)
                    repository.assertCurrent(config.revision)
                    receipt("upload", path, client.upload(path, file, args.optionalText("expected_etag")))
                }
            }
            "forget" -> {
                requireWebdav(args.flag("confirmed"), WebdavError.PERMISSION_DENIED)
                repository.forgetTransfer(args.text("file_id"))
                buildJsonObject { put("state", "forgotten"); put("remote_file_changed", false) }
            }
            else -> throw WebdavException(WebdavError.INVALID_ARGUMENT)
        }
    }
    repository.assertCurrent(config.revision)
    return result
}

private fun receipt(action: String, path: String, status: Int): JsonObject = buildJsonObject {
    put("action", action); put("path", path); put("http_status", status)
    put("server_accepted", true)
    put("message", "The server accepted this operation. Read metadata/content to verify the resulting state; do not repeat the write.")
}

private fun JsonObject.optionalText(name: String): String? {
    val value = this[name] ?: return null
    requireWebdav(value is JsonPrimitive && value.isString)
    return (value as JsonPrimitive).content
}
private fun JsonObject.text(name: String, default: String? = null): String =
    optionalText(name) ?: default ?: throw WebdavException(WebdavError.INVALID_ARGUMENT)
private fun JsonObject.flag(name: String): Boolean {
    val value = this[name] ?: return false
    requireWebdav(value is JsonPrimitive && !value.isString && value.booleanOrNull != null)
    return (value as JsonPrimitive).booleanOrNull == true
}

internal val WEBDAV_TOOLS = listOf(
    ExtensionToolDefinition("webdav_list",
        "List scoped WebDAV files or search file names. Recursive search is bounded; report truncation, not exhaustive results.",
        """{"type":"object","properties":{"path":{"type":"string","maxLength":2048},"query":{"type":"string","maxLength":128},"recursive":{"type":"boolean"},"offset":{"type":"integer","minimum":0,"maximum":1000,"description":"Non-recursive pages only; use next_offset from the preceding response. Each page is a fresh listing."}},"additionalProperties":false}""",
        ExtensionRiskLevel.READ, true),
    ExtensionToolDefinition("webdav_read",
        "Read a scoped UTF-8 text file up to 32 KiB, or metadata_only for any file/folder and its ETag. Binary bytes never enter model context.",
        """{"type":"object","properties":{"path":{"type":"string","maxLength":2048},"metadata_only":{"type":"boolean"}},"required":["path"],"additionalProperties":false}""",
        ExtensionRiskLevel.READ, true),
    ExtensionToolDefinition("webdav_write",
        "Create or update a UTF-8 text file after explicit user intent (confirmed=true). Omit expected_etag only for create; updates require the strong ETag read earlier. Never retry an uncertain write.",
        """{"type":"object","properties":{"path":{"type":"string","maxLength":2048},"content":{"type":"string","maxLength":32768},"expected_etag":{"type":"string","maxLength":256},"confirmed":{"type":"boolean"}},"required":["path","content","confirmed"],"additionalProperties":false}""",
        ExtensionRiskLevel.WRITE, false),
    ExtensionToolDefinition("webdav_manage",
        "Create a directory, copy, move, or delete within the configured root. Requires explicit user intent and confirmed=true. Existing resources require their strong expected_etag; folders additionally require recursive=true. Copy/move never overwrite. Root mutation is forbidden.",
        """{"type":"object","properties":{"action":{"type":"string","enum":["mkdir","copy","move","delete"]},"path":{"type":"string","maxLength":2048},"destination":{"type":"string","maxLength":2048},"expected_etag":{"type":"string","maxLength":256},"recursive":{"type":"boolean"},"confirmed":{"type":"boolean"}},"required":["action","path","confirmed"],"additionalProperties":false}""",
        ExtensionRiskLevel.SENSITIVE, false),
    ExtensionToolDefinition("webdav_transfer",
        "List staged files; download up to 32 MiB for native Save to device; upload a file_id explicitly imported through the system picker; or forget a staged copy. No raw local paths/URIs/base64. Upload/forget require confirmed user intent. Existing uploads require expected_etag. Files expire after one hour.",
        """{"type":"object","properties":{"action":{"type":"string","enum":["list","download","upload","forget"]},"path":{"type":"string","maxLength":2048},"file_id":{"type":"string","maxLength":36},"expected_etag":{"type":"string","maxLength":256},"confirmed":{"type":"boolean"}},"required":["action"],"additionalProperties":false}""",
        ExtensionRiskLevel.SENSITIVE, false),
)
