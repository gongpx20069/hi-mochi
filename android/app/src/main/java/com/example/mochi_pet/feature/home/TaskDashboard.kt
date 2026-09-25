package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.agentlink.AgentLinkChatLink
import com.example.mochi_pet.core.extensions.TermuxTaskView
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleResult
import com.example.mochi_pet.core.schedule.AgentScheduleType
import com.example.mochi_pet.core.tasks.AgentTaskView
import com.example.mochi_pet.core.tasks.TaskStatus
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class TaskFilter(val label: String) {
    ALL("All tasks"), ACTIVE("In progress"), ATTENTION("Needs attention"), PLANNED("Schedules"), HISTORY("Finished"),
}

internal sealed interface TaskReference {
    data class Agent(val value: AgentTaskView) : TaskReference
    data class Schedule(val value: AgentSchedule) : TaskReference
    data class Shell(val value: TermuxTaskView) : TaskReference
    data class Remote(val link: AgentLinkChatLink, val taskId: String?) : TaskReference
}

internal data class DashboardTask(
    val key: String,
    val title: String,
    val source: String,
    val status: TaskStatus?,
    val statusLabel: String,
    val reference: TaskReference,
    val time: Instant? = null,
    val attention: Boolean = status == TaskStatus.FAILED,
) {
    val active: Boolean get() = status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.STOPPING)
    fun matches(filter: TaskFilter): Boolean = when (filter) {
        TaskFilter.ALL -> true
        TaskFilter.ACTIVE -> active
        TaskFilter.ATTENTION -> attention
        TaskFilter.PLANNED -> reference is TaskReference.Schedule
        TaskFilter.HISTORY -> status in setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED)
    }
}

internal fun taskDashboard(
    state: TaskCenterUiState,
    agents: List<AgentTaskView>,
    termux: List<TermuxTaskView>,
): List<DashboardTask> = buildList {
    agents.forEach { task ->
        val mergedSchedule = task.active && task.actor == "main" &&
            state.schedules.any { it.id == task.scheduleId }
        if (!mergedSchedule) add(DashboardTask(
            "agent:${task.id}", task.title,
            if (task.actor != "main") "Subagent" else if (task.scheduleId != null) "Scheduled Agent" else "Mochi Agent",
            task.status, task.status.label(), TaskReference.Agent(task), task.updatedAt,
        ))
    }
    state.schedules.forEach { schedule ->
        val running = agents.any { it.active && it.actor == "main" && it.scheduleId == schedule.id }
        val status = if (running) TaskStatus.RUNNING else state.scheduleStatuses[schedule.id]
        add(DashboardTask(
            "schedule:${schedule.id}", schedule.name, "Scheduled Agent", status,
            status?.label() ?: when {
                schedule.enabled -> "Scheduled"
                schedule.type == AgentScheduleType.ONCE && schedule.lastResult != null -> "Schedule ended"
                else -> "Paused"
            }, TaskReference.Schedule(schedule),
            schedule.nextRunAt, attention = !running && schedule.lastResult == AgentScheduleResult.FAILED,
        ))
    }
    termux.forEach { task ->
        val status = termuxTaskStatus(task)
        add(DashboardTask("shell:${task.id}", "Shell task", "Termux", status, status.label(),
            TaskReference.Shell(task), attention = status == TaskStatus.FAILED || status == TaskStatus.UNKNOWN))
    }
    state.agentLink.links.forEach { link ->
        val prefix = "remote:${link.machineId.length}:${link.machineId}:${link.chatId.length}:${link.chatId}"
        val snapshots = state.remoteTasks.filter { it.link.machineId == link.machineId && it.link.chatId == link.chatId }
        if (snapshots.isEmpty()) {
            add(DashboardTask(prefix, link.title, "AgentLink", null,
                if ((link.machineId to link.chatId) in state.checkedChats) "Linked chat" else "Remote status not checked",
                TaskReference.Remote(link, null), state.remoteCheckedAt, attention = link.outcomeUnknown))
        } else snapshots.forEach { task ->
            add(DashboardTask("$prefix:${task.id}", link.title, "AgentLink", task.status, task.status.label(),
                TaskReference.Remote(link, task.id), state.remoteCheckedAt,
                attention = link.outcomeUnknown || task.status == TaskStatus.FAILED))
        }
    }
}.sortedWith(compareByDescending<DashboardTask> { it.active }.thenByDescending { it.attention })

internal fun termuxTaskStatus(task: TermuxTaskView): TaskStatus =
    when (((task.result.data as? JsonObject)?.get("state") as? JsonPrimitive)?.content) {
        "submitted" -> TaskStatus.QUEUED
        "running" -> TaskStatus.RUNNING
        "stopping" -> TaskStatus.STOPPING
        "succeeded" -> TaskStatus.SUCCEEDED
        "failed", "timed_out" -> TaskStatus.FAILED
        "stopped", "cancelled" -> TaskStatus.CANCELLED
        else -> TaskStatus.UNKNOWN
    }

internal fun TaskStatus.label(): String = when (this) {
    TaskStatus.QUEUED -> "Queued"
    TaskStatus.RUNNING -> "Running"
    TaskStatus.SUCCEEDED -> "Completed"
    TaskStatus.FAILED -> "Failed"
    TaskStatus.CANCELLED -> "Cancelled"
    TaskStatus.STOPPING -> "Stop requested"
    TaskStatus.UNKNOWN -> "State unknown"
}
