package com.example.mochi_pet.core.tasks

import com.example.mochi_pet.core.agent.AgentDiagnosticEvent
import com.example.mochi_pet.core.agent.AgentDiagnosticEventType
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, STOPPING, UNKNOWN }

data class AgentTaskView(
    val id: String,
    val actor: String,
    val scheduleId: String?,
    val title: String,
    val status: TaskStatus,
    val updatedAt: Instant,
    val tool: String? = null,
    val errorType: String? = null,
) {
    val active: Boolean get() = status == TaskStatus.RUNNING
}

/** A bounded process-local view of runtime events, never a second execution queue. */
class AgentTaskRuntime(private val clock: Clock = Clock.systemUTC()) {
    private val mutableTasks = MutableStateFlow<List<AgentTaskView>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    private val owners = mutableMapOf<String, Job>()

    @Synchronized
    fun record(event: AgentDiagnosticEvent, scheduleId: String?, title: String, owner: Job) {
        val previous = mutableTasks.value.firstOrNull { it.id == event.runId }
        if (previous == null && event.type != AgentDiagnosticEventType.RUN_STARTED) return
        if (previous != null && !previous.active) return
        val status = when (event.type) {
            AgentDiagnosticEventType.RUN_COMPLETED -> TaskStatus.SUCCEEDED
            AgentDiagnosticEventType.RUN_FAILED -> TaskStatus.FAILED
            AgentDiagnosticEventType.RUN_CANCELLED -> TaskStatus.CANCELLED
            else -> TaskStatus.RUNNING
        }
        val task = AgentTaskView(
            id = event.runId,
            actor = event.actor,
            scheduleId = scheduleId,
            title = title.take(120),
            status = status,
            updatedAt = clock.instant(),
            tool = if (event.type == AgentDiagnosticEventType.TOOL_STARTED) event.toolName else null,
            errorType = event.errorType,
        )
        if (task.active) owners[event.runId] = owner else owners.remove(event.runId)
        val all = listOf(task) + mutableTasks.value.filterNot { it.id == task.id }
        mutableTasks.value = all.filter { it.active } + all.filterNot { it.active }.take(100)
    }

    @Synchronized
    fun stopForeground(id: String) {
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return
        if (task.active && task.scheduleId == null) owners[id]?.cancel()
    }
}
