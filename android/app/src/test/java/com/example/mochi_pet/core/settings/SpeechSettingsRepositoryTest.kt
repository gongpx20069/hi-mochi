package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechSettingsRepositoryTest {
    @Test
    fun `synthesis is opt in and reuses encrypted iFlytek secrets`() = runBlocking {
        val repository = repository()
        val initial = SpeechSettingsInput(
            provider = SpeechProvider.IFLYTEK,
            iFlytekAppId = "test-app",
            iFlytekApiKeyReplacement = "test-key",
            iFlytekApiSecretReplacement = "test-secret",
        )
        repository.save(initial)
        assertFalse(repository.loadSummary().synthesisEnabled)
        assertEquals(SpeechRuntimeConfig.System, repository.loadSynthesisConfig())

        repository.save(
            initial.copy(
                iFlytekApiKeyReplacement = "",
                iFlytekApiSecretReplacement = "",
                synthesisEnabled = true,
                iFlytekVoice = " x4_xiaoyan ",
            ),
        )
        val config = repository.loadSynthesisConfig() as SpeechRuntimeConfig.IFlytek
        assertEquals("test-key", config.apiKey)
        assertEquals("test-secret", config.apiSecret)
        assertEquals("x4_xiaoyan", config.voice)
        assertTrue(repository.loadSummary().synthesisEnabled)

        repository.save(SpeechSettingsInput(provider = SpeechProvider.SYSTEM, synthesisEnabled = true))
        assertFalse(repository.loadSummary().synthesisEnabled)
        assertEquals(SpeechRuntimeConfig.System, repository.loadSynthesisConfig())
    }

    @Test
    fun `azure synthesis voice survives reload without replacing the key`() = runBlocking {
        val store = SpeechPreferencesDataStore()
        val repository = DataStoreSpeechSettingsRepository(store, SpeechFakeCipher())
        val input = SpeechSettingsInput(
            provider = SpeechProvider.AZURE,
            azureEndpoint = "https://test.cognitiveservices.azure.com",
            azureApiKeyReplacement = "test-key",
            synthesisEnabled = true,
            azureVoice = "zh-CN-XiaoxiaoNeural",
        )
        repository.save(input)
        repository.save(input.copy(azureApiKeyReplacement = ""))
        val restored = DataStoreSpeechSettingsRepository(store, SpeechFakeCipher())
        val config = restored.loadSynthesisConfig() as SpeechRuntimeConfig.Azure
        assertEquals("test-key", config.apiKey)
        assertEquals(input.azureVoice, config.voice)
        assertEquals(input.azureVoice, restored.loadSummary().azureVoice)
    }

    @Test
    fun `invalid voice ID is rejected before changing stored settings`() = runBlocking {
        val repository = repository()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.save(
                    SpeechSettingsInput(
                        provider = SpeechProvider.SYSTEM,
                        azureVoice = "<voice/>",
                    ),
                )
            }
        }
        assertEquals(SpeechSettingsSummary(), repository.loadSummary())
    }

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
