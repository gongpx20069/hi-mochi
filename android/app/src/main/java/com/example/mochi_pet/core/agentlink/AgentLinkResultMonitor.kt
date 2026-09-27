package com.example.mochi_pet.core.agentlink

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

class AgentLinkResultMonitor(
    private val client: AgentLinkClient,
    private val repository: AgentLinkResultRepository,
) {
    private val mutex = Mutex()
    private var nextIndex = 0

    suspend fun refresh(): Boolean = mutex.withLock {
        try {
            repository.load()
            if (repository.state.value.tasks.isEmpty()) return@withLock true
            if (!client.taskFollowUpEnabled()) {
                repository.availability(false, "Remote follow-up is paused. Check AgentLink access in Tools.")
                return@withLock true
            }
            val access = client.refresh()
            val allowed = access.authorized && access.connected && access.enabled && "agentlink_chat" in access.enabledTools &&
                access.readableMachineIds.isNotEmpty()
            repository.availability(allowed, if (!allowed) "Remote follow-up is paused. Check AgentLink access in Tools." else null,
                access.readableMachineIds)
            if (!allowed) return@withLock true
            val pending = repository.state.value.tasks.filter { it.receipt?.terminal != true && it.machineId in access.readableMachineIds }
            if (pending.isEmpty()) return@withLock true
            val startIndex = nextIndex % pending.size
            val batch = List(minOf(4, pending.size)) { pending[(startIndex + it) % pending.size] }
            var failure: String? = null
            for ((index, task) in batch.withIndex()) {
                nextIndex = (startIndex + index + 1) % pending.size
                val response = client.execute("agentlink_chat", AgentLinkRequest(
                    action = "task", machineId = task.machineId, chatId = task.chatId, taskId = task.taskId,
                ))
                if (response.status != "ok") {
                    failure = when (response.code) {
                        "INVALID_ARGS" -> "Remote follow-up needs an updated AgentLink app and Bridge."
                        "PERMISSION_DENIED" -> "Remote follow-up is paused. Check AgentLink access in Tools."
                        "NOT_FOUND" -> "Remote task not found. Its outcome is unknown; do not resubmit automatically."
                        else -> "Remote follow-up is unavailable. Saved results remain; reconnect to collect updates."
                    }
                    repository.availability(response.code == "NOT_FOUND", failure, access.readableMachineIds)
                    if (response.code != "NOT_FOUND") return@withLock false
                    continue
                }
                repository.receive(task.key, parseAgentLinkReceipt(
                    response.data as? JsonObject ?: error("Missing task response"), task,
                ))
            }
            if (failure != null) repository.availability(true, failure, access.readableMachineIds)
            failure == null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            repository.availability(false, "Remote results could not be read or saved. Check AgentLink and retry.")
            false
        }
    }
}
