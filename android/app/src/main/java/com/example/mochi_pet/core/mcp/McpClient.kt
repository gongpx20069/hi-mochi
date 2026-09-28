package com.example.mochi_pet.core.mcp

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.web.PublicWebUrlPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class McpRemoteTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    },
    val readOnlyHint: Boolean = false,
)

data class McpServerRuntime(
    val id: String,
    val name: String,
    val endpoint: String,
    val accessToken: String?,
    val authorizationHeader: String? =
        accessToken?.let { "Bearer $it" },
    val feishuAllowedTools: Set<String>? = null,
)

open class McpException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class McpAuthenticationException(message: String) : McpException(message)

open class McpStreamableHttpClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val feishuClient = this.client.newBuilder()
        .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    open suspend fun listTools(server: McpServerRuntime): List<McpRemoteTool> =
        withContext(Dispatchers.IO) {
            val session = initialize(server)
            buildList {
                var cursor: String? = null
                repeat(MAX_TOOL_PAGES) {
                    val response = session.request(
                        method = "tools/list",
                        params = buildJsonObject {
                            cursor?.let { put("cursor", it) }
                        },
                    )
                    val result = response.result()
                    val tools = result["tools"]?.jsonArray
                        ?: throw McpException(
                            "MCP tools/list response did not contain tools",
                        )
                    tools.forEach { element ->
                        val tool = element.jsonObject
                        val name = tool.string("name")
                            ?: throw McpException(
                                "MCP tool did not contain a name",
                            )
                        requireSafeToolName(name)
                        add(
                            McpRemoteTool(
                                name = name,
                                description = tool.string("description")
                                    ?.take(MAX_DESCRIPTION_CHARS)
                                    .orEmpty(),
                                inputSchema = tool["inputSchema"]
                                    ?.let { schema ->
                                        runCatching { schema.jsonObject }
                                            .getOrNull()
                                    }
                                    ?: buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {},
                                        )
                                    },
                                readOnlyHint = tool["annotations"]
                                    ?.let { annotations ->
                                        runCatching {
                                            annotations.jsonObject[
                                                "readOnlyHint"
                                            ]?.jsonPrimitive?.content == "true"
                                        }.getOrDefault(false)
                                    }
                                    ?: false,
                            ),
                        )
                    }
                    cursor = result.string("nextCursor")
                    if (cursor == null) {
                        return@buildList
                    }
                }
                throw McpException("MCP tool list exceeded pagination limit")
            }.distinctBy(McpRemoteTool::name).take(MAX_TOOLS)
        }

    open suspend fun callTool(
        server: McpServerRuntime,
        toolName: String,
        arguments: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        requireSafeToolName(toolName)
        if (server.id == FEISHU_SERVER_ID) {
            require(server.feishuAllowedTools?.contains(toolName) == true) {
                "Feishu tool is not enabled"
            }
        }
        val response = initialize(server).request(
            method = "tools/call",
            params = buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            },
        )
        val result = response.result()
        if (
            result["isError"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() == true
        ) {
            throw McpException(
                result.contentText().ifBlank {
                    "MCP tool reported an error"
                },
            )
        }
        buildJsonObject {
            put("server", server.name)
            put("tool", toolName)
            result["structuredContent"]?.let {
                put("structured_content", it)
            }
            result["content"]?.let { put("content", it) }
            put("text", result.contentText().take(MAX_RESULT_TEXT_CHARS))
        }
    }

    private suspend fun initialize(server: McpServerRuntime): Session {
        if (server.feishuAllowedTools != null || server.id == FEISHU_SERVER_ID) {
            require(server.id == FEISHU_SERVER_ID && server.endpoint == FEISHU_MCP_ENDPOINT &&
                !server.accessToken.isNullOrBlank() &&
                !server.feishuAllowedTools.isNullOrEmpty() &&
                FEISHU_MCP_TOOLS.containsAll(server.feishuAllowedTools)) {
                "Invalid Feishu MCP configuration"
            }
        }
        val endpoint = try {
            PublicWebUrlPolicy.validate(server.endpoint).toString()
        } catch (error: Exception) {
            throw McpException("MCP endpoint is not a permitted public URL", error)
        }
        val session = Session(
            endpoint = endpoint,
            accessToken = server.accessToken,
            authorizationHeader = server.authorizationHeader,
            feishuAllowedTools = server.feishuAllowedTools,
        )
        val response = session.requestResponse(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put(
                    "capabilities",
                    buildJsonObject {},
                )
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "Mochi")
                        put("version", "1.0.0")
                    },
                )
            },
        )
        response.payload.result()
        session.sessionId = response.sessionId
        session.notify(
            method = "notifications/initialized",
            params = buildJsonObject {},
        )
        return session
    }

    private inner class Session(
        private val endpoint: String,
        private val accessToken: String?,
        private val authorizationHeader: String?,
        private val feishuAllowedTools: Set<String>?,
    ) {
        var sessionId: String? = null
        private var nextId = 1L

        suspend fun request(
            method: String,
            params: JsonObject,
        ): JsonObject = requestResponse(method, params).payload

        suspend fun requestResponse(
            method: String,
            params: JsonObject,
        ): McpHttpResponse {
            val id = nextId++
            val response = execute(
                payload = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
                notification = false,
            )
            val payload = response.payloadForId(id)
            return response.copy(payload = payload)
        }

        suspend fun notify(
            method: String,
            params: JsonObject,
        ) {
            execute(
                payload = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", params)
                },
                notification = true,
            )
        }

        private suspend fun execute(
            payload: JsonObject,
            notification: Boolean,
        ): McpHttpResponse {
            val request = Request.Builder()
                .url(endpoint)
                .post(
                    payload.toString().toRequestBody(JSON_MEDIA_TYPE),
                )
                .header(
                    "Accept",
                    "application/json, text/event-stream",
                )
                .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
                .header("User-Agent", "Mochi-Android/1.0")
                .apply {
                    accessToken?.takeIf(String::isNotBlank)?.let {
                        header("Authorization", "Bearer $it")
                    }
                    sessionId?.let { header("Mcp-Session-Id", it) }
                }
                .apply {
                    accessToken?.takeIf(String::isNotBlank)?.let { token ->
                        header("Authorization", "Bearer $token")
                    }
                }
                .apply {
                    authorizationHeader
                        ?.takeIf(String::isNotBlank)
                        ?.let { value ->
                            header("Authorization", value)
                        }
                }
                .apply {
                    if (feishuAllowedTools != null) {
                        removeHeader("Authorization")
                        header("X-Lark-MCP-UAT", requireNotNull(accessToken))
                        header("X-Lark-MCP-Allowed-Tools", feishuAllowedTools.joinToString(","))
                    }
                }
                .build()
            if (feishuAllowedTools != null) {
                return suspendCancellableCoroutine { continuation ->
                    val call = feishuClient.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            continuation.resumeWithException(McpException("MCP network request failed", e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                continuation.resume(parseResponse(response, notification))
                            } catch (error: McpException) {
                                continuation.resumeWithException(error)
                            } catch (error: IOException) {
                                continuation.resumeWithException(McpException("MCP network request failed", error))
                            }
                        }
                    })
                }
            }
            try {
                return parseResponse(client.newCall(request).execute(), notification)
            } catch (error: IOException) {
                throw McpException("MCP network request failed", error)
            }
        }

        private fun parseResponse(response: Response, notification: Boolean): McpHttpResponse =
            response.use {
                if (response.code == 401 || response.code == 403) {
                    throw McpAuthenticationException("MCP authorization is required or expired")
                }
                if (!response.isSuccessful) {
                    throw McpException("MCP request failed with HTTP ${response.code}")
                }
                val returnedSession = response.header("Mcp-Session-Id") ?: sessionId
                if (notification) {
                    return@use McpHttpResponse(
                        payload = buildJsonObject {},
                        sessionId = returnedSession,
                    )
                }
                val body = response.body
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    throw McpException("MCP response was too large")
                }
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES + 1)
                if (source.buffer.size > MAX_RESPONSE_BYTES) {
                    throw McpException("MCP response was too large")
                }
                val raw = source.readUtf8()
                val messages = if (response.header("Content-Type")?.startsWith("text/event-stream") == true) {
                    raw.lineSequence()
                        .filter { it.startsWith("data:") }
                        .map { it.removePrefix("data:").trim() }
                        .filter(String::isNotEmpty)
                        .map(::parseObject)
                        .toList()
                } else {
                    listOf(parseObject(raw))
                }
                McpHttpResponse(
                    payload = messages.lastOrNull() ?: throw McpException("MCP response contained no messages"),
                    messages = messages,
                    sessionId = returnedSession,
                )
            }
    }

    private fun parseObject(raw: String): JsonObject =
        try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: SerializationException) {
            throw McpException("MCP response was not valid JSON", error)
        } catch (error: IllegalArgumentException) {
            throw McpException("MCP response was not a JSON object", error)
        }

    private data class McpHttpResponse(
        val payload: JsonObject,
        val messages: List<JsonObject> = listOf(payload),
        val sessionId: String?,
    ) {
        fun payloadForId(id: Long): JsonObject =
            messages.firstOrNull {
                it["id"]?.jsonPrimitive?.contentOrNull == id.toString()
            } ?: throw McpException(
                "MCP response did not contain request id $id",
            )
    }

    private fun JsonObject.result(): JsonObject {
        this["error"]?.let { error ->
            val value = runCatching { error.jsonObject }.getOrNull()
            throw McpException(
                value?.string("message") ?: "MCP returned an error",
            )
        }
        return this["result"]?.jsonObject
            ?: throw McpException("MCP response did not contain a result")
    }
}

class McpAgentTool(
    override val name: String,
    private val remoteTool: McpRemoteTool,
    private val server: suspend () -> McpServerRuntime?,
    private val client: McpStreamableHttpClient,
) : AgentTool {
    override val schema: JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", name)
                put(
                    "description",
                    remoteTool.description.ifBlank {
                        "Call ${remoteTool.name} on the configured MCP server."
                    },
                )
                put("parameters", remoteTool.inputSchema)
            },
        )
    }

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        return try {
            val runtime = server()
                ?: return ToolResultEnvelope.error(
                    ToolErrorCode.PERMISSION_DENIED,
                    "MCP connection is not configured or enabled",
                )
            ToolResultEnvelope.success(
                client.callTool(
                    server = runtime,
                    toolName = remoteTool.name,
                    arguments = arguments,
                ),
            )
        } catch (error: IllegalArgumentException) {
            ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                error.message ?: "MCP tool arguments are invalid",
            )
        } catch (error: McpAuthenticationException) {
            ToolResultEnvelope.error(
                ToolErrorCode.PERMISSION_DENIED,
                error.message ?: "MCP authorization failed",
            )
        } catch (error: McpException) {
            ToolResultEnvelope.error(
                ToolErrorCode.PROVIDER_ERROR,
                error.message ?: "MCP tool failed",
            )
        }
    }
}

fun mcpToolAlias(
    serverId: String,
    remoteName: String,
): String {
    val server = serverId.lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .trim('_')
        .take(16)
    val tool = remoteName.lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .trim('_')
        .replace('-', '_')
    if (server == NOTION_SERVER_ID) {
        return tool.take(MAX_ALIAS_CHARS)
    }
    if (server == TENCENT_DOCS_SERVER_ID) {
        return "tencent_docs_${tool.take(51)}".take(MAX_ALIAS_CHARS)
    }
    if (server == FEISHU_SERVER_ID) {
        return "feishu_${tool.take(57)}"
    }
    val suffix = (serverId + '\u0000' + remoteName)
        .hashCode()
        .toUInt()
        .toString(16)
        .padStart(8, '0')
    return "mcp_${server}_${tool.take(30)}_$suffix".take(MAX_ALIAS_CHARS)
}

private fun requireSafeToolName(name: String) {
    if (
        name.isBlank() ||
        name.length > MAX_REMOTE_TOOL_NAME_CHARS ||
        !REMOTE_TOOL_NAME.matches(name)
    ) {
        throw McpException("MCP server returned an invalid tool name")
    }
}

private fun JsonObject.string(name: String): String? =
    this[name]?.let { value ->
        runCatching { value.jsonPrimitive.contentOrNull }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

private fun JsonObject.contentText(): String =
    this["content"]?.let { content ->
        runCatching {
            content.jsonArray.mapNotNull { block ->
                val value = block.jsonObject
                if (value.string("type") == "text") {
                    value.string("text")
                } else {
                    null
                }
            }.joinToString("\n")
        }.getOrDefault("")
    }.orEmpty()

const val NOTION_SERVER_ID = "notion"
const val NOTION_MCP_ENDPOINT = "https://mcp.notion.com/mcp"
const val TENCENT_DOCS_SERVER_ID = "tencent-docs"
const val TENCENT_DOCS_MCP_ENDPOINT = "https://docs.qq.com/openapi/mcp"
const val FEISHU_SERVER_ID = "feishu"
const val FEISHU_MCP_ENDPOINT = "https://mcp.feishu.cn/mcp"
val FEISHU_MCP_TOOLS = setOf("search-doc", "fetch-doc", "create-doc", "update-doc", "list-docs")
private const val MCP_PROTOCOL_VERSION = "2025-06-18"
private const val DEFAULT_TIMEOUT_SECONDS = 30L
private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val MAX_RESULT_TEXT_CHARS = 100_000
private const val MAX_DESCRIPTION_CHARS = 1_000
private const val MAX_REMOTE_TOOL_NAME_CHARS = 128
private const val MAX_ALIAS_CHARS = 64
private const val MAX_TOOLS = 256
private const val MAX_TOOL_PAGES = 10
private val REMOTE_TOOL_NAME = Regex("[A-Za-z0-9_.-]+")
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
