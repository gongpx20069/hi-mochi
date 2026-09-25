package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agentlink.AgentLinkChatLink
import com.example.mochi_pet.core.agentlink.AgentLinkState
import com.example.mochi_pet.core.extensions.TermuxTaskView
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleType
import com.example.mochi_pet.core.tasks.AgentTaskView
import com.example.mochi_pet.core.tasks.TaskStatus
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDashboardTest {
    @Test fun `active schedule is one card rather than duplicate run and schedule cards`() {
        val schedule = AgentSchedule(
            "schedule", "Morning briefing", "Brief me", AgentScheduleType.EVERY, null, null, emptySet(), 60,
            ZoneOffset.UTC, true, null, null, Instant.EPOCH, Instant.EPOCH,
        )
        val rows = taskDashboard(
            TaskCenterUiState(schedules = listOf(schedule)),
            listOf(AgentTaskView("run", "main", schedule.id, schedule.name, TaskStatus.RUNNING, Instant.EPOCH)),
            emptyList(),
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single().reference is TaskReference.Schedule)
        assertTrue(rows.single().matches(TaskFilter.ACTIVE))
        assertTrue(rows.single().matches(TaskFilter.PLANNED))
        assertFalse(rows.single().matches(TaskFilter.HISTORY))
    }

    @Test fun `running work precedes failures and finished tasks without guessing remote status`() {
        val rows = taskDashboard(
            TaskCenterUiState(agentLink = AgentLinkState(
                links = listOf(AgentLinkChatLink("machine", "chat", "Remote work", outcomeUnknown = true)),
            )),
            listOf(AgentTaskView("done", "main", null, "Conversation", TaskStatus.SUCCEEDED, Instant.EPOCH)),
            listOf(shell("failed", "failed"), shell("running", "running")),
        )
        assertEquals("shell:running", rows.first().key)
        assertEquals(2, rows.count { it.matches(TaskFilter.ATTENTION) })
        assertEquals(2, rows.count { it.matches(TaskFilter.HISTORY) })
        val remote = rows.single { it.reference is TaskReference.Remote }
        assertFalse(remote.active)
        assertEquals(null, remote.status)
        assertTrue(remote.attention)
    }

    private fun shell(id: String, state: String) = TermuxTaskView(
        id, ToolResultEnvelope.success(buildJsonObject { put("task_id", id); put("state", state) }),
    )
}
