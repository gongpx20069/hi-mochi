package com.example.mochi_pet.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agentlink.AgentLinkState
import com.example.mochi_pet.core.diagnostics.ConfigurationCheck
import com.example.mochi_pet.core.diagnostics.runConfigurationChecks
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.schedule.AgentSchedule
import com.example.mochi_pet.core.schedule.AgentScheduleType
import com.example.mochi_pet.core.schedule.toDraft
import com.example.mochi_pet.core.tasks.AgentTaskView
import com.example.mochi_pet.core.tasks.AgentLinkTaskSnapshot
import com.example.mochi_pet.core.tasks.readAgentLinkTasks
import com.example.mochi_pet.core.tasks.TaskStatus
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TaskCenterUiState(
    val visible: Boolean = false,
    val diagnosticsPage: Boolean = false,
    val diagnosticsStandalone: Boolean = false,
    val refreshing: Boolean = false,
    val refreshedAt: Instant? = null,
    val schedules: List<AgentSchedule> = emptyList(),
    val scheduleStatuses: Map<String, TaskStatus> = emptyMap(),
    val agentLink: AgentLinkState = AgentLinkState(),
    val remoteTasks: List<AgentLinkTaskSnapshot> = emptyList(),
    val remoteCheckedAt: Instant? = null,
    val checkedChats: Set<Pair<String, String>> = emptySet(),
    val workingTermuxIds: Set<String> = emptySet(),
    val workingScheduleIds: Set<String> = emptySet(),
    val errors: List<String> = emptyList(),
    val checks: List<ConfigurationCheck> = emptyList(),
    val checking: Boolean = false,
    val checkingTitle: String? = null,
    val checkedAt: Instant? = null,
    val checkCancelled: Boolean = false,
)

class TaskCenterViewModel(private val app: MochiApplication) : ViewModel() {
    private val mutableState = MutableStateFlow(TaskCenterUiState())
    val state = mutableState.asStateFlow()
    val agentTasks = app.agentTaskRuntime.tasks
    val termuxTasks = app.termuxRuntime.tasks
    private var refreshJob: Job? = null
    private var checkJob: Job? = null
    @Volatile private var checkVersion = 0L
    @Volatile private var refreshVersion = 0L
    private val termuxMutex = Mutex()

    fun open(diagnostics: Boolean = false) {
        mutableState.update { it.copy(visible = true, diagnosticsPage = diagnostics, diagnosticsStandalone = diagnostics) }
        if (!diagnostics) refresh()
    }

    fun showDiagnostics(value: Boolean) {
        if (!value) cancelChecks()
        mutableState.update { it.copy(diagnosticsPage = value, diagnosticsStandalone = false) }
    }

    fun close() {
        refreshVersion += 1
        refreshJob?.cancel()
        cancelChecks()
        mutableState.update { it.copy(visible = false, refreshing = false) }
    }

    fun refresh(includeRemote: Boolean = true) {
        if (refreshJob?.isActive == true || mutableState.value.workingTermuxIds.isNotEmpty() ||
            mutableState.value.workingScheduleIds.isNotEmpty()
        ) return
        val version = ++refreshVersion
        mutableState.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                checkSource("Could not refresh schedules. Reopen the task center and try again.", version) {
                    val schedules = app.agentScheduleStore.list()
                    val statuses = app.agentScheduleController.taskStatuses(schedules.map { it.id })
                    updateRefresh(version) { it.copy(schedules = schedules, scheduleStatuses = statuses) }
                }
                if (includeRemote) checkSource("Could not refresh AgentLink. Open Tools to check its connection.", version) {
                    val link = app.agentLinkClient.refresh()
                    updateRefresh(version) { it.copy(
                        agentLink = link, remoteTasks = emptyList(), remoteCheckedAt = null, checkedChats = emptySet(),
                    ) }
                    if (link.connected && link.authorized && link.enabled && "agentlink_chat" in link.enabledTools) {
                        for (chat in link.links) {
                            val tasks = readAgentLinkTasks(app.agentLinkClient, chat,
                                ToolExecutionContext(LocalDate.now(), MochiSurface.Tools))
                            updateRefresh(version) { it.copy(
                                remoteTasks = it.remoteTasks + tasks,
                                checkedChats = it.checkedChats + (chat.machineId to chat.chatId),
                            ) }
                        }
                        updateRefresh(version) { it.copy(remoteCheckedAt = Instant.now()) }
                    }
                }
                checkSource("Could not refresh Termux tasks. Check the connection in Tools.", version) {
                    termuxMutex.withLock {
                        val snapshot = app.termuxClient.snapshot()
                        if (snapshot.connected) {
                            val definition = snapshot.tools.first { it.name == "termux_task" }
                            val tool = app.termuxClient.agentTool(definition)
                            val context = ToolExecutionContext(LocalDate.now(), MochiSurface.Tools)
                            val listed = tool.execute(buildJsonObject { put("action", "list") }, context)
                            check(listed.status == "ok")
                            val ids = (listed.data as? JsonObject)?.get("task_ids") as? JsonArray
                                ?: error("Missing task list")
                            for (element in ids.take(100)) {
                                val id = (element as? JsonPrimitive)?.content ?: error("Invalid task ID")
                                val existing = termuxTasks.value.firstOrNull { it.id == id }
                                if (existing == null || termuxTaskStatus(existing) in setOf(
                                        TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.STOPPING, TaskStatus.UNKNOWN,
                                    )
                                ) {
                                    val result = tool.execute(buildJsonObject {
                                        put("action", "read")
                                        put("task_id", id)
                                    }, context)
                                    check(result.status == "ok")
                                    app.termuxRuntime.record(result)
                                }
                            }
                        } else if (termuxTasks.value.isNotEmpty()) {
                            error("Termux is disconnected")
                        }
                    }
                }
                updateRefresh(version) {
                    if (it.errors.isEmpty()) it.copy(refreshedAt = Instant.now()) else it
                }
            } finally {
                updateRefresh(version) { it.copy(refreshing = false) }
            }
        }
    }

    fun termuxAction(id: String, action: String) {
        require(action in setOf("read", "stop", "forget"))
        if (id in mutableState.value.workingTermuxIds) return
        refreshVersion += 1
        refreshJob?.cancel()
        mutableState.update { it.copy(refreshing = false, workingTermuxIds = it.workingTermuxIds + id) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                checkSource("Termux task action failed. Refresh its state and check the connection.") {
                    termuxMutex.withLock {
                        val snapshot = app.termuxClient.snapshot()
                        check(snapshot.connected)
                        val tool = app.termuxClient.agentTool(snapshot.tools.first { it.name == "termux_task" })
                        val result = tool.execute(buildJsonObject {
                            put("action", action)
                            put("task_id", id)
                        }, ToolExecutionContext(LocalDate.now(), MochiSurface.Tools))
                        check(result.status == "ok")
                        app.termuxRuntime.record(result)
                    }
                }
            } finally {
                mutableState.update { it.copy(workingTermuxIds = it.workingTermuxIds - id) }
            }
        }
    }

    fun stopAgent(task: AgentTaskView) {
        if (task.scheduleId != null) stopSchedule(task.scheduleId)
        else app.agentTaskRuntime.stopForeground(task.id)
    }

    fun stopSchedule(id: String) {
        scheduleAction(id, "Could not stop the scheduled run. Refresh its state before trying again.") {
            app.agentScheduleController.stopRun(id)
        }
    }

    fun runSchedule(id: String) {
        scheduleAction(id, "Could not start this schedule. Check configuration and refresh before retrying.") {
            check(app.agentScheduleStore.get(id) != null)
            app.agentScheduleController.runNow(id)
        }
    }

    fun setScheduleEnabled(id: String, enabled: Boolean) {
        scheduleAction(id, "Could not update the schedule. Check its date and refresh before retrying.") {
            val existing = requireNotNull(app.agentScheduleStore.get(id))
            check(!enabled || existing.type != AgentScheduleType.ONCE || existing.runAt?.isAfter(Instant.now()) == true)
            app.agentScheduleController.sync(app.agentScheduleStore.set(id, existing.toDraft(enabled)))
        }
    }

    private fun scheduleAction(id: String, errorMessage: String, action: suspend () -> Unit) {
        if (id in mutableState.value.workingScheduleIds) return
        refreshVersion += 1
        refreshJob?.cancel()
        mutableState.update { it.copy(refreshing = false, workingScheduleIds = it.workingScheduleIds + id) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                checkSource(errorMessage) {
                    action()
                    val schedules = app.agentScheduleStore.list()
                    val statuses = app.agentScheduleController.taskStatuses(schedules.map { it.id })
                    mutableState.update { it.copy(schedules = schedules, scheduleStatuses = statuses) }
                }
            } finally {
                mutableState.update { it.copy(workingScheduleIds = it.workingScheduleIds - id) }
            }
        }
    }

    fun checkConfiguration() {
        if (checkJob?.isActive == true) return
        val version = ++checkVersion
        mutableState.update { it.copy(checking = true, checks = emptyList(), checkedAt = null, checkCancelled = false) }
        checkJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runConfigurationChecks(
                    probes = configurationProbes(app),
                    errorMessage = { error ->
                        conversationErrorMessage(error).takeUnless { it == "Mochi could not complete this request" }
                            ?: "Check failed. Open the related settings, verify saved configuration and retry."
                    },
                    onProgress = { title ->
                        updateCheck(version) { it.copy(checkingTitle = title) }
                    },
                    onResult = { checks ->
                        updateCheck(version) { it.copy(checks = it.checks + checks) }
                    },
                )
                updateCheck(version) { it.copy(checkedAt = Instant.now()) }
            } finally {
                updateCheck(version) { it.copy(checking = false, checkingTitle = null) }
            }
        }
    }

    fun cancelChecks() {
        if (checkJob?.isActive == true) {
            checkVersion += 1
            checkJob?.cancel()
            mutableState.update { it.copy(checkCancelled = true, checking = false, checkingTitle = null) }
        }
    }

    private fun updateCheck(version: Long, transform: (TaskCenterUiState) -> TaskCenterUiState) {
        mutableState.update { if (version == checkVersion) transform(it) else it }
    }

    private fun updateRefresh(version: Long?, transform: (TaskCenterUiState) -> TaskCenterUiState) {
        mutableState.update { if (version == null || version == refreshVersion) transform(it) else it }
    }

    private suspend fun checkSource(message: String, version: Long? = null, block: suspend () -> Unit) {
        try {
            check(withTimeoutOrNull(20_000) { block(); true } == true)
            updateRefresh(version) { it.copy(errors = it.errors - message) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            updateRefresh(version) { it.copy(errors = (it.errors + message).distinct()) }
        }
    }
}
