package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.URI
import kotlinx.coroutines.flow.first

enum class SpeechProvider {
    SYSTEM,
    IFLYTEK,
    AZURE,
}

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
    val iFlytekVoice: String = "",
    val azureVoice: String = "",
)

internal fun SpeechSettingsInput.validate() {
    require(
        listOf(iFlytekVoice, azureVoice).all {
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
    data object System : SpeechRuntimeConfig

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

    suspend fun loadSynthesisConfig(): SpeechRuntimeConfig =
        if (loadSummary().synthesisEnabled) {
            loadRuntimeConfig()
        } else {
            SpeechRuntimeConfig.System
        }
}

class DataStoreSpeechSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: ApiKeyCipher,
) : SpeechSettingsRepository {
    override suspend fun loadSummary(): SpeechSettingsSummary =
        dataStore.data.first().toSummary()

    override suspend fun save(
        input: SpeechSettingsInput,
    ): SpeechSettingsSummary {
        input.validate()
        val appId = input.iFlytekAppId.trim()
        val azureEndpoint = input.azureEndpoint.trim().trimEnd('/')
        val iFlytekApiKey = input.iFlytekApiKeyReplacement.toSecret()
        val iFlytekApiSecret =
            input.iFlytekApiSecretReplacement.toSecret()
        val azureApiKey = input.azureApiKeyReplacement.toSecret()
        dataStore.edit { preferences ->
            preferences[PROVIDER] = input.provider.name
            preferences[IFLYTEK_APP_ID] = appId
            preferences[AZURE_ENDPOINT] = azureEndpoint
            preferences[SYNTHESIS_ENABLED] =
                input.synthesisEnabled && input.provider != SpeechProvider.SYSTEM
            preferences[IFLYTEK_VOICE] = input.iFlytekVoice.trim()
            preferences[AZURE_VOICE] = input.azureVoice.trim()
            iFlytekApiKey?.let {
                preferences[IFLYTEK_API_KEY_CIPHERTEXT] = it.ciphertext
                preferences[IFLYTEK_API_KEY_IV] = it.iv
            }
            iFlytekApiSecret?.let {
                preferences[IFLYTEK_API_SECRET_CIPHERTEXT] = it.ciphertext
                preferences[IFLYTEK_API_SECRET_IV] = it.iv
            }
            azureApiKey?.let {
                preferences[AZURE_API_KEY_CIPHERTEXT] = it.ciphertext
                preferences[AZURE_API_KEY_IV] = it.iv
            }
        }
        val summary = loadSummary()
        require(summary.isReady) {
            "Complete the selected speech provider credentials"
        }
        return summary
    }

    override suspend fun loadRuntimeConfig(): SpeechRuntimeConfig =
        runtimeConfig(dataStore.data.first())

    override suspend fun loadSynthesisConfig(): SpeechRuntimeConfig {
        val preferences = dataStore.data.first()
        return if (preferences[SYNTHESIS_ENABLED] == true) {
            runtimeConfig(preferences)
        } else {
            SpeechRuntimeConfig.System
        }
    }

    private fun runtimeConfig(preferences: Preferences): SpeechRuntimeConfig {
        val summary = preferences.toSummary()
        return when (summary.provider) {
            SpeechProvider.SYSTEM -> SpeechRuntimeConfig.System
            SpeechProvider.IFLYTEK -> SpeechRuntimeConfig.IFlytek(
                appId = summary.iFlytekAppId,
                apiKey = preferences.decrypt(
                    IFLYTEK_API_KEY_CIPHERTEXT,
                    IFLYTEK_API_KEY_IV,
                ),
                apiSecret = preferences.decrypt(
                    IFLYTEK_API_SECRET_CIPHERTEXT,
                    IFLYTEK_API_SECRET_IV,
                ),
                synthesisEnabled = summary.synthesisEnabled,
                voice = summary.iFlytekVoice,
            )
            SpeechProvider.AZURE -> SpeechRuntimeConfig.Azure(
                endpoint = summary.azureEndpoint,
                apiKey = preferences.decrypt(
                    AZURE_API_KEY_CIPHERTEXT,
                    AZURE_API_KEY_IV,
                ),
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

    private fun Preferences.decrypt(
        ciphertextKey: Preferences.Key<String>,
        ivKey: Preferences.Key<String>,
    ): String {
        val ciphertext = this[ciphertextKey]
            ?: error("Speech provider secret is missing")
        val iv = this[ivKey]
            ?: error("Speech provider secret IV is missing")
        return secretCipher.decrypt(
            EncryptedSecret(ciphertext = ciphertext, iv = iv),
        )
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
        )

    private fun Preferences.hasSecret(
        ciphertextKey: Preferences.Key<String>,
        ivKey: Preferences.Key<String>,
    ): Boolean =
        !this[ciphertextKey].isNullOrBlank() &&
            !this[ivKey].isNullOrBlank()

    private companion object {
        val PROVIDER = stringPreferencesKey("speech.provider")
        val SYNTHESIS_ENABLED = booleanPreferencesKey("speech.synthesis_enabled")
        val IFLYTEK_VOICE = stringPreferencesKey("speech.iflytek.voice")
        val AZURE_VOICE = stringPreferencesKey("speech.azure.voice")
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
    } catch (_: IllegalArgumentException) {
        null
    }
    require(
        uri?.scheme.equals("https", ignoreCase = true) &&
            !uri?.host.isNullOrBlank(),
    ) {
        "Azure Speech endpoint must be a valid HTTPS URL"
    }
}
