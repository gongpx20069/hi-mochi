package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechSettingsRepositoryTest {
    @Test fun `migrates active and inactive legacy accounts without decrypting or losing voices`() = runBlocking {
        val store = SpeechPreferencesDataStore()
        store.updateData { it.toMutablePreferences().apply {
            this[stringPreferencesKey("speech.provider")] = "AZURE"
            this[booleanPreferencesKey("speech.synthesis_enabled")] = true
            this[stringPreferencesKey("speech.iflytek.app_id")] = "legacy-app"
            this[stringPreferencesKey("speech.iflytek.api_key_ciphertext")] = "iflytek-key".reversed()
            this[stringPreferencesKey("speech.iflytek.api_key_iv")] = "speech-test-iv"
            this[stringPreferencesKey("speech.iflytek.api_secret_ciphertext")] = "iflytek-secret".reversed()
            this[stringPreferencesKey("speech.iflytek.api_secret_iv")] = "speech-test-iv"
            this[stringPreferencesKey("speech.iflytek.voice")] = "x4_yezi"
            this[stringPreferencesKey("speech.azure.endpoint")] = "https://fixture.cognitiveservices.azure.com"
            this[stringPreferencesKey("speech.azure.api_key_ciphertext")] = "azure-key".reversed()
            this[stringPreferencesKey("speech.azure.api_key_iv")] = "speech-test-iv"
            this[stringPreferencesKey("speech.azure.voice")] = "zh-CN-XiaoxiaoNeural"
            this[stringPreferencesKey("speech.system.voice")] = "offline-fixture"
        } }
        val repo = DataStoreSpeechSettingsRepository(store, SpeechFakeCipher())
        val profiles = repo.loadProfiles()
        assertEquals(3, profiles.profiles.size)
        assertEquals(SpeechProvider.AZURE, profiles.active.settings.provider)
        assertEquals("azure-key", (repo.loadSynthesisConfig() as SpeechRuntimeConfig.Azure).apiKey)
        val iflytek = profiles.profiles.single { it.settings.provider == SpeechProvider.IFLYTEK }
        assertEquals("iflytek-key", (repo.loadProfileRuntimeConfig(iflytek.id) as SpeechRuntimeConfig.IFlytek).apiKey)
        assertEquals("x4_yezi", iflytek.settings.iFlytekVoice)
        assertEquals(null, store.data.value[stringPreferencesKey("speech.azure.api_key_ciphertext")])
        assertEquals(profiles, DataStoreSpeechSettingsRepository(store, SpeechFakeCipher()).loadProfiles())
    }

    @Test fun `same-provider accounts keep independent keys voices and explicit selection`() = runBlocking {
        val repo = repository()
        val first = repo.saveProfile(azureProfile("One", "one", "en-US-JennyNeural")).profiles.last()
        val second = repo.saveProfile(azureProfile("Two", "two", "zh-CN-XiaoxiaoNeural")).profiles.last()
        assertEquals(SYSTEM_SPEECH_PROFILE_ID, repo.loadProfiles().activeId)
        repo.activateProfile(first.id)
        repo.saveProfile(azureProfile("Two renamed", null, "zh-CN-XiaoxiaoNeural").copy(id = second.id))
        assertEquals("one", (repo.loadRuntimeConfig() as SpeechRuntimeConfig.Azure).apiKey)
        assertEquals("two", (repo.loadProfileRuntimeConfig(second.id) as SpeechRuntimeConfig.Azure).apiKey)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.deleteProfile(first.id) } }
        repo.activateProfile(second.id)
        repo.deleteProfile(first.id)
        assertEquals("zh-CN-XiaoxiaoNeural", (repo.loadSynthesisConfig() as SpeechRuntimeConfig.Azure).voice)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.deleteProfile(SYSTEM_SPEECH_PROFILE_ID) } }
        Unit
    }

    @Test fun `invalid save import and cross-host key reuse leave catalog unchanged`() = runBlocking {
        val repo = repository()
        val first = repo.saveProfile(azureProfile("One", "one", "")).profiles.last()
        val before = repo.loadProfiles()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repo.saveProfile(azureProfile("Other", null, "").let { it.copy(id = first.id,
                settings = it.settings.copy(azureEndpoint = "https://other.example")) })
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repo.importProfiles(listOf(azureProfile("Valid", "key", ""), azureProfile("Invalid", null, "")), 0)
        } }
        assertEquals(before, repo.loadProfiles())
    }

    @Test fun `corrupt catalog and half legacy secret are reported without replacing data`() = runBlocking {
        for ((key, value) in listOf("speech.profiles.v1" to "broken", "speech.azure.api_key_ciphertext" to "partial")) {
            val store = SpeechPreferencesDataStore()
            store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey(key)] = value } }
            val repo = DataStoreSpeechSettingsRepository(store, SpeechFakeCipher())
            assertThrows(IllegalStateException::class.java) { runBlocking { repo.loadProfiles() } }
            assertEquals(value, store.data.value[stringPreferencesKey(key)])
        }
    }

    private fun azureProfile(name: String, key: String?, voice: String) = SpeechProfileInput(
        name = name, settings = SpeechSettingsInput(SpeechProvider.AZURE,
            azureEndpoint = "https://fixture.cognitiveservices.azure.com", azureApiKeyReplacement = key,
            synthesisEnabled = true, azureVoice = voice),
    )

    @Test
    fun `saving other providers preserves all voice selections including local system voice`() = runBlocking {
        val repository = repository()
        repository.save(
            SpeechSettingsInput(
                SpeechProvider.IFLYTEK,
                iFlytekAppId = "test-app",
                iFlytekApiKeyReplacement = "test-key",
                iFlytekApiSecretReplacement = "test-secret",
                iFlytekVoice = "x4_yezi",
            ),
        )
        repository.save(
            SpeechSettingsInput(
                SpeechProvider.AZURE,
                azureEndpoint = "https://test.cognitiveservices.azure.com",
                azureApiKeyReplacement = "test-key",
                azureVoice = "en-US-JennyNeural",
            ),
        )
        repository.save(SpeechSettingsInput(SpeechProvider.SYSTEM, systemVoice = "en-us-x-test-local"))
        val summary = repository.loadSummary()
        val profiles = repository.loadProfiles().profiles
        assertEquals("x4_yezi", profiles.single { it.settings.provider == SpeechProvider.IFLYTEK }.settings.iFlytekVoice)
        assertEquals("en-US-JennyNeural", profiles.single { it.settings.provider == SpeechProvider.AZURE }.settings.azureVoice)
        assertEquals(SpeechRuntimeConfig.System("en-us-x-test-local"), repository.loadSynthesisConfig())
        repository.save(SpeechSettingsInput(SpeechProvider.SYSTEM, systemVoice = ""))
        assertEquals(SpeechRuntimeConfig.System(), repository.loadSynthesisConfig())
    }

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
        assertEquals(SpeechRuntimeConfig.System(), repository.loadSynthesisConfig())

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
        assertEquals(SpeechRuntimeConfig.System(), repository.loadSynthesisConfig())
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
            SpeechRuntimeConfig.System(),
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

internal class SpeechFakeCipher : ApiKeyCipher {
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

internal class SpeechPreferencesDataStore : DataStore<Preferences> {
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
