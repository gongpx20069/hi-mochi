package com.example.mochi_pet.core.tasks

import com.example.mochi_pet.core.agent.AgentDiagnosticEvent
import com.example.mochi_pet.core.agent.AgentDiagnosticEventType
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskRuntimeTest {
    @Test fun `stopping a child cancels its exact foreground owner but not another run`() {
        val runtime = AgentTaskRuntime()
        val owner = Job()
        val other = Job()
        runtime.record(event("child", AgentDiagnosticEventType.RUN_STARTED, "researcher"), null, "Researcher", owner)
        runtime.record(event("other", AgentDiagnosticEventType.RUN_STARTED), null, "Conversation", other)
        runtime.stopForeground("child")
        assertTrue(owner.isCancelled)
        assertTrue(other.isActive)
        other.cancel()
    }

    @Test fun `completed and scheduled rows cannot cancel a foreground interaction`() {
        val runtime = AgentTaskRuntime()
        val owner = Job()
        runtime.record(event("done", AgentDiagnosticEventType.RUN_STARTED), null, "Conversation", owner)
        runtime.record(event("done", AgentDiagnosticEventType.RUN_COMPLETED), null, "Conversation", owner)
        runtime.record(event("scheduled", AgentDiagnosticEventType.RUN_STARTED), "schedule", "Brief", owner)
        runtime.stopForeground("done")
        runtime.stopForeground("scheduled")
        assertFalse(owner.isCancelled)
        runtime.record(event("done", AgentDiagnosticEventType.TOOL_STARTED), null, "Conversation", owner)
        assertEquals(TaskStatus.SUCCEEDED, runtime.tasks.value.first { it.id == "done" }.status)
        owner.cancel()
    }

    @Test fun `terminal history is bounded without evicting active runs`() {
        val runtime = AgentTaskRuntime()
        val owner = Job()
        runtime.record(event("active", AgentDiagnosticEventType.RUN_STARTED), null, "Conversation", owner)
        repeat(110) {
            runtime.record(event("$it", AgentDiagnosticEventType.RUN_STARTED), null, "Conversation", owner)
            runtime.record(event("$it", AgentDiagnosticEventType.RUN_FAILED), null, "Conversation", owner)
        }
        assertEquals(101, runtime.tasks.value.size)
        assertTrue(runtime.tasks.value.first { it.id == "active" }.active)
        assertEquals(100, runtime.tasks.value.count { it.status == TaskStatus.FAILED })
        owner.cancel()
    }

    private fun event(id: String, type: AgentDiagnosticEventType, actor: String = "main") =
        AgentDiagnosticEvent(type, id, actor)
}
