package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.mochi_pet.core.agent.llm.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingsRepositoryTest {
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
