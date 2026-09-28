package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SpeechProvider {
    SYSTEM,
    IFLYTEK,
    AZURE,
}

@Serializable
data class SpeechSettingsSummary(
    val provider: SpeechProvider = SpeechProvider.SYSTEM,
    val iFlytekAppId: String = "",
    val hasIFlytekApiKey: Boolean = false,
    val hasIFlytekApiSecret: Boolean = false,
    val azureEndpoint: String = "",
    val hasAzureApiKey: Boolean = false,
    val synthesisEnabled: Boolean = false,
    val iFlytekVoice: String = "",
    val azureVoice: String = "",
    val systemVoice: String = "",
) {
    val isReady: Boolean
        get() = when (provider) {
            SpeechProvider.SYSTEM -> true
            SpeechProvider.IFLYTEK ->
                iFlytekAppId.isNotBlank() &&
                    hasIFlytekApiKey &&
                    hasIFlytekApiSecret
            SpeechProvider.AZURE ->
                azureEndpoint.isNotBlank() && hasAzureApiKey
        }
}

data class SpeechSettingsInput(
    val provider: SpeechProvider,
    val iFlytekAppId: String = "",
    val iFlytekApiKeyReplacement: String? = null,
    val iFlytekApiSecretReplacement: String? = null,
    val azureEndpoint: String = "",
    val azureApiKeyReplacement: String? = null,
    val synthesisEnabled: Boolean = false,
    val iFlytekVoice: String? = null,
    val azureVoice: String? = null,
    val systemVoice: String? = null,
)

internal fun SpeechSettingsInput.validate() {
    require(systemVoice == null || (systemVoice.length <= 256 && systemVoice.none(Char::isISOControl))) {
        "Invalid system voice ID"
    }
    require(
        listOfNotNull(iFlytekVoice, azureVoice).all {
            it.trim().isEmpty() ||
                it.trim().matches(Regex("[A-Za-z0-9_:-]{1,100}"))
        },
    ) {
        "Speech voice must be a valid provider voice ID"
    }
    if (provider == SpeechProvider.IFLYTEK) {
        require(iFlytekAppId.trim().isNotEmpty()) {
            "iFlytek AppID must not be empty"
        }
    }
    if (provider == SpeechProvider.AZURE) {
        requireValidHttpsEndpoint(azureEndpoint.trim().trimEnd('/'))
    }
}

sealed interface SpeechRuntimeConfig {
    data class System(val voice: String = "") : SpeechRuntimeConfig

    data class IFlytek(
        val appId: String,
        val apiKey: String,
        val apiSecret: String,
        val synthesisEnabled: Boolean = false,
        val voice: String = "",
    ) : SpeechRuntimeConfig

    data class Azure(
        val endpoint: String,
        val apiKey: String,
        val synthesisEnabled: Boolean = false,
        val voice: String = "",
    ) : SpeechRuntimeConfig
}

interface SpeechSettingsRepository {
    suspend fun loadSummary(): SpeechSettingsSummary

    suspend fun save(input: SpeechSettingsInput): SpeechSettingsSummary

    suspend fun loadRuntimeConfig(): SpeechRuntimeConfig

    suspend fun loadProfiles(): SpeechProfilesSummary
    suspend fun saveProfile(input: SpeechProfileInput): SpeechProfilesSummary
    suspend fun activateProfile(id: String): SpeechProfilesSummary
    suspend fun deleteProfile(id: String): SpeechProfilesSummary
    suspend fun importProfiles(inputs: List<SpeechProfileInput>, activeIndex: Int): SpeechProfilesSummary
    suspend fun loadProfileRuntimeConfig(id: String): SpeechRuntimeConfig

    suspend fun loadSynthesisConfig(): SpeechRuntimeConfig =
        if (loadSummary().synthesisEnabled) {
            loadRuntimeConfig()
        } else {
            SpeechRuntimeConfig.System(loadSummary().systemVoice)
        }
}

class DataStoreSpeechSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: ApiKeyCipher,
) : SpeechSettingsRepository {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun loadSummary(): SpeechSettingsSummary =
        loadProfiles().active.settings

    override suspend fun save(
        input: SpeechSettingsInput,
    ): SpeechSettingsSummary = mutex.withLock {
        val catalog = readProfiles()
        val old = catalog.profiles.firstOrNull {
            it.id == catalog.activeId && it.settings.provider == input.provider
        } ?: catalog.profiles.firstOrNull { it.settings.provider == input.provider }
        val saved = makeProfile(SpeechProfileInput(old?.id, old?.name ?: input.provider.connectionTitle, input), old)
        writeProfiles(catalog.copy(profiles = catalog.profiles.filterNot { it.id == saved.id } + saved, activeId = saved.id))
        loadSummary()
    }

    override suspend fun loadRuntimeConfig(): SpeechRuntimeConfig =
        readProfiles().let { runtimeConfig(it.profiles.single { profile -> profile.id == it.activeId }) }

    override suspend fun loadProfiles(): SpeechProfilesSummary = readProfiles().summary()

    override suspend fun saveProfile(input: SpeechProfileInput): SpeechProfilesSummary = mutex.withLock {
        val catalog = readProfiles()
        val old = input.id?.let { id -> catalog.profiles.firstOrNull { it.id == id } }
        require(input.id == null || old != null) { "Saved speech connection no longer exists." }
        require(input.settings.provider != SpeechProvider.SYSTEM || old?.id == SYSTEM_SPEECH_PROFILE_ID) {
            "Edit the built-in Android speech connection instead."
        }
        val saved = makeProfile(input, old)
        val updated = catalog.copy(profiles = if (old == null) catalog.profiles + saved else catalog.profiles.map {
            if (it.id == saved.id) saved else it
        })
        writeProfiles(updated)
        updated.summary()
    }

    override suspend fun activateProfile(id: String): SpeechProfilesSummary = mutex.withLock {
        val catalog = readProfiles()
        require(catalog.profiles.any { it.id == id && it.settings.isReady }) {
            "Complete this speech connection before using it."
        }
        val updated = catalog.copy(activeId = id)
        writeProfiles(updated)
        updated.summary()
    }

    override suspend fun deleteProfile(id: String): SpeechProfilesSummary = mutex.withLock {
        val catalog = readProfiles()
        require(id != SYSTEM_SPEECH_PROFILE_ID) { "The built-in Android speech connection cannot be deleted." }
        require(id != catalog.activeId) { "Select another speech connection before deleting this one." }
        require(catalog.profiles.any { it.id == id }) { "Saved speech connection no longer exists." }
        val updated = catalog.copy(profiles = catalog.profiles.filterNot { it.id == id })
        writeProfiles(updated)
        updated.summary()
    }

    override suspend fun importProfiles(inputs: List<SpeechProfileInput>, activeIndex: Int): SpeechProfilesSummary =
        mutex.withLock {
            require(inputs.isNotEmpty() && activeIndex in inputs.indices) { "Shared speech connection selection is invalid." }
            val catalog = readProfiles()
            val imported = inputs.map {
                if (it.settings.provider == SpeechProvider.SYSTEM) {
                    it.settings.validate()
                    catalog.profiles.single { profile -> profile.id == SYSTEM_SPEECH_PROFILE_ID }
                } else makeProfile(it.copy(id = null), null)
            }
            val updated = catalog.copy(
                profiles = catalog.profiles + imported.filter { it.id != SYSTEM_SPEECH_PROFILE_ID },
                activeId = imported[activeIndex].id,
            )
            writeProfiles(updated)
            updated.summary()
        }

    override suspend fun loadProfileRuntimeConfig(id: String): SpeechRuntimeConfig =
        runtimeConfig(readProfiles().profiles.firstOrNull { it.id == id }
            ?: throw IllegalStateException("Saved speech connection no longer exists."))

    override suspend fun loadSynthesisConfig(): SpeechRuntimeConfig {
        val catalog = readProfiles()
        val active = catalog.profiles.single { it.id == catalog.activeId }
        return runtimeConfig(if (active.settings.synthesisEnabled) active
            else catalog.profiles.single { it.id == SYSTEM_SPEECH_PROFILE_ID })
    }

    private fun runtimeConfig(profile: StoredSpeechProfile): SpeechRuntimeConfig {
        val summary = profile.settings
        check(summary.isReady) { "Complete the selected speech provider credentials" }
        return when (summary.provider) {
            SpeechProvider.SYSTEM -> SpeechRuntimeConfig.System(summary.systemVoice)
            SpeechProvider.IFLYTEK -> SpeechRuntimeConfig.IFlytek(
                appId = summary.iFlytekAppId,
                apiKey = secretCipher.decrypt(checkNotNull(profile.key)),
                apiSecret = secretCipher.decrypt(checkNotNull(profile.secret)),
                synthesisEnabled = summary.synthesisEnabled,
                voice = summary.iFlytekVoice,
            )
            SpeechProvider.AZURE -> SpeechRuntimeConfig.Azure(
                endpoint = summary.azureEndpoint,
                apiKey = secretCipher.decrypt(checkNotNull(profile.key)),
                synthesisEnabled = summary.synthesisEnabled,
                voice = summary.azureVoice,
            )
        }
    }

    private fun String?.toSecret(): EncryptedSecret? =
        this
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(secretCipher::encrypt)

    private fun Preferences.encrypted(
        ciphertextKey: Preferences.Key<String>,
        ivKey: Preferences.Key<String>,
    ): EncryptedSecret? {
        val ciphertext = this[ciphertextKey]?.takeIf(String::isNotBlank)
        val iv = this[ivKey]?.takeIf(String::isNotBlank)
        check((ciphertext == null) == (iv == null)) { "Stored speech credentials are incomplete." }
        return if (ciphertext != null && iv != null) EncryptedSecret(ciphertext, iv) else null
    }

    private fun makeProfile(input: SpeechProfileInput, old: StoredSpeechProfile?): StoredSpeechProfile {
        require(input.name.trim().length in 1..120) { "Name the speech connection using 1-120 characters." }
        val value = input.settings
        value.validate()
        require(old == null || old.settings.provider == value.provider) {
            "Add a new connection to use a different speech provider."
        }
        if (old?.key != null && value.provider == SpeechProvider.AZURE &&
            value.azureApiKeyReplacement.isNullOrBlank()
        ) {
            require(URI(old.settings.azureEndpoint).authority == URI(value.azureEndpoint.trim()).authority) {
                "Changing the speech endpoint requires a new API key."
            }
        }
        val key = when (value.provider) {
            SpeechProvider.SYSTEM -> null
            SpeechProvider.IFLYTEK -> value.iFlytekApiKeyReplacement.toSecret() ?: old?.key
            SpeechProvider.AZURE -> value.azureApiKeyReplacement.toSecret() ?: old?.key
        }
        val secret = if (value.provider == SpeechProvider.IFLYTEK) {
            value.iFlytekApiSecretReplacement.toSecret() ?: old?.secret
        } else null
        val summary = SpeechSettingsSummary(
            provider = value.provider,
            iFlytekAppId = if (value.provider == SpeechProvider.IFLYTEK) value.iFlytekAppId.trim() else "",
            hasIFlytekApiKey = value.provider == SpeechProvider.IFLYTEK && key != null,
            hasIFlytekApiSecret = secret != null,
            azureEndpoint = if (value.provider == SpeechProvider.AZURE) value.azureEndpoint.trim().trimEnd('/') else "",
            hasAzureApiKey = value.provider == SpeechProvider.AZURE && key != null,
            synthesisEnabled = value.synthesisEnabled && value.provider != SpeechProvider.SYSTEM,
            iFlytekVoice = if (value.provider == SpeechProvider.IFLYTEK) value.iFlytekVoice?.trim() ?: old?.settings?.iFlytekVoice.orEmpty() else "",
            azureVoice = if (value.provider == SpeechProvider.AZURE) value.azureVoice?.trim() ?: old?.settings?.azureVoice.orEmpty() else "",
            systemVoice = if (value.provider == SpeechProvider.SYSTEM) value.systemVoice ?: old?.settings?.systemVoice.orEmpty() else "",
        )
        require(summary.isReady) { "Complete the selected speech provider credentials" }
        return StoredSpeechProfile(old?.id ?: UUID.randomUUID().toString(), input.name.trim(), summary, key, secret)
    }

    private suspend fun readProfiles(): StoredSpeechProfiles {
        val snapshot = dataStore.edit { preferences ->
            if (preferences[PROFILES] == null) {
                val legacy = preferences.toSummary()
                val iflytekKey = preferences.encrypted(IFLYTEK_API_KEY_CIPHERTEXT, IFLYTEK_API_KEY_IV)
                val iflytekSecret = preferences.encrypted(IFLYTEK_API_SECRET_CIPHERTEXT, IFLYTEK_API_SECRET_IV)
                val azureKey = preferences.encrypted(AZURE_API_KEY_CIPHERTEXT, AZURE_API_KEY_IV)
                val profiles = mutableListOf(StoredSpeechProfile(
                    SYSTEM_SPEECH_PROFILE_ID, SpeechProvider.SYSTEM.connectionTitle,
                    SpeechSettingsSummary(systemVoice = legacy.systemVoice),
                ))
                if (legacy.provider == SpeechProvider.IFLYTEK || legacy.iFlytekAppId.isNotBlank() ||
                    iflytekKey != null || iflytekSecret != null || legacy.iFlytekVoice.isNotBlank()
                ) profiles += StoredSpeechProfile(
                    "migrated-iflytek", SpeechProvider.IFLYTEK.connectionTitle,
                    SpeechSettingsSummary(
                        provider = SpeechProvider.IFLYTEK, iFlytekAppId = legacy.iFlytekAppId,
                        hasIFlytekApiKey = iflytekKey != null, hasIFlytekApiSecret = iflytekSecret != null,
                        synthesisEnabled = legacy.provider == SpeechProvider.IFLYTEK && legacy.synthesisEnabled,
                        iFlytekVoice = legacy.iFlytekVoice,
                    ), iflytekKey, iflytekSecret,
                )
                if (legacy.provider == SpeechProvider.AZURE || legacy.azureEndpoint.isNotBlank() ||
                    azureKey != null || legacy.azureVoice.isNotBlank()
                ) profiles += StoredSpeechProfile(
                    "migrated-azure", SpeechProvider.AZURE.connectionTitle,
                    SpeechSettingsSummary(
                        provider = SpeechProvider.AZURE, azureEndpoint = legacy.azureEndpoint,
                        hasAzureApiKey = azureKey != null,
                        synthesisEnabled = legacy.provider == SpeechProvider.AZURE && legacy.synthesisEnabled,
                        azureVoice = legacy.azureVoice,
                    ), azureKey,
                )
                preferences[PROFILES] = json.encodeToString(StoredSpeechProfiles(
                    profiles = profiles, activeId = profiles.first { it.settings.provider == legacy.provider }.id,
                ))
                listOf(PROVIDER, IFLYTEK_VOICE, AZURE_VOICE, SYSTEM_VOICE, IFLYTEK_APP_ID,
                    IFLYTEK_API_KEY_CIPHERTEXT, IFLYTEK_API_KEY_IV, IFLYTEK_API_SECRET_CIPHERTEXT,
                    IFLYTEK_API_SECRET_IV, AZURE_ENDPOINT, AZURE_API_KEY_CIPHERTEXT, AZURE_API_KEY_IV)
                    .forEach { preferences.remove(it) }
                preferences.remove(SYNTHESIS_ENABLED)
            }
        }
        return try {
            json.decodeFromString<StoredSpeechProfiles>(snapshot[PROFILES]!!).also { catalog ->
                check(catalog.version == 1 && catalog.profiles.map { it.id }.distinct().size == catalog.profiles.size &&
                    catalog.profiles.any { it.id == catalog.activeId } &&
                    catalog.profiles.singleOrNull { it.settings.provider == SpeechProvider.SYSTEM }?.id == SYSTEM_SPEECH_PROFILE_ID
                ) { "Stored speech connections are invalid." }
            }
        } catch (_: SerializationException) {
            throw IllegalStateException("Stored speech connections are invalid.")
        }
    }

    private suspend fun writeProfiles(catalog: StoredSpeechProfiles) {
        dataStore.edit { it[PROFILES] = json.encodeToString(catalog) }
    }

    @Serializable
    private data class StoredSpeechProfile(
        val id: String, val name: String, val settings: SpeechSettingsSummary,
        val key: EncryptedSecret? = null, val secret: EncryptedSecret? = null,
    )

    @Serializable
    private data class StoredSpeechProfiles(
        val version: Int = 1, val profiles: List<StoredSpeechProfile>, val activeId: String,
    ) {
        fun summary(): SpeechProfilesSummary {
            val systemVoice = profiles.single { it.id == SYSTEM_SPEECH_PROFILE_ID }.settings.systemVoice
            return SpeechProfilesSummary(
                profiles.map { SpeechProfileSummary(it.id, it.name, it.settings.copy(systemVoice = systemVoice)) }, activeId,
            )
        }
    }

    private fun Preferences.toSummary(): SpeechSettingsSummary =
        SpeechSettingsSummary(
            provider = this[PROVIDER]
                ?.let { stored ->
                    SpeechProvider.entries.firstOrNull {
                        it.name == stored
                    }
                }
                ?: SpeechProvider.SYSTEM,
            iFlytekAppId = this[IFLYTEK_APP_ID].orEmpty(),
            hasIFlytekApiKey = hasSecret(
                IFLYTEK_API_KEY_CIPHERTEXT,
                IFLYTEK_API_KEY_IV,
            ),
            hasIFlytekApiSecret = hasSecret(
                IFLYTEK_API_SECRET_CIPHERTEXT,
                IFLYTEK_API_SECRET_IV,
            ),
            azureEndpoint = this[AZURE_ENDPOINT].orEmpty(),
            hasAzureApiKey = hasSecret(
                AZURE_API_KEY_CIPHERTEXT,
                AZURE_API_KEY_IV,
            ),
            synthesisEnabled = this[SYNTHESIS_ENABLED] ?: false,
            iFlytekVoice = this[IFLYTEK_VOICE].orEmpty(),
            azureVoice = this[AZURE_VOICE].orEmpty(),
            systemVoice = this[SYSTEM_VOICE].orEmpty(),
        )

    private fun Preferences.hasSecret(
        ciphertextKey: Preferences.Key<String>,
        ivKey: Preferences.Key<String>,
    ): Boolean =
        !this[ciphertextKey].isNullOrBlank() &&
            !this[ivKey].isNullOrBlank()

    private companion object {
        val PROFILES = stringPreferencesKey("speech.profiles.v1")
        val PROVIDER = stringPreferencesKey("speech.provider")
        val SYNTHESIS_ENABLED = booleanPreferencesKey("speech.synthesis_enabled")
        val IFLYTEK_VOICE = stringPreferencesKey("speech.iflytek.voice")
        val AZURE_VOICE = stringPreferencesKey("speech.azure.voice")
        val SYSTEM_VOICE = stringPreferencesKey("speech.system.voice")
        val IFLYTEK_APP_ID = stringPreferencesKey("speech.iflytek.app_id")
        val IFLYTEK_API_KEY_CIPHERTEXT =
            stringPreferencesKey("speech.iflytek.api_key_ciphertext")
        val IFLYTEK_API_KEY_IV =
            stringPreferencesKey("speech.iflytek.api_key_iv")
        val IFLYTEK_API_SECRET_CIPHERTEXT =
            stringPreferencesKey("speech.iflytek.api_secret_ciphertext")
        val IFLYTEK_API_SECRET_IV =
            stringPreferencesKey("speech.iflytek.api_secret_iv")
        val AZURE_ENDPOINT = stringPreferencesKey("speech.azure.endpoint")
        val AZURE_API_KEY_CIPHERTEXT =
            stringPreferencesKey("speech.azure.api_key_ciphertext")
        val AZURE_API_KEY_IV =
            stringPreferencesKey("speech.azure.api_key_iv")
    }
}

private fun requireValidHttpsEndpoint(endpoint: String) {
    val uri = try {
        URI(endpoint)
    } catch (_: java.net.URISyntaxException) {
        null
    }
    require(
        uri?.scheme.equals("https", ignoreCase = true) &&
            !uri?.host.isNullOrBlank() && uri?.userInfo == null && uri?.fragment == null,
    ) {
        "Azure Speech endpoint must be a valid HTTPS URL"
    }
}
