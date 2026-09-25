package com.example.mochi_pet.core.tasks

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agentlink.AgentLinkChatLink
import com.example.mochi_pet.core.agentlink.AgentLinkClient
import com.example.mochi_pet.core.agentlink.AgentLinkExecution
import com.example.mochi_pet.core.agentlink.AgentLinkTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentLinkTaskSnapshot(
    val link: AgentLinkChatLink,
    val id: String,
    val status: TaskStatus,
)

suspend fun readAgentLinkTasks(
    client: AgentLinkClient,
    link: AgentLinkChatLink,
    context: ToolExecutionContext,
): List<AgentLinkTaskSnapshot> {
    val result = AgentLinkTool("agentlink_chat", client, AgentLinkExecution()).execute(
        buildJsonObject {
            put("action", "read")
            put("machineId", link.machineId)
            put("chatId", link.chatId)
            put("limit", 1)
        }, context,
    )
    check(result.status == "ok") { "AgentLink task read failed" }
    val tasks = (result.data as? JsonObject)?.get("tasks") as? JsonArray
        ?: error("AgentLink omitted task state")
    return tasks.take(100).map { element ->
        val task = element as? JsonObject ?: error("Invalid AgentLink task")
        val id = (task["taskId"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it.length <= 256 } ?: error("Invalid AgentLink task ID")
        val status = when ((task["state"] as? JsonPrimitive)?.content) {
            "queued", "pending" -> TaskStatus.QUEUED
            "running", "active" -> TaskStatus.RUNNING
            "completed", "succeeded" -> TaskStatus.SUCCEEDED
            "failed" -> TaskStatus.FAILED
            "cancelled", "canceled" -> TaskStatus.CANCELLED
            else -> TaskStatus.UNKNOWN
        }
        AgentLinkTaskSnapshot(link, id, status)
    }.distinctBy { it.id }
}
