package com.example.mochi_pet.core.agentlink

import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.database.dao.SkillDao
import com.example.mochi_pet.core.database.entity.SkillEntity
import com.example.mochi_pet.core.skills.LoadSkillTool
import com.example.mochi_pet.core.skills.RoomSkillRepository
import com.example.mochi_pet.core.skills.requiredToolNames
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_pet.core.tools.readyToolNames
import com.example.mochi_pet.core.tools.skillReadiness
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLinkToolTest {
    private val context = ToolExecutionContext(LocalDate.of(2026, 9, 18), MochiSurface.Conversation)
    private val client = FakeClient()
    private val execution = AgentLinkExecution()
    private val registry = ToolRegistry(AGENTLINK_TOOLS.map { AgentLinkTool(it, client, execution) })

    @Test
    fun `exactly three schemas and no model owned write guards`() {
        assertEquals(setOf("agentlink_workspace", "agentlink_chat", "agentlink_control"), registry.names)
        registry.schemas.forEach {
            assertFalse(it.toString().contains("\"operationId\""))
            assertFalse(it.toString().contains("\"expectedHumanRevision\""))
        }
    }

    @Test
    fun `read guards send and operation identity is generated locally`() = runBlocking {
        assertEquals("CONFLICT", call("agentlink_control", "send").code)
        assertEquals("ok", call("agentlink_chat", "read").status)
        assertEquals("ok", call("agentlink_control", "send").status)
        val sent = client.calls.last()
        assertEquals(7L, sent.expectedHumanRevision)
        assertEquals("mochi", sent.source)
        assertNotNull(sent.operationId)
        assertEquals("CONFLICT", call("agentlink_control", "send").code)
        assertEquals("task-1", client.links.last().taskId)
    }

    @Test
    fun `human conflict blocks even a later successful read in same run`() = runBlocking {
        call("agentlink_chat", "read")
        client.writeResult = ToolResultEnvelope.error(ToolErrorCode.CONFLICT, "Human override")
        assertEquals("CONFLICT", call("agentlink_control", "send").code)
        call("agentlink_chat", "read")
        val count = client.calls.size
        assertEquals("CONFLICT", call("agentlink_control", "configure").code)
        assertEquals(count, client.calls.size)
    }

    @Test
    fun `unknown write outcome never auto retries`() = runBlocking {
        call("agentlink_chat", "read")
        client.writeResult = ToolResultEnvelope.error(ToolErrorCode.TIMEOUT, "Unknown outcome")
        assertEquals("TIMEOUT", call("agentlink_control", "send").code)
        call("agentlink_chat", "read")
        assertEquals("CONFLICT", call("agentlink_control", "send").code)
        assertEquals(1, client.calls.count { it.action == "send" })
    }

    @Test
    fun `cancelling local wait retains operation linkage without remote cancel`() = runBlocking {
        call("agentlink_chat", "read")
        client.cancelWait = true
        try {
            call("agentlink_control", "send")
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(client.links.last().outcomeUnknown)
            assertNotNull(client.links.last().operationId)
            assertFalse(client.calls.any { it.action == "cancel" })
        }
        client.cancelWait = false
        call("agentlink_chat", "read")
        assertEquals("CONFLICT", call("agentlink_control", "send").code)
    }

    @Test
    fun `uncertain creates are not duplicated`() = runBlocking {
        client.writeResult = ToolResultEnvelope.error(ToolErrorCode.TIMEOUT, "Unknown outcome")
        assertEquals("TIMEOUT", call("agentlink_chat", "create").code)
        assertEquals("CONFLICT", call("agentlink_chat", "create").code)
        assertEquals(1, client.calls.count { it.action == "create" })
    }

    @Test
    fun `disabled unavailable and disconnected calls fail closed`() = runBlocking {
        listOf(
            client.state.copy(enabled = false),
            client.state.copy(authorized = false),
            client.state.copy(connected = false),
            client.state.copy(enabledTools = emptySet()),
        ).forEach {
            client.state = it
            assertEquals("PERMISSION_DENIED", call("agentlink_chat", "read").code)
        }
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `model cannot supply operation ids revisions or arbitrary navigation`() = runBlocking {
        listOf("operationId", "expectedHumanRevision", "source", "intent", "uri").forEach { field ->
            val result = registry.execute("agentlink_control", buildJsonObject {
                put("action", "send")
                put(field, "untrusted")
            }, context)
            assertEquals("INVALID_ARGS", result.code)
        }
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `open uses a previously read native link without remote side effect`() = runBlocking {
        assertEquals("CONFLICT", call("agentlink_chat", "open").code)
        call("agentlink_chat", "read")
        assertEquals("ok", call("agentlink_chat", "open").status)
        assertEquals(1, client.calls.size)
        assertEquals("chat-1", client.links.single().chatId)
    }

    @Test
    fun `read without authoritative revision cannot authorize sends`() = runBlocking {
        client.readResult = ToolResultEnvelope.success(buildJsonObject {
            put("chat", buildJsonObject { put("chatId", "chat-1") })
        })
        assertEquals("PROVIDER_ERROR", call("agentlink_chat", "read").code)
        assertEquals("CONFLICT", call("agentlink_control", "send").code)
    }

    @Test
    fun `cancel targets an observed task rather than generating a new operation`() = runBlocking {
        call("agentlink_chat", "read")
        val result = registry.execute("agentlink_control", buildJsonObject {
            put("action", "cancel")
            put("machineId", "machine-1")
            put("chatId", "chat-1")
            put("taskId", "task-1")
        }, context)
        assertEquals("ok", result.status)
        assertEquals("task-1", client.calls.last().operationId)
        assertEquals(null, client.calls.last().expectedHumanRevision)
        assertEquals(null, client.calls.last().taskId)
    }

    @Test
    fun `unknown cancellation target is rejected without side effects`() = runBlocking {
        call("agentlink_chat", "read")
        val result = registry.execute("agentlink_control", buildJsonObject {
            put("action", "cancel")
            put("machineId", "machine-1")
            put("chatId", "chat-1")
            put("taskId", "invented")
        }, context)
        assertEquals("INVALID_ARGS", result.code)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `readiness aggregates AgentLink and requires every switch`() {
        val ready = ToolCatalogSummary(agentLink = client.state)
        assertEquals(AGENTLINK_TOOLS, ready.readyToolNames())
        assertTrue(ready.skillReadiness(AGENTLINK_TOOLS).isReady)
        val partial = ready.copy(agentLink = client.state.copy(enabledTools = setOf("agentlink_chat")))
        assertEquals(setOf("AgentLink"), partial.skillReadiness(AGENTLINK_TOOLS).missingRequirements)
        assertTrue(ready.copy(agentLink = client.state.copy(connected = false)).readyToolNames().isEmpty())
    }

    @Test
    fun `built in skill is opt in and suspended when foreground tools missing`() = runBlocking {
        val entries = mutableMapOf<String, SkillEntity>()
        val repository = RoomSkillRepository(object : SkillDao {
            override suspend fun listAll() = entries.values.toList()
            override suspend fun getById(id: String) = entries[id]
            override suspend fun listEnabled() = entries.values.filter { it.enabled }
            override suspend fun upsert(skill: SkillEntity) { entries[skill.id] = skill }
            override suspend fun delete(skill: SkillEntity) { entries.remove(skill.id) }
        })
        val skill = repository.listSkills().single { it.id == "builtin:agentlink" }
        assertFalse(skill.enabled)
        assertEquals(AGENTLINK_TOOLS, skill.requiredToolNames)
        repository.setEnabled(skill.id, true)
        assertTrue(repository.listEnabledMetadata(AGENTLINK_TOOLS).any { it.name == "agentlink" })
        assertFalse(repository.listEnabledMetadata(emptySet()).any { it.name == "agentlink" })
        assertEquals("PERMISSION_DENIED", LoadSkillTool(repository, emptySet()).execute(
            buildJsonObject { put("skill_name", "agentlink") }, context,
        ).code)
    }

    private suspend fun call(name: String, action: String) = registry.execute(name, buildJsonObject {
        put("action", action)
        put("machineId", "machine-1")
        put("chatId", "chat-1")
        if (action == "send") put("content", "Run requested tests")
    }, context)

    private class FakeClient : AgentLinkClient {
        var state = AgentLinkState(
            installed = true, authorized = true, connected = true, enabled = true, status = "connected",
        )
        val calls = mutableListOf<AgentLinkRequest>()
        val links = mutableListOf<AgentLinkChatLink>()
        var cancelWait = false
        var readResult = ToolResultEnvelope.success(buildJsonObject {
            put("chat", buildJsonObject {
                put("chatId", "chat-1")
                put("chatTitle", "Tests")
                put("humanRevision", 7)
            })
            put("latestEventId", 9)
            put("nextEventId", 5)
            put("tasks", buildJsonArray {
                add(buildJsonObject { put("taskId", "task-1") })
            })
        })
        var writeResult = ToolResultEnvelope.success(buildJsonObject {
            put("taskId", "task-1")
            put("chatId", "chat-1")
            put("state", "queued")
        })
        override suspend fun refresh() = state
        override suspend fun execute(tool: String, request: AgentLinkRequest): ToolResultEnvelope {
            calls += request
            if (cancelWait) throw CancellationException("Local wait stopped")
            return if (request.action == "read") readResult else writeResult
        }
        override suspend fun setEnabled(enabled: Boolean) { state = state.copy(enabled = enabled) }
        override suspend fun setToolEnabled(name: String, enabled: Boolean) = Unit
        override suspend fun beginAuthorization() = AgentLinkActivityRequest("nonce")
        override suspend fun completeAuthorization(requestId: String?, version: Int, accepted: Boolean) = Unit
        override suspend fun revoke() = Unit
        override suspend fun remember(link: AgentLinkChatLink) { links += link }
    }
}
