package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.mochi_pet.core.agent.llm.ProviderType
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.tools.DataStoreToolCatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderShareManagerTest {
    @Test fun `speech share includes only checked accounts and imports additively with its active selection`() = runBlocking {
        val source = DataStoreSpeechSettingsRepository(Store(), Cipher)
        fun input(name: String, key: String) = SpeechProfileInput(name = name, settings = SpeechSettingsInput(
            SpeechProvider.AZURE, azureEndpoint = "https://fixture.cognitiveservices.azure.com",
            azureApiKeyReplacement = key, synthesisEnabled = true, azureVoice = "en-US-JennyNeural",
        ))
        val one = source.saveProfile(input("One", "one")).profiles.last()
        val two = source.saveProfile(input("Two", "two")).profiles.last()
        source.saveProfile(input("Excluded", "excluded"))
        source.activateProfile(two.id)
        val link = manager(repo(), source).createShareLink(ProviderShareSelection(
            includeLlm = false, speechProfileIds = setOf(one.id, two.id),
        ))
        val bundle = ProviderShareCodec.decode(link)
        assertEquals(listOf("One", "Two"), bundle.speechProfiles.map { it.name })
        assertEquals(1, bundle.activeSpeechIndex)
        val target = DataStoreSpeechSettingsRepository(Store(), Cipher)
        val existing = target.saveProfile(input("Existing", "existing")).profiles.last()
        manager(repo(), target).importShareLink(link)
        assertEquals(4, target.loadProfiles().profiles.size)
        assertEquals("Two", target.loadProfiles().active.name)
        assertEquals("two", (target.loadSynthesisConfig() as SpeechRuntimeConfig.Azure).apiKey)
        assertEquals("existing", (target.loadProfileRuntimeConfig(existing.id) as SpeechRuntimeConfig.Azure).apiKey)
        val excludedActive = manager(repo(), source).createShareLink(ProviderShareSelection(
            includeLlm = false, speechProfileIds = setOf(one.id),
        ))
        assertEquals(0, ProviderShareCodec.decode(excludedActive).activeSpeechIndex)
    }

    @Test fun `actual v2 and v3 speech payloads import without replacing old accounts or system voice`() = runBlocking {
        for (version in listOf(2, 3)) {
            val target = DataStoreSpeechSettingsRepository(Store(), Cipher)
            target.save(SpeechSettingsInput(SpeechProvider.SYSTEM, systemVoice = "device-only"))
            val plaintext = """{"version":$version,"speech":{"provider":"AZURE","azureEndpoint":"https://fixture.cognitiveservices.azure.com","azureApiKey":"legacy-key"}}"""
            val key = ByteArray(32) { 7 }
            val iv = ByteArray(12) { 9 }
            val encrypted = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
                init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, iv))
                updateAAD("mochi-provider-share-v$version".toByteArray())
                doFinal(plaintext.toByteArray())
            }
            val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
            val link = "mochi://provider/import#v$version." + listOf(key, iv, encrypted).joinToString(".") { encoder.encodeToString(it) }
            manager(repo(), target).importShareLink(link)
            assertEquals(2, target.loadProfiles().profiles.size)
            assertEquals("legacy-key", (target.loadRuntimeConfig() as SpeechRuntimeConfig.Azure).apiKey)
            assertEquals(SpeechRuntimeConfig.System("device-only"), target.loadSynthesisConfig())
        }
    }

    @Test fun `shares only selected profiles and imports them without overwriting existing connections`() = runBlocking {
        val source = repo()
        val one = source.saveProfile(input("One", "one-key")).active!!
        val two = source.saveProfile(input("Two", "two-key")).profiles.last()
        source.saveProfile(input("Unselected", "excluded-key"))
        source.activateProfile(two.id)
        val link = manager(source).createShareLink(ProviderShareSelection(
            includeSpeech = false, llmProfileIds = setOf(one.id, two.id),
        ))
        val bundle = ProviderShareCodec.decode(link)
        assertEquals(listOf("One", "Two"), bundle.llmProfiles.map { it.name })
        assertEquals(1, bundle.activeLlmIndex)
        assertFalse(link.contains("one-key"))
        assertFalse(bundle.llmProfiles.any { it.config.apiKey == "excluded-key" })
        val target = repo()
        val existing = target.saveProfile(input("Existing", "existing-key")).active!!
        manager(target).importShareLink(link)
        val imported = target.loadProfiles()
        assertEquals(3, imported.profiles.size)
        assertEquals("Two", imported.active!!.name)
        assertEquals("two-key", target.loadRuntimeConfig().apiKey)
        assertFalse(target.loadRuntimeConfig().imageInputEnabled)
        assertEquals("existing-key", target.loadProfileRuntimeConfig(existing.id).apiKey)
    }

    @Test fun `invalid profile in a bundle leaves all target connections unchanged`() = runBlocking {
        val target = repo()
        target.saveProfile(input("Existing", "existing-key"))
        val before = target.loadProfiles()
        val config = SharedLlmProvider(ProviderType.CUSTOM, ProviderPreset.DEEPSEEK.endpoint,
            "fixture", "2024-10-21", 60, 2_097_152, "synthetic-key")
        val link = ProviderShareCodec.encode(SharedProviderBundle(llmProfiles = listOf(
            SharedLlmProfile("Valid", ProviderPreset.DEEPSEEK, config),
            SharedLlmProfile("Invalid", ProviderPreset.DEEPSEEK, config.copy(apiKey = "")),
        )))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager(target).importShareLink(link) } }
        assertEquals(before, target.loadProfiles())
    }

    @Test fun `v2 single-provider links still import as a new saved connection`() = runBlocking {
        val old = SharedProviderBundle(version = 2, llm = SharedLlmProvider(
            ProviderType.OPENAI, "https://api.openai.com/v1", "fixture", "2024-10-21",
            60, 2_097_152, "legacy-key",
        ))
        val target = repo()
        target.saveProfile(input("Existing", "existing-key"))
        manager(target).importShareLink(ProviderShareCodec.encode(old))
        assertEquals(2, target.loadProfiles().profiles.size)
        assertEquals("legacy-key", target.loadRuntimeConfig().apiKey)
    }

    private fun input(name: String, key: String) = ProviderProfileInput(
        name = name, preset = ProviderPreset.DEEPSEEK,
        settings = ProviderSettingsInput(endpoint = ProviderPreset.DEEPSEEK.endpoint,
            model = "fixture-model", apiKeyReplacement = key, imageInputEnabled = false),
    )
    private fun repo() = DataStoreProviderSettingsRepository(Store(), Cipher)
    private fun manager(repo: ProviderSettingsRepository, speech: SpeechSettingsRepository = DataStoreSpeechSettingsRepository(Store(), Cipher)) = ProviderShareManager(
        repo, speech,
        DataStoreToolCatalogRepository(Store(), Cipher, McpStreamableHttpClient()),
    )
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private object Cipher : ApiKeyCipher {
        override fun encrypt(plaintext: String) = EncryptedSecret(plaintext.reversed(), "fixture")
        override fun decrypt(secret: EncryptedSecret) = secret.ciphertext.reversed()
    }
}
