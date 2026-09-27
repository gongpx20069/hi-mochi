package com.example.mochi_pet.core.agentlink

import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentLinkResultsTest {
    @Test fun `only exact owned receipts are accepted and unknown is not success`() {
        val task = followed()
        fun data(receipt: AgentLinkReceipt) = buildJsonObject {
            put("online", true)
            put("task", Json { encodeDefaults = true }.encodeToJsonElement(AgentLinkReceipt.serializer(), receipt))
        }
        assertEquals("completed", parseAgentLinkReceipt(data(receipt()), task).state)
        for (invalid in listOf(receipt().copy(taskId = "other"), receipt().copy(chatId = "other"),
            receipt().copy(source = "human"), receipt().copy(state = "idle"),
            receipt().copy(revision = -1), receipt().copy(resultText = "x".repeat(2001)))) {
            assertThrows(IllegalStateException::class.java) { parseAgentLinkReceipt(data(invalid), task) }
        }
    }

    @Test fun `briefing is factual local and viewed is separate from announced`() {
        val task = followed().copy(receipt = receipt(), viewedRevision = 1)
        assertTrue(task.pendingAnnouncement)
        val state = AgentLinkResultState(listOf(task), canAnnounce = true, readableMachineIds = setOf("machine"))
        assertTrue(state.briefing(true)!!.text.contains("跑完"))
        assertFalse(state.briefing(true)!!.text.contains("通过"))
        assertEquals(null, state.copy(canAnnounce = false).briefing(false))
        assertEquals(null, state.copy(readableMachineIds = setOf("different-machine")).briefing(false))
        assertEquals(null, state.copy(tasks = listOf(task.copy(announcedRevision = 1))).briefing(true))
        val waiting = task.copy(taskId = "second", receipt = receipt().copy(taskId = "second", state = "waitingApproval"))
        assertTrue(state.copy(tasks = listOf(task, waiting)).briefing(true)!!.text.contains("需要你"))
    }

    @Test fun `monitor reads submitted operations only and revocation stops reads`() = runBlocking {
        val repository = ResultsFake(AgentLinkResultState(listOf(followed())))
        val client = ReceiptClient()
        val monitor = AgentLinkResultMonitor(client, repository)
        assertTrue(monitor.refresh())
        assertEquals(listOf("task"), client.actions)
        assertTrue(repository.state.value.tasks.single().unread)
        assertTrue(monitor.refresh())
        assertEquals(1, client.actions.size)
        client.enabled = false
        assertTrue(monitor.refresh())
        assertFalse(repository.state.value.canAnnounce)
        assertEquals(1, client.actions.size)
    }

    @Test fun `failed reads preserve unknown task and never retry writes`() = runBlocking {
        val repository = ResultsFake(AgentLinkResultState(listOf(followed())))
        val client = ReceiptClient().apply { fail = true }
        assertFalse(AgentLinkResultMonitor(client, repository).refresh())
        assertNull(repository.state.value.tasks.single().receipt)
        assertNotNull(repository.state.value.error)
        assertEquals(listOf("task"), client.actions)
    }
}

internal fun followed() = FollowedAgentLinkTask("machine", "chat", "operation", "A remote task")
internal fun receipt() = AgentLinkReceipt("chat", "operation", "mochi", "completed", 1, 100, "Remote answer")

internal class ResultsFake(initial: AgentLinkResultState) : AgentLinkResultRepository {
    override val state = MutableStateFlow(initial)
    override suspend fun load() = Unit
    override suspend fun follow(task: FollowedAgentLinkTask) { state.value = state.value.copy(tasks = state.value.tasks + task) }
    override suspend fun receive(key: String, receipt: AgentLinkReceipt) {
        state.value = state.value.copy(tasks = state.value.tasks.map { if (it.key == key) it.copy(receipt = receipt) else it })
    }
    override suspend fun acknowledge(versions: List<AgentLinkResultVersion>, viewed: Boolean, announced: Boolean) {
        state.value = state.value.copy(tasks = state.value.tasks.map { task ->
            val revision = versions.firstOrNull { it.key == task.key }?.revision
            if (revision != task.receipt?.revision || revision == null) task else task.copy(
                viewedRevision = if (viewed) revision else task.viewedRevision,
                announcedRevision = if (announced) revision else task.announcedRevision,
            )
        })
    }
    override suspend fun clear() { state.value = AgentLinkResultState() }
    override fun availability(allowed: Boolean, error: String?, readableMachineIds: Set<String>) {
        state.value = state.value.copy(canAnnounce = allowed, error = error, readableMachineIds = readableMachineIds)
    }
}

private class ReceiptClient : AgentLinkClient {
    var enabled = true
    var fail = false
    val actions = mutableListOf<String>()
    override suspend fun refresh() = AgentLinkState(installed = true, authorized = true, connected = true, enabled = enabled,
        readableMachineIds = setOf("machine"))
    override suspend fun execute(tool: String, request: AgentLinkRequest): ToolResultEnvelope {
        actions += request.action
        assertEquals("agentlink_chat", tool)
        assertEquals("operation", request.taskId)
        return if (fail) ToolResultEnvelope.error(ToolErrorCode.TIMEOUT, "Unavailable")
        else ToolResultEnvelope.success(buildJsonObject {
            put("online", true)
            put("task", Json { encodeDefaults = true }.encodeToJsonElement(AgentLinkReceipt.serializer(), receipt()))
        })
    }
    override suspend fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    override suspend fun setToolEnabled(name: String, enabled: Boolean) = Unit
    override suspend fun beginAuthorization(): AgentLinkActivityRequest = error("Not used")
    override suspend fun completeAuthorization(requestId: String?, version: Int, accepted: Boolean) = Unit
    override suspend fun revoke() { enabled = false }
    override suspend fun remember(link: AgentLinkChatLink) = Unit
}
