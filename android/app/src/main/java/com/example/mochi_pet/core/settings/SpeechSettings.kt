package com.example.mochi_pet.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
)

sealed interface SpeechRuntimeConfig {
    data object System : SpeechRuntimeConfig

    data class IFlytek(
        val appId: String,
        val apiKey: String,
        val apiSecret: String,
    ) : SpeechRuntimeConfig

    data class Azure(
        val endpoint: String,
        val apiKey: String,
    ) : SpeechRuntimeConfig
}

interface SpeechSettingsRepository {
    suspend fun loadSummary(): SpeechSettingsSummary

    suspend fun save(input: SpeechSettingsInput): SpeechSettingsSummary

    suspend fun loadRuntimeConfig(): SpeechRuntimeConfig
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
        val appId = input.iFlytekAppId.trim()
        val azureEndpoint = input.azureEndpoint.trim().trimEnd('/')
        if (input.provider == SpeechProvider.IFLYTEK) {
            require(appId.isNotEmpty()) {
                "iFlytek AppID must not be empty"
            }
        }
        if (input.provider == SpeechProvider.AZURE) {
            requireValidHttpsEndpoint(azureEndpoint)
        }
        val iFlytekApiKey = input.iFlytekApiKeyReplacement.toSecret()
        val iFlytekApiSecret =
            input.iFlytekApiSecretReplacement.toSecret()
        val azureApiKey = input.azureApiKeyReplacement.toSecret()
        dataStore.edit { preferences ->
            preferences[PROVIDER] = input.provider.name
            preferences[IFLYTEK_APP_ID] = appId
            preferences[AZURE_ENDPOINT] = azureEndpoint
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

    override suspend fun loadRuntimeConfig(): SpeechRuntimeConfig {
        val preferences = dataStore.data.first()
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
            )
            SpeechProvider.AZURE -> SpeechRuntimeConfig.Azure(
                endpoint = summary.azureEndpoint,
                apiKey = preferences.decrypt(
                    AZURE_API_KEY_CIPHERTEXT,
                    AZURE_API_KEY_IV,
                ),
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
        )

    private fun Preferences.hasSecret(
        ciphertextKey: Preferences.Key<String>,
        ivKey: Preferences.Key<String>,
    ): Boolean =
        !this[ciphertextKey].isNullOrBlank() &&
            !this[ivKey].isNullOrBlank()

    private companion object {
        val PROVIDER = stringPreferencesKey("speech.provider")
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
