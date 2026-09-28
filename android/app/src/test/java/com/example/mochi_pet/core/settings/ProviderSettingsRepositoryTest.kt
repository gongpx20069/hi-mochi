package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.mochi_pet.core.agent.llm.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderSettingsRepositoryTest {
    @Test fun `migrates legacy credentials exactly once and keeps settings across recreation`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        store.updateData { it.toMutablePreferences().apply {
            this[stringPreferencesKey("provider.endpoint")] = "https://resource.openai.azure.com"
            this[stringPreferencesKey("provider.model")] = "deployment"
            this[stringPreferencesKey("provider.type")] = "AZURE_OPENAI"
            this[stringPreferencesKey("provider.api_key_ciphertext")] = "fixture-key".reversed()
            this[stringPreferencesKey("provider.api_key_iv")] = "fake-iv"
            this[intPreferencesKey("provider.timeout_seconds")] = 123
            this[booleanPreferencesKey("provider.image_input_enabled")] = false
        } }
        val first = DataStoreProviderSettingsRepository(store, FakeApiKeyCipher())
        val profiles = first.loadProfiles()
        assertEquals(1, profiles.profiles.size)
        assertEquals(ProviderPreset.AZURE, profiles.active!!.preset)
        assertEquals(123, profiles.active!!.settings.timeoutSeconds)
        assertFalse(profiles.active!!.settings.imageInputEnabled)
        assertEquals("fixture-key", first.loadRuntimeConfig().apiKey)
        assertEquals(null, store.data.first()[stringPreferencesKey("provider.api_key_ciphertext")])
        val restored = DataStoreProviderSettingsRepository(store, FakeApiKeyCipher())
        assertEquals(profiles, restored.loadProfiles())
        assertEquals("fixture-key", restored.loadRuntimeConfig().apiKey)
    }

    @Test fun `multiple accounts preserve isolated keys and active deletion never falls back`() = runBlocking {
        val repo = repository(FakeApiKeyCipher())
        val first = repo.saveProfile(profile("First", "one")).active!!
        val second = repo.saveProfile(profile("Second", "two")).profiles.last()
        assertEquals(first.id, repo.loadProfiles().activeId)
        assertEquals("one", repo.loadRuntimeConfig().apiKey)
        repo.activateProfile(second.id)
        assertEquals("two", repo.loadRuntimeConfig().apiKey)
        repo.saveProfile(profile("First edited", null).copy(id = first.id))
        assertEquals("one", repo.loadProfileRuntimeConfig(first.id).apiKey)
        assertEquals("two", repo.loadRuntimeConfig().apiKey)
        repo.deleteProfile(second.id)
        assertEquals(1, repo.loadProfiles().profiles.size)
        assertEquals(null, repo.loadProfiles().activeId)
        assertThrows(ProviderSettingsIncompleteException::class.java) { runBlocking { repo.loadRuntimeConfig() } }
        repo.activateProfile(first.id)
        assertEquals("one", repo.loadRuntimeConfig().apiKey)
    }

    @Test fun `host changes cannot silently reuse keys and corrupt storage is not reset`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repo = DataStoreProviderSettingsRepository(store, FakeApiKeyCipher())
        val first = repo.saveProfile(profile("First", "one")).active!!
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repo.saveProfile(profile("Other host", null).let {
                it.copy(id = first.id, settings = it.settings.copy(endpoint = "https://another.example/v1"))
            })
        } }
        assertEquals("one", repo.loadRuntimeConfig().apiKey)
        store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("provider.profiles.v1")] = "broken" } }
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.loadProfiles() } }
        assertEquals("broken", store.data.first()[stringPreferencesKey("provider.profiles.v1")])
    }

    @Test fun `switch cancels running owners and children while saving another account and renaming do not`() = runBlocking {
        val runs = ProviderRunCoordinator()
        val repo = DataStoreProviderSettingsRepository(InMemoryPreferencesDataStore(), FakeApiKeyCipher(), runs)
        val first = repo.saveProfile(profile("First", "one")).active!!
        val ready = CompletableDeferred<Unit>()
        val childReady = CompletableDeferred<Job>()
        val owner = launch {
            runs.register(coroutineContext[Job]!!)
            launch { childReady.complete(coroutineContext[Job]!!); awaitCancellation() }
            ready.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { ready.await() }
        val child = childReady.await()
        val second = repo.saveProfile(profile("Second", "two")).profiles.last()
        assertTrue(owner.isActive)
        repo.saveProfile(profile("Renamed", null).copy(id = first.id))
        assertTrue(owner.isActive)
        withTimeout(5_000) { repo.activateProfile(second.id) }
        assertTrue(owner.isCancelled)
        assertTrue(child.isCancelled)
        assertEquals("two", repo.loadRuntimeConfig().apiKey)
    }

    @Test fun `new Agent startup waits for in-progress provider change`() = runBlocking {
        val runs = ProviderRunCoordinator()
        val changing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val changed = async { runs.change { changing.complete(Unit); release.await() } }
        changing.await()
        val started = CompletableDeferred<Unit>()
        val agent = launch { runs.register(coroutineContext[Job]!!); started.complete(Unit) }
        assertFalse(started.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { changed.await(); agent.join() }
        assertTrue(started.isCompleted)
    }

    private fun profile(name: String, key: String?) = ProviderProfileInput(
        name = name, preset = ProviderPreset.DEEPSEEK,
        settings = ProviderSettingsInput(endpoint = ProviderPreset.DEEPSEEK.endpoint,
            model = "fixture-model", apiKeyReplacement = key),
    )

    @Test
    fun `multimodal input defaults on and preserves an explicit opt out`() =
        runBlocking {
            val repository = repository(FakeApiKeyCipher())

            assertTrue(repository.loadSummary().imageInputEnabled)

            repository.save(
                ProviderSettingsInput(
                    endpoint = "https://example.test/v1",
                    model = "test-model",
                    imageInputEnabled = false,
                    apiKeyReplacement = "secret",
                ),
            )

            assertFalse(repository.loadSummary().imageInputEnabled)
            assertFalse(repository.loadRuntimeConfig().imageInputEnabled)
        }

    @Test
    fun `blank API key replacement preserves encrypted key`() = runBlocking {
        val cipher = FakeApiKeyCipher()
        val repository = repository(cipher)

        repository.save(
            ProviderSettingsInput(
                providerType = ProviderType.AZURE_OPENAI,
                endpoint = "https://example.test/v1",
                model = "test-model",
                apiVersion = "2024-10-21",
                imageInputEnabled = true,
                apiKeyReplacement = "first-secret",
            ),
        )
        repository.save(
            ProviderSettingsInput(
                providerType = ProviderType.AZURE_OPENAI,
                endpoint = "https://example.test/v1",
                model = "updated-model",
                apiVersion = "2025-01-01-preview",
                imageInputEnabled = true,
                apiKeyReplacement = "   ",
            ),
        )

        val summary = repository.loadSummary()
        val runtime = repository.loadRuntimeConfig()
        assertTrue(summary.hasApiKey)
        assertEquals(ProviderType.AZURE_OPENAI, runtime.providerType)
        assertEquals("updated-model", runtime.model)
        assertEquals("2025-01-01-preview", runtime.apiVersion)
        assertEquals("first-secret", runtime.apiKey)
        assertTrue(summary.imageInputEnabled)
        assertTrue(runtime.imageInputEnabled)
    }

    @Test(expected = ProviderSettingsIncompleteException::class)
    fun `runtime config rejects incomplete settings`() {
        runBlocking {
            repository(FakeApiKeyCipher()).loadRuntimeConfig()
        }
    }

    private fun repository(
        cipher: ApiKeyCipher,
    ): DataStoreProviderSettingsRepository =
        DataStoreProviderSettingsRepository(
            dataStore = InMemoryPreferencesDataStore(),
            apiKeyCipher = cipher,
        )
}

private class FakeApiKeyCipher : ApiKeyCipher {
    override fun encrypt(plaintext: String): EncryptedSecret =
        EncryptedSecret(
            ciphertext = plaintext.reversed(),
            iv = "fake-iv",
        )

    override fun decrypt(secret: EncryptedSecret): String {
        require(secret.iv == "fake-iv")
        return secret.ciphertext.reversed()
    }
}

private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
