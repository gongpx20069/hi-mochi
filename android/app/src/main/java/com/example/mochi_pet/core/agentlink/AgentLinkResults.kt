package com.example.mochi_pet.core.agentlink

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class AgentLinkReceipt(
    val chatId: String,
    val taskId: String,
    val source: String,
    val state: String,
    val revision: Long,
    val updatedAt: Long,
    val resultText: String = "",
    val resultTruncated: Boolean = false,
) {
    val terminal: Boolean get() = state in setOf("completed", "failed", "cancelled", "interrupted")
    val actionable: Boolean get() = terminal || state == "waitingApproval"
}

@Serializable
data class FollowedAgentLinkTask(
    val machineId: String,
    val chatId: String,
    val taskId: String,
    val title: String,
    val receipt: AgentLinkReceipt? = null,
    val viewedRevision: Long = -1,
    val announcedRevision: Long = -1,
) {
    val key: String get() = "${machineId.length}:$machineId:${chatId.length}:$chatId:$taskId"
    val unread: Boolean get() = receipt?.let { it.actionable && it.revision > viewedRevision } == true
    val pendingAnnouncement: Boolean get() = receipt?.let { it.actionable && it.revision > announcedRevision } == true
    val link: AgentLinkChatLink get() = AgentLinkChatLink(machineId, chatId, title, taskId = taskId)
}

data class AgentLinkResultState(
    val tasks: List<FollowedAgentLinkTask> = emptyList(),
    val canAnnounce: Boolean = false,
    val error: String? = null,
    val readableMachineIds: Set<String> = emptySet(),
)

data class AgentLinkResultVersion(val key: String, val revision: Long)
data class AgentLinkBriefing(val text: String, val versions: List<AgentLinkResultVersion>)

interface AgentLinkResultRepository {
    val state: StateFlow<AgentLinkResultState>
    suspend fun load()
    suspend fun follow(task: FollowedAgentLinkTask)
    suspend fun receive(key: String, receipt: AgentLinkReceipt)
    suspend fun acknowledge(versions: List<AgentLinkResultVersion>, viewed: Boolean, announced: Boolean)
    suspend fun clear()
    fun availability(allowed: Boolean, error: String? = null, readableMachineIds: Set<String> = emptySet())
}

fun AgentLinkResultState.briefing(chinese: Boolean): AgentLinkBriefing? {
    if (!canAnnounce) return null
    val pending = tasks.filter { it.pendingAnnouncement && it.machineId in readableMachineIds }
    if (pending.isEmpty()) return null
    val needsHelp = pending.count { it.receipt?.state in setOf("waitingApproval", "failed", "interrupted") }
    val text = when {
        pending.size > 1 && needsHelp > 0 -> if (chinese) "远端有新进展，其中有任务需要你看一下。" else "There are remote updates, and something needs your attention."
        pending.size > 1 -> if (chinese) "几项远端任务有结果了，详情放在卡片里。" else "Your remote tasks have updates. The details are in their cards."
        pending.single().receipt?.state == "waitingApproval" -> if (chinese) "上次的任务在等你确认，卡片里可以查看。" else "Your remote task needs your approval. Check its card."
        pending.single().receipt?.state == "failed" -> if (chinese) "上次的远端任务没能完成，需要你看一下。" else "Your remote task couldn't finish and needs a look."
        pending.single().receipt?.state == "interrupted" -> if (chinese) "上次的任务中断了，结果还不能确认。" else "Your remote task was interrupted. Its outcome isn't confirmed."
        pending.single().receipt?.state == "cancelled" -> if (chinese) "上次的远端任务已经取消了。" else "Your remote task was cancelled."
        else -> if (chinese) "上次的远端任务跑完了，结果在卡片里。" else "Your remote task finished running. The result is in its card."
    }
    return AgentLinkBriefing(text, pending.map { AgentLinkResultVersion(it.key, checkNotNull(it.receipt).revision) })
}

fun parseAgentLinkReceipt(data: JsonObject, expected: FollowedAgentLinkTask): AgentLinkReceipt {
    check((data["online"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true) { "Remote observation is not live" }
    val task = data["task"] as? JsonObject ?: error("Missing task receipt")
    fun text(name: String): String = (task[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("Invalid task receipt field")
    val receipt = AgentLinkReceipt(
        chatId = text("chatId"), taskId = text("taskId"), source = text("source"),
        state = text("state"),
        revision = (task["revision"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?.takeIf { it >= 0 } ?: error("Invalid task revision"),
        updatedAt = (task["updatedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?.takeIf { it >= 0 } ?: error("Invalid task timestamp"),
        resultText = text("resultText"),
        resultTruncated = (task["resultTruncated"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: error("Invalid task truncation flag"),
    )
    check(receipt.chatId == expected.chatId && receipt.taskId == expected.taskId && receipt.source == "mochi") {
        "Task receipt identity or provenance mismatch"
    }
    check(receipt.state in setOf("starting", "queued", "running", "waitingApproval", "completed", "failed", "cancelled", "interrupted")) {
        "Unsupported task state"
    }
    // The bridge bounds Unicode code points; supplementary characters use two UTF-16 units.
    check(receipt.resultText.codePointCount(0, receipt.resultText.length) <= 2000) { "Task result is too large" }
    return receipt
}
