package com.example.mochi_pet.core.extensions

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TermuxApprovalTest {
    private val context = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
    private val arguments = buildJsonObject { put("command", "printf hello") }
    private class RecordingTool : AgentTool {
        override val name = "termux_exec"
        override val schema = JsonObject(emptyMap())
        var calls = 0
        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope {
            calls++
            return ToolResultEnvelope.success(buildJsonObject { put("task_id", "test-task"); put("state", "submitted") })
        }
    }

    @Test
    fun `approval is native nonce bound and one shot`() = runTest {
        val gate = TermuxApprovalGate()
        val delegate = RecordingTool()
        val tool = TermuxToolSession(gate) { true }.wrap(delegate)
        val pending = async { tool.execute(arguments, context) }
        runCurrent()
        gate.respond("stale-id", TermuxApprovalChoice.THIS_RUN)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(0, delegate.calls)
        val id = gate.pending.value!!.id
        gate.respond(id, TermuxApprovalChoice.ONCE)
        assertEquals("ok", pending.await().status)
        assertNull(gate.pending.value)
        val second = async { tool.execute(arguments, context) }
        runCurrent()
        gate.respond(id, TermuxApprovalChoice.ONCE)
        runCurrent()
        assertFalse(second.isCompleted)
        gate.respond(gate.pending.value!!.id, TermuxApprovalChoice.DENY)
        assertEquals("PERMISSION_DENIED", second.await().code)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `task wide approval does not carry into another registry or survive revocation`() = runTest {
        val gate = TermuxApprovalGate()
        val delegate = RecordingTool()
        val tool = TermuxToolSession(gate) { true }.wrap(delegate)
        val first = async { tool.execute(arguments, context) }
        runCurrent()
        gate.respond(gate.pending.value!!.id, TermuxApprovalChoice.THIS_RUN)
        first.await()
        assertEquals("ok", tool.execute(arguments, context).status)
        gate.cancelPending()
        assertEquals("PERMISSION_DENIED", tool.execute(arguments, context).code)
        val next = TermuxToolSession(gate) { true }.wrap(delegate)
        val pending = async { next.execute(arguments, context) }
        runCurrent()
        assertFalse(pending.isCompleted)
        pending.cancel()
        runCurrent()
        assertNull(gate.pending.value)
        assertEquals(2, delegate.calls)
    }

    @Test
    fun `disable while awaiting approval and expired approval cannot execute`() = runTest {
        val gate = TermuxApprovalGate()
        var enabled = true
        val delegate = RecordingTool()
        val tool = TermuxToolSession(gate) { enabled }.wrap(delegate)
        val pending = async { tool.execute(arguments, context) }
        runCurrent()
        enabled = false
        gate.respond(gate.pending.value!!.id, TermuxApprovalChoice.ONCE)
        assertEquals("PERMISSION_DENIED", pending.await().code)
        enabled = true
        assertEquals("PERMISSION_DENIED", tool.execute(arguments, context).code)
        assertEquals(0, delegate.calls)
    }
}
