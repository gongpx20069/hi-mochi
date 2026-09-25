package com.example.mochi_pet.core.extensions

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxRuntimeTest {
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
    fun `enabled commands execute immediately on every call and track tasks`() = runTest {
        val runtime = TermuxRuntimeState()
        val delegate = RecordingTool()
        val tool = TermuxToolSession(runtime) { true }.wrap(delegate)
        repeat(3) { assertEquals("ok", tool.execute(arguments, context).status) }
        assertEquals(3, delegate.calls)
        assertEquals(listOf("test-task"), runtime.tasks.value.map { it.id })
        runtime.record(ToolResultEnvelope.success(buildJsonObject {
            put("task_id", "test-task"); put("state", "forgotten")
        }))
        assertEquals(emptyList<TermuxTaskView>(), runtime.tasks.value)
    }

    @Test
    fun `disabled tools and stale sessions cannot execute after reenabling`() = runTest {
        val runtime = TermuxRuntimeState()
        val delegate = RecordingTool()
        var enabled = false
        val stale = TermuxToolSession(runtime) { enabled }.wrap(delegate)
        assertEquals("PERMISSION_DENIED", stale.execute(arguments, context).code)
        runtime.invalidateSessions()
        enabled = true
        assertEquals("PERMISSION_DENIED", stale.execute(arguments, context).code)
        assertEquals(0, delegate.calls)
        assertEquals("ok", TermuxToolSession(runtime) { enabled }.wrap(delegate).execute(arguments, context).status)
    }

    @Test
    fun `revocation during suspended settings read prevents dispatch`() = runTest {
        val runtime = TermuxRuntimeState()
        val delegate = RecordingTool()
        val checked = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val tool = TermuxToolSession(runtime) {
            checked.complete(Unit)
            resume.await()
            true
        }.wrap(delegate)
        val execution = async { tool.execute(arguments, context) }
        checked.await()
        runtime.invalidateSessions()
        resume.complete(Unit)
        assertEquals("PERMISSION_DENIED", execution.await().code)
        assertEquals(0, delegate.calls)
    }
}
