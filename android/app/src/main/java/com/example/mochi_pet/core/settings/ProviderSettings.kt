package com.example.mochi_pet.core.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.agent.llm.DEFAULT_AZURE_API_VERSION
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.ProviderType
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

data class ProviderSettingsSummary(
    val providerType: ProviderType = ProviderType.CUSTOM,
    val endpoint: String = "",
    val model: String = "",
    val apiVersion: String = DEFAULT_AZURE_API_VERSION,
    val timeoutSeconds: Int = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    val maxResponseBytes: Long = DEFAULT_PROVIDER_MAX_RESPONSE_BYTES,
    val hasApiKey: Boolean = false,
) {
    val isReady: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank() && hasApiKey
}

data class ProviderSettingsInput(
    val providerType: ProviderType = ProviderType.CUSTOM,
    val endpoint: String,
    val model: String,
    val apiVersion: String = DEFAULT_AZURE_API_VERSION,
    val timeoutSeconds: Int = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    val maxResponseBytes: Long = DEFAULT_PROVIDER_MAX_RESPONSE_BYTES,
    val apiKeyReplacement: String? = null,
)

data class EncryptedSecret(
    val ciphertext: String,
    val iv: String,
)

interface ApiKeyCipher {
    fun encrypt(plaintext: String): EncryptedSecret

    fun decrypt(secret: EncryptedSecret): String
}

interface ProviderSettingsRepository {
    suspend fun loadSummary(): ProviderSettingsSummary

    suspend fun save(input: ProviderSettingsInput): ProviderSettingsSummary

    suspend fun clearApiKey(): ProviderSettingsSummary

    suspend fun loadRuntimeConfig(): OpenAiProviderConfig
}

class ProviderSettingsIncompleteException(message: String) :
    IllegalStateException(message)

class ProviderSecretException(message: String) : IllegalStateException(message)

class AndroidKeystoreApiKeyCipher(
    private val keyAlias: String = KEY_ALIAS,
) : ApiKeyCipher {
    override fun encrypt(plaintext: String): EncryptedSecret {
        require(plaintext.isNotBlank()) { "API key must not be empty" }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            EncryptedSecret(
                ciphertext = Base64.encodeToString(
                    cipher.doFinal(
                        plaintext.toByteArray(StandardCharsets.UTF_8),
                    ),
                    Base64.NO_WRAP,
                ),
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            )
        } catch (error: GeneralSecurityException) {
            throw ProviderSecretException("Failed to encrypt provider API key")
        }
    }

    override fun decrypt(secret: EncryptedSecret): String =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(
                    GCM_TAG_LENGTH_BITS,
                    Base64.decode(secret.iv, Base64.NO_WRAP),
                ),
            )
            String(
                cipher.doFinal(
                    Base64.decode(secret.ciphertext, Base64.NO_WRAP),
                ),
                StandardCharsets.UTF_8,
            )
        } catch (error: GeneralSecurityException) {
            throw ProviderSecretException("Failed to decrypt provider API key")
        } catch (error: IllegalArgumentException) {
            throw ProviderSecretException("Stored provider API key is invalid")
        }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "mochi_provider_api_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

class DataStoreProviderSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val apiKeyCipher: ApiKeyCipher,
) : ProviderSettingsRepository {
    override suspend fun loadSummary(): ProviderSettingsSummary =
        dataStore.data.first().toSummary()

    override suspend fun save(
        input: ProviderSettingsInput,
    ): ProviderSettingsSummary {
        val endpoint = input.endpoint.trim()
        val model = input.model.trim()
        val apiVersion = input.apiVersion.trim()
        require(endpoint.isNotEmpty()) { "Provider endpoint must not be empty" }
        require(model.isNotEmpty()) { "Provider model must not be empty" }
        if (input.providerType == ProviderType.AZURE_OPENAI) {
            require(apiVersion.isNotEmpty()) {
                "Azure OpenAI API version must not be empty"
            }
        }
        require(input.timeoutSeconds in 1..300) {
            "Provider timeout must be between 1 and 300 seconds"
        }
        require(input.maxResponseBytes in 1..10L * 1024L * 1024L) {
            "Provider response limit must be between 1 byte and 10 MiB"
        }
        val replacement = input.apiKeyReplacement
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(apiKeyCipher::encrypt)

        dataStore.edit { preferences ->
            preferences[ENDPOINT] = endpoint
            preferences[MODEL] = model
            preferences[PROVIDER_TYPE] = input.providerType.name
            preferences[API_VERSION] = apiVersion
            preferences[TIMEOUT_SECONDS] = input.timeoutSeconds
            preferences[MAX_RESPONSE_BYTES] = input.maxResponseBytes
            replacement?.let {
                preferences[API_KEY_CIPHERTEXT] = it.ciphertext
                preferences[API_KEY_IV] = it.iv
            }
        }
        return loadSummary()
    }

    override suspend fun clearApiKey(): ProviderSettingsSummary {
        dataStore.edit { preferences ->
            preferences.remove(API_KEY_CIPHERTEXT)
            preferences.remove(API_KEY_IV)
        }
        return loadSummary()
    }

    override suspend fun loadRuntimeConfig(): OpenAiProviderConfig {
        val preferences = dataStore.data.first()
        val summary = preferences.toSummary()
        if (!summary.isReady) {
            throw ProviderSettingsIncompleteException(
                "Complete endpoint, model, and API key in Settings",
            )
        }
        val ciphertext = preferences[API_KEY_CIPHERTEXT]
            ?: throw ProviderSettingsIncompleteException("Provider API key is missing")
        val iv = preferences[API_KEY_IV]
            ?: throw ProviderSettingsIncompleteException("Provider API key IV is missing")
        return OpenAiProviderConfig(
            providerType = summary.providerType,
            endpoint = summary.endpoint,
            apiKey = apiKeyCipher.decrypt(
                EncryptedSecret(ciphertext = ciphertext, iv = iv),
            ),
            model = summary.model,
            apiVersion = summary.apiVersion,
            timeoutSeconds = summary.timeoutSeconds.toLong(),
            maxResponseBytes = summary.maxResponseBytes,
        )
    }

    private fun Preferences.toSummary(): ProviderSettingsSummary =
        ProviderSettingsSummary(
            providerType = this[PROVIDER_TYPE]
                ?.let { stored ->
                    ProviderType.entries.firstOrNull { it.name == stored }
                }
                ?: ProviderType.CUSTOM,
            endpoint = this[ENDPOINT].orEmpty(),
            model = this[MODEL].orEmpty(),
            apiVersion = this[API_VERSION] ?: DEFAULT_AZURE_API_VERSION,
            timeoutSeconds = this[TIMEOUT_SECONDS]
                ?: DEFAULT_PROVIDER_TIMEOUT_SECONDS,
            maxResponseBytes = this[MAX_RESPONSE_BYTES]
                ?: DEFAULT_PROVIDER_MAX_RESPONSE_BYTES,
            hasApiKey =
                !this[API_KEY_CIPHERTEXT].isNullOrBlank() &&
                    !this[API_KEY_IV].isNullOrBlank(),
        )

    private companion object {
        val ENDPOINT = stringPreferencesKey("provider.endpoint")
        val MODEL = stringPreferencesKey("provider.model")
        val PROVIDER_TYPE = stringPreferencesKey("provider.type")
        val API_VERSION = stringPreferencesKey("provider.api_version")
        val TIMEOUT_SECONDS = intPreferencesKey("provider.timeout_seconds")
        val MAX_RESPONSE_BYTES = longPreferencesKey("provider.max_response_bytes")
        val API_KEY_CIPHERTEXT =
            stringPreferencesKey("provider.api_key_ciphertext")
        val API_KEY_IV = stringPreferencesKey("provider.api_key_iv")
    }
}

const val DEFAULT_PROVIDER_TIMEOUT_SECONDS = 60
const val DEFAULT_PROVIDER_MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
