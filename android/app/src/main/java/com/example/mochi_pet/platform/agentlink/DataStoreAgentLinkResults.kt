package com.example.mochi_pet.platform.agentlink

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.agentlink.AgentLinkReceipt
import com.example.mochi_pet.core.agentlink.AgentLinkResultRepository
import com.example.mochi_pet.core.agentlink.AgentLinkResultState
import com.example.mochi_pet.core.agentlink.AgentLinkResultVersion
import com.example.mochi_pet.core.agentlink.FollowedAgentLinkTask
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DataStoreAgentLinkResults(
    private val store: DataStore<Preferences>,
    private val cipher: ApiKeyCipher,
) : AgentLinkResultRepository {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AgentLinkResultState())
    override val state = mutableState.asStateFlow()
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        val saved = store.data.first()[RECORDS]
        val tasks = saved?.let {
            val envelope = json.decodeFromString<SealedResults>(it)
            json.decodeFromString<List<FollowedAgentLinkTask>>(
                cipher.decrypt(EncryptedSecret(envelope.ciphertext, envelope.iv)),
            )
        }.orEmpty()
        mutableState.update { it.copy(tasks = tasks) }
        loaded = true
    }

    override suspend fun follow(task: FollowedAgentLinkTask) = modify { tasks ->
        if (tasks.any { it.key == task.key }) tasks
        else {
            val retained = if (tasks.size < 100) tasks else {
                val removable = tasks.lastOrNull { it.receipt?.terminal == true && !it.unread && !it.pendingAnnouncement }
                checkNotNull(removable) { "Task history is full. Review and dismiss remote results before submitting more work." }
                tasks.filterNot { it.key == removable.key }
            }
            listOf(task) + retained
        }
    }

    override suspend fun receive(key: String, receipt: AgentLinkReceipt) = modify { tasks ->
        tasks.map { task ->
            if (task.key != key) task else {
                check(task.chatId == receipt.chatId && task.taskId == receipt.taskId && receipt.source == "mochi")
                val previous = task.receipt
                if (previous != null && receipt.revision < previous.revision) task
                else task.copy(receipt = receipt)
            }
        }
    }

    override suspend fun acknowledge(
        versions: List<AgentLinkResultVersion>,
        viewed: Boolean,
        announced: Boolean,
    ) = modify { tasks ->
        val targets = versions.associate { it.key to it.revision }
        tasks.map { task ->
            val revision = targets[task.key]
            if (revision == null || task.receipt?.revision != revision) task else task.copy(
                viewedRevision = if (viewed) maxOf(task.viewedRevision, revision) else task.viewedRevision,
                announcedRevision = if (announced) maxOf(task.announcedRevision, revision) else task.announcedRevision,
            )
        }
    }

    override suspend fun clear() = mutex.withLock {
        store.edit { it.remove(RECORDS) }
        loaded = true
        mutableState.value = AgentLinkResultState()
    }

    override fun availability(allowed: Boolean, error: String?, readableMachineIds: Set<String>) {
        mutableState.update { it.copy(canAnnounce = allowed, error = error, readableMachineIds = readableMachineIds) }
    }

    private suspend fun modify(transform: (List<FollowedAgentLinkTask>) -> List<FollowedAgentLinkTask>) = mutex.withLock {
        loadLocked()
        val current = mutableState.value.tasks
        val next = transform(current)
        if (next == current) return@withLock
        val encrypted = cipher.encrypt(json.encodeToString(next))
        store.edit { it[RECORDS] = json.encodeToString(SealedResults(encrypted.ciphertext, encrypted.iv)) }
        mutableState.update { it.copy(tasks = next) }
    }

    @Serializable
    private data class SealedResults(val ciphertext: String, val iv: String)
    private companion object {
        val RECORDS = stringPreferencesKey("encrypted_records")
    }
}
