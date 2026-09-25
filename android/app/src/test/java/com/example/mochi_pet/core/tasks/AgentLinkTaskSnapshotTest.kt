package com.example.mochi_pet.core.tasks

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agentlink.AgentLinkActivityRequest
import com.example.mochi_pet.core.agentlink.AgentLinkChatLink
import com.example.mochi_pet.core.agentlink.AgentLinkClient
import com.example.mochi_pet.core.agentlink.AgentLinkRequest
import com.example.mochi_pet.core.agentlink.AgentLinkState
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLinkTaskSnapshotTest {
    private val link = AgentLinkChatLink("machine", "chat")
    private val context = ToolExecutionContext(LocalDate.of(2026, 9, 25), MochiSurface.Tools)

    @Test fun `refresh is a guarded bounded read and unknown remote states remain unknown`() = runTest {
        val client = FakeClient()
        val tasks = readAgentLinkTasks(client, link, context)
        assertEquals(listOf(TaskStatus.RUNNING, TaskStatus.UNKNOWN), tasks.map { it.status })
        assertEquals(listOf("read"), client.requests.map { it.action })
        assertEquals(1, client.requests.single().limit)
    }

    @Test fun `disabled chat tool cannot be bypassed by task center`() = runTest {
        val client = FakeClient()
        client.state = client.state.copy(enabledTools = emptySet())
        try {
            readAgentLinkTasks(client, link, context)
            error("Expected unavailable read")
        } catch (_: IllegalStateException) {
            assertTrue(client.requests.isEmpty())
        }
    }

    private class FakeClient : AgentLinkClient {
        var state = AgentLinkState(installed = true, authorized = true, connected = true, enabled = true)
        val requests = mutableListOf<AgentLinkRequest>()
        override suspend fun refresh() = state
        override suspend fun execute(tool: String, request: AgentLinkRequest): ToolResultEnvelope {
            requests += request
            return ToolResultEnvelope.success(buildJsonObject {
                put("chat", buildJsonObject { put("chatId", "chat"); put("humanRevision", 1) })
                put("tasks", buildJsonArray {
                    add(buildJsonObject { put("taskId", "one"); put("state", "running") })
                    add(buildJsonObject { put("taskId", "two"); put("state", "future_unknown_state") })
                })
            })
        }
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun setToolEnabled(name: String, enabled: Boolean) = Unit
        override suspend fun beginAuthorization() = AgentLinkActivityRequest("test")
        override suspend fun completeAuthorization(requestId: String?, version: Int, accepted: Boolean) = Unit
        override suspend fun revoke() = Unit
        override suspend fun remember(link: AgentLinkChatLink) = Unit
    }
}
