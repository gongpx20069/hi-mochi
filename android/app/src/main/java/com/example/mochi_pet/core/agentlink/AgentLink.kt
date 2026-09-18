package com.example.mochi_pet.core.agentlink

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val AGENTLINK_TOOLS = setOf("agentlink_workspace", "agentlink_chat", "agentlink_control")

@Serializable
data class AgentLinkRequest(
    val action: String,
    val machineId: String? = null,
    val workspaceId: String? = null,
    val chatId: String? = null,
    val agentId: String? = null,
    val title: String? = null,
    val content: String? = null,
    val afterEventId: Long? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val taskId: String? = null,
    val path: String? = null,
    val mode: String? = null,
    val url: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val sourceWorkspaceId: String? = null,
    val branch: String? = null,
    val configId: String? = null,
    val value: String? = null,
    val operationId: String? = null,
    val source: String? = null,
    val expectedHumanRevision: Long? = null,
)

@Serializable
data class AgentLinkChatLink(
    val machineId: String,
    val chatId: String,
    val title: String = chatId,
    val taskId: String? = null,
    val cursor: Long? = null,
    val operationId: String? = null,
    val outcomeUnknown: Boolean = false,
)

data class AgentLinkState(
    val installed: Boolean = false,
    val authorized: Boolean = false,
    val connected: Boolean = false,
    val enabled: Boolean = false,
    val status: String = "not_installed",
    val enabledTools: Set<String> = AGENTLINK_TOOLS,
    val links: List<AgentLinkChatLink> = emptyList(),
)

data class AgentLinkActivityRequest(
    val requestId: String,
    val chat: AgentLinkChatLink? = null,
    val authorize: Boolean = true,
)

data class AgentLinkAuthorizationResult(
    val requestId: String?,
    val version: Int,
    val accepted: Boolean,
)

sealed interface AgentLinkUiAction {
    data object Connect : AgentLinkUiAction
    data object Refresh : AgentLinkUiAction
    data object Revoke : AgentLinkUiAction
    data object Manager : AgentLinkUiAction
    data class Enable(val enabled: Boolean) : AgentLinkUiAction
    data class EnableTool(val name: String, val enabled: Boolean) : AgentLinkUiAction
    data class OpenChat(val link: AgentLinkChatLink) : AgentLinkUiAction
}

interface AgentLinkClient {
    suspend fun refresh(): AgentLinkState
    suspend fun execute(tool: String, request: AgentLinkRequest): ToolResultEnvelope
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setToolEnabled(name: String, enabled: Boolean)
    suspend fun beginAuthorization(): AgentLinkActivityRequest
    suspend fun completeAuthorization(requestId: String?, version: Int, accepted: Boolean)
    suspend fun revoke()
    suspend fun remember(link: AgentLinkChatLink)
}

/** One foreground registry owns the read guards; revisions never survive a run. */
class AgentLinkExecution {
    val mutex = Mutex()
    val revisions = mutableMapOf<Pair<String, String>, Long>()
    val blocked = mutableSetOf<Pair<String, String>>()
    val uncertainCreates = mutableSetOf<Pair<String, AgentLinkRequest>>()
    val tasks = mutableMapOf<Pair<String, String>, Set<String>>()
}

class AgentLinkTool(
    override val name: String,
    private val client: AgentLinkClient,
    private val execution: AgentLinkExecution,
) : AgentTool {
    init {
        require(name in AGENTLINK_TOOLS)
    }

    private val actions = when (name) {
        "agentlink_workspace" -> listOf("list", "create")
        "agentlink_chat" -> listOf("list", "create", "read", "open")
        else -> listOf("send", "cancel", "configure")
    }
    override val schema = functionToolSchema(
        name,
        "Control authorized shared AgentLink CLI/App chats. Load agentlink skill first. " +
            "List to ground IDs; read before control. Human changes cause CONFLICT: stop, never retry. " +
            "No browser/network bypass. Permission requires Tools > AgentLink confirmation.",
        buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(actions.map(::JsonPrimitive)))
            })
            listOf(
                "machineId", "workspaceId", "chatId", "agentId", "title", "content",
                "path", "mode", "url", "name", "displayName", "sourceWorkspaceId", "branch", "configId", "value", "taskId",
            ).forEach { field ->
                put(field, buildJsonObject { put("type", "string") })
            }
            put("limit", buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 100)
            })
            put("afterEventId", buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
            })
            put("offset", buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
            })
        },
        listOf("action"),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope = execution.mutex.withLock {
        // Operation IDs and revision guards belong to the executor, never model arguments.
        require(arguments.keys.none { it in setOf("operationId", "source", "expectedHumanRevision") }) {
            "Control guards are application-owned"
        }
        val request = Json.decodeFromJsonElement(AgentLinkRequest.serializer(), arguments)
        require(request.action in actions) { "Unsupported action" }
        require(request.limit == null || request.limit in 1..100) { "limit must be 1..100" }
        require(request.afterEventId == null || request.afterEventId >= 0) { "Invalid cursor" }
        require(request.offset == null || request.offset >= 0) { "Invalid offset" }
        require(arguments.toString().toByteArray().size <= 120 * 1024) { "Request too large" }
        listOf(request.machineId, request.chatId, request.workspaceId, request.agentId).forEach {
            require(it == null || (it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl))) {
                "Invalid identifier"
            }
        }
        val state = client.refresh()
        if (!state.authorized || !state.connected || !state.enabled || name !in state.enabledTools) {
            return@withLock ToolResultEnvelope.error(
                ToolErrorCode.PERMISSION_DENIED,
                "AgentLink unavailable. Open Tools > AgentLink > Connect/Open AgentLink; " +
                    "authorize scopes in its native page. Never bypass with browser or network.",
            )
        }
        val key = request.machineId.orEmpty() to request.chatId.orEmpty()
        val control = name == "agentlink_control"
        if (name == "agentlink_chat" && request.action in setOf("read", "open")) {
            require(key.first.isNotBlank() && key.second.isNotBlank()) { "machineId and chatId required" }
        }
        if (name == "agentlink_chat" && request.action == "read") {
            execution.revisions.remove(key)
            execution.tasks.remove(key)
        }
        if (control && request.action == "cancel") {
            require(!request.taskId.isNullOrBlank() && request.taskId in execution.tasks[key].orEmpty()) {
                "Cancel requires a taskId from the latest chat read"
            }
        } else {
            require(request.taskId == null) { "taskId is only valid for cancellation" }
        }
        if (control) {
            require(key.first.isNotBlank() && key.second.isNotBlank()) { "machineId and chatId required" }
            if (key in execution.blocked || key !in execution.revisions) {
                return@withLock ToolResultEnvelope.error(
                    ToolErrorCode.CONFLICT,
                    "Fresh chat read required; after conflict or uncertain write stop this run and ask the user.",
                )
            }
        }
        if (name == "agentlink_chat" && request.action == "open") {
            if (key !in execution.revisions) {
                return@withLock ToolResultEnvelope.error(ToolErrorCode.CONFLICT, "Read this chat first")
            }
            return@withLock ToolResultEnvelope.success(buildJsonObject {
                put("chatId", request.chatId)
                put("instruction", "Tap the linked chat in Tools > AgentLink to open its trusted native view.")
            })
        }
        val guarded = if (control && request.action == "send") request.copy(
            operationId = UUID.randomUUID().toString(),
            source = "mochi",
            expectedHumanRevision = execution.revisions[key],
        ) else if (control && request.action == "cancel") request.copy(
            taskId = null, operationId = request.taskId,
        ) else request
        val createKey = name to request
        if (request.action == "create" && !execution.uncertainCreates.add(createKey)) {
            return@withLock ToolResultEnvelope.error(
                ToolErrorCode.CONFLICT,
                "Previous create outcome is unknown. List remote state; do not create a duplicate.",
            )
        }
        // A lost reply may hide a successful remote write. Block repeats, including after cancellation.
        if (control) {
            execution.blocked.add(key)
            client.remember(AgentLinkChatLink(
                key.first, key.second, operationId = guarded.operationId, outcomeUnknown = true,
            ))
        }
        val result = client.execute(name, guarded)
        if (result.status != "ok") {
            if (result.code == "CONFLICT") execution.blocked.add(key)
            return@withLock result
        }
        execution.uncertainCreates.remove(createKey)
        val data = result.data as? JsonObject
        if (name == "agentlink_chat" && request.action == "read") {
            val chat = data?.get("chat") as? JsonObject
            val revision = chat?.get("humanRevision")?.jsonPrimitive?.longOrNull
            if (chat?.get("chatId")?.jsonPrimitive?.content != request.chatId ||
                revision == null || revision < 0
            ) {
                return@withLock ToolResultEnvelope.error(
                    ToolErrorCode.PROVIDER_ERROR, "AgentLink read omitted authoritative chat identity/revision",
                )
            }
            execution.revisions[key] = revision
            execution.tasks[key] = (data["tasks"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.get("taskId")?.jsonPrimitive?.content
            }.toSet()
            client.remember(AgentLinkChatLink(
                key.first, key.second,
                chat["chatTitle"]?.jsonPrimitive?.content?.take(160) ?: key.second,
                cursor = data["nextEventId"]?.jsonPrimitive?.longOrNull,
            ))
        }
        if (control) {
            execution.blocked.remove(key)
            execution.revisions.remove(key)
            client.remember(AgentLinkChatLink(
                key.first, key.second,
                taskId = data?.get("taskId")?.jsonPrimitive?.content?.take(256),
                operationId = guarded.operationId,
            ))
        }
        result
    }
}
