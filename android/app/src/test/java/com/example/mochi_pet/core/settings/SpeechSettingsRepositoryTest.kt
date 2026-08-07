package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSettingsRepositoryTest {
    @Test
    fun `system recognition is the ready default`() = runBlocking {
        val repository = repository()

        assertEquals(
            SpeechSettingsSummary(),
            repository.loadSummary(),
        )
        assertEquals(
            SpeechRuntimeConfig.System,
            repository.loadRuntimeConfig(),
        )
    }

    @Test
    fun `blank iFlytek replacements preserve stored secrets`() = runBlocking {
        val repository = repository()
        repository.save(
            SpeechSettingsInput(
                provider = SpeechProvider.IFLYTEK,
                iFlytekAppId = "app-id",
                iFlytekApiKeyReplacement = "api-key",
                iFlytekApiSecretReplacement = "api-secret",
            ),
        )

        val summary = repository.save(
            SpeechSettingsInput(
                provider = SpeechProvider.IFLYTEK,
                iFlytekAppId = "updated-app-id",
                iFlytekApiKeyReplacement = " ",
                iFlytekApiSecretReplacement = "",
            ),
        )
        val runtime =
            repository.loadRuntimeConfig() as SpeechRuntimeConfig.IFlytek

        assertTrue(summary.isReady)
        assertEquals("updated-app-id", runtime.appId)
        assertEquals("api-key", runtime.apiKey)
        assertEquals("api-secret", runtime.apiSecret)
    }

    @Test
    fun `azure requires an HTTPS endpoint and stores its key`() = runBlocking {
        val repository = repository()

        val summary = repository.save(
            SpeechSettingsInput(
                provider = SpeechProvider.AZURE,
                azureEndpoint =
                    "https://example.cognitiveservices.azure.com/",
                azureApiKeyReplacement = "azure-key",
            ),
        )
        val runtime =
            repository.loadRuntimeConfig() as SpeechRuntimeConfig.Azure

        assertTrue(summary.isReady)
        assertEquals(
            "https://example.cognitiveservices.azure.com",
            runtime.endpoint,
        )
        assertEquals("azure-key", runtime.apiKey)
    }

    private fun repository(): DataStoreSpeechSettingsRepository =
        DataStoreSpeechSettingsRepository(
            dataStore = SpeechPreferencesDataStore(),
            secretCipher = SpeechFakeCipher(),
        )
}

private class SpeechFakeCipher : ApiKeyCipher {
    override fun encrypt(plaintext: String): EncryptedSecret =
        EncryptedSecret(
            ciphertext = plaintext.reversed(),
            iv = "speech-test-iv",
        )

    override fun decrypt(secret: EncryptedSecret): String {
        require(secret.iv == "speech-test-iv")
        return secret.ciphertext.reversed()
    }
}

private class SpeechPreferencesDataStore : DataStore<Preferences> {
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
