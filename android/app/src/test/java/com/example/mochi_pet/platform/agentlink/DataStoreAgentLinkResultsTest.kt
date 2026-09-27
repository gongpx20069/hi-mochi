package com.example.mochi_pet.platform.agentlink

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.mochi_pet.core.agentlink.AgentLinkResultVersion
import com.example.mochi_pet.core.agentlink.followed
import com.example.mochi_pet.core.agentlink.receipt
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DataStoreAgentLinkResultsTest {
    @get:Rule val directory = TemporaryFolder()
    private val cipher = object : ApiKeyCipher {
        override fun encrypt(plaintext: String) = EncryptedSecret(plaintext.reversed(), "test")
        override fun decrypt(secret: EncryptedSecret) = secret.ciphertext.reversed()
    }

    @Test fun `receipts and separate acknowledgements survive recreation without plaintext`() = runBlocking {
        val file = File(directory.root, "results.preferences_pb")
        val task = followed()
        var job = SupervisorJob()
        var repository = DataStoreAgentLinkResults(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }, cipher,
        )
        repository.follow(task)
        repository.receive(task.key, receipt())
        repository.acknowledge(listOf(AgentLinkResultVersion(task.key, 1)), viewed = true, announced = false)
        assertFalse(repository.state.value.tasks.single().unread)
        assertTrue(repository.state.value.tasks.single().pendingAnnouncement)
        assertFalse(file.readText().contains("Remote answer"))
        job.cancelAndJoin()
        job = SupervisorJob()
        repository = DataStoreAgentLinkResults(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }, cipher,
        )
        try {
            repository.load()
            assertFalse(repository.state.value.tasks.single().unread)
            assertTrue(repository.state.value.tasks.single().pendingAnnouncement)
            repository.receive(task.key, receipt().copy(revision = 2, state = "failed"))
            repository.acknowledge(listOf(AgentLinkResultVersion(task.key, 1)), viewed = true, announced = true)
            assertTrue(repository.state.value.tasks.single().unread)
            repository.receive(task.key, receipt())
            assertEquals(2, repository.state.value.tasks.single().receipt!!.revision)
            repository.acknowledge(listOf(AgentLinkResultVersion(task.key, 2)), viewed = true, announced = true)
            assertFalse(repository.state.value.tasks.single().pendingAnnouncement)
            repository.clear()
            repository.receive(task.key, receipt().copy(revision = 3))
            assertTrue(repository.state.value.tasks.isEmpty())
        } finally {
            job.cancelAndJoin()
        }
    }
}
