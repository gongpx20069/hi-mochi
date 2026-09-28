package com.example.mochi_pet.core.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.agent.llm.DEFAULT_AZURE_API_VERSION
import com.example.mochi_pet.core.agent.llm.DEFAULT_MULTIMODAL_INPUT_ENABLED
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ProviderSettingsSummary(
    val providerType: ProviderType = ProviderType.CUSTOM,
    val endpoint: String = "",
    val model: String = "",
    val apiVersion: String = DEFAULT_AZURE_API_VERSION,
    val timeoutSeconds: Int = DEFAULT_PROVIDER_TIMEOUT_SECONDS,
    val maxResponseBytes: Long = DEFAULT_PROVIDER_MAX_RESPONSE_BYTES,
    val imageInputEnabled: Boolean = DEFAULT_MULTIMODAL_INPUT_ENABLED,
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
    val imageInputEnabled: Boolean = DEFAULT_MULTIMODAL_INPUT_ENABLED,
    val apiKeyReplacement: String? = null,
)

internal fun ProviderSettingsInput.validate() {
    require(endpoint.trim().isNotEmpty()) {
        "Provider endpoint must not be empty"
    }
    require(model.trim().isNotEmpty()) {
        "Provider model must not be empty"
    }
    val url = endpoint.trim().toHttpUrlOrNull()
    require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
        "Use an absolute HTTP(S) endpoint without embedded credentials or fragments."
    }
    if (providerType == ProviderType.AZURE_OPENAI) {
        require(apiVersion.trim().isNotEmpty()) {
            "Azure OpenAI API version must not be empty"
        }
    }
    require(timeoutSeconds in 1..300) {
        "Provider timeout must be between 1 and 300 seconds"
    }
    require(maxResponseBytes in 1..10L * 1024L * 1024L) {
        "Provider response limit must be between 1 byte and 10 MiB"
    }
}

@Serializable
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

    suspend fun loadProfiles(): ProviderProfilesSummary

    suspend fun saveProfile(input: ProviderProfileInput): ProviderProfilesSummary

    suspend fun activateProfile(id: String): ProviderProfilesSummary

    suspend fun deleteProfile(id: String): ProviderProfilesSummary

    suspend fun importProfiles(inputs: List<ProviderProfileInput>, activeIndex: Int): ProviderProfilesSummary

    suspend fun loadProfileRuntimeConfig(id: String): OpenAiProviderConfig
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
    private val runs: ProviderRunCoordinator = ProviderRunCoordinator(),
) : ProviderSettingsRepository {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun loadSummary(): ProviderSettingsSummary =
        loadProfiles().active?.settings ?: ProviderSettingsSummary()

    override suspend fun loadProfiles(): ProviderProfilesSummary = readProfiles().summary()

    override suspend fun save(
        input: ProviderSettingsInput,
    ): ProviderSettingsSummary = runs.change { cancelRuns ->
        val catalog = readProfiles()
        val old = catalog.profiles.firstOrNull { it.id == catalog.activeId }
        val preset = when (input.providerType) {
            ProviderType.OPENAI -> ProviderPreset.OPENAI
            ProviderType.AZURE_OPENAI -> ProviderPreset.AZURE
            ProviderType.CUSTOM -> old?.preset?.takeIf { it.protocol == input.providerType } ?: ProviderPreset.CUSTOM
        }
        val saved = makeProfile(ProviderProfileInput(old?.id, old?.name ?: preset.title, preset, input), old)
        cancelRuns()
        writeProfiles(catalog.copy(
            activeId = saved.id,
            profiles = catalog.profiles.filterNot { it.id == saved.id } + saved,
        ))
        saved.summary().settings
    }

    override suspend fun saveProfile(input: ProviderProfileInput): ProviderProfilesSummary = runs.change { cancelRuns ->
        val catalog = readProfiles()
        val old = input.id?.let { id -> catalog.profiles.firstOrNull { it.id == id } }
        require(input.id == null || old != null) { "Saved AI connection no longer exists." }
        val saved = makeProfile(input, old)
        val active = if (input.useAfterSave || catalog.profiles.isEmpty()) saved.id else catalog.activeId
        if (active != catalog.activeId ||
            (saved.id == catalog.activeId && old != null && saved.copy(name = old.name) != old)
        ) cancelRuns()
        val updated = catalog.copy(
            activeId = active,
            profiles = if (old == null) catalog.profiles + saved else catalog.profiles.map {
                if (it.id == saved.id) saved else it
            },
        )
        writeProfiles(updated)
        updated.summary()
    }

    override suspend fun activateProfile(id: String): ProviderProfilesSummary = runs.change { cancelRuns ->
        val catalog = readProfiles()
        val selected = catalog.profiles.firstOrNull { it.id == id }
        require(selected?.summary()?.settings?.isReady == true) { "Complete this AI connection before using it." }
        if (catalog.activeId != id) cancelRuns()
        val updated = catalog.copy(activeId = id)
        writeProfiles(updated)
        updated.summary()
    }

    override suspend fun deleteProfile(id: String): ProviderProfilesSummary = runs.change { cancelRuns ->
        val catalog = readProfiles()
        require(catalog.profiles.any { it.id == id }) { "Saved AI connection no longer exists." }
        if (catalog.activeId == id) cancelRuns()
        val updated = catalog.copy(
            profiles = catalog.profiles.filterNot { it.id == id },
            activeId = catalog.activeId.takeUnless { it == id },
        )
        writeProfiles(updated)
        updated.summary()
    }

    override suspend fun importProfiles(
        inputs: List<ProviderProfileInput>,
        activeIndex: Int,
    ): ProviderProfilesSummary = runs.change { cancelRuns ->
        require(inputs.isNotEmpty() && activeIndex in inputs.indices) { "Shared AI connection selection is invalid." }
        val imported = inputs.map {
            require(!it.settings.apiKeyReplacement.isNullOrBlank()) { "Shared LLM API key is required" }
            makeProfile(it.copy(id = null), null)
        }
        val catalog = readProfiles()
        cancelRuns()
        val updated = catalog.copy(
            profiles = catalog.profiles + imported,
            activeId = imported[activeIndex].id,
        )
        writeProfiles(updated)
        updated.summary()
    }

    private fun makeProfile(input: ProviderProfileInput, old: StoredProviderProfile?): StoredProviderProfile {
        require(input.name.trim().isNotEmpty() && input.name.trim().length <= 120) {
            "Name the AI connection using 1-120 characters."
        }
        val settings = input.settings
        require(input.preset.protocol == settings.providerType) { "AI provider preset and protocol do not match." }
        settings.validate()
        val replacement = settings.apiKeyReplacement?.trim()?.takeIf(String::isNotEmpty)
        if (old?.key != null && replacement == null) {
            require(old.providerType == settings.providerType &&
                old.endpoint.toHttpUrlOrNull()?.host == settings.endpoint.trim().toHttpUrlOrNull()?.host) {
                "Changing provider or endpoint host requires a new API key."
            }
        }
        return StoredProviderProfile(
            id = old?.id ?: UUID.randomUUID().toString(),
            name = input.name.trim(), preset = input.preset, providerType = settings.providerType,
            endpoint = settings.endpoint.trim(), model = settings.model.trim(),
            apiVersion = settings.apiVersion.trim(), timeoutSeconds = settings.timeoutSeconds,
            maxResponseBytes = settings.maxResponseBytes, imageInputEnabled = settings.imageInputEnabled,
            key = replacement?.let(apiKeyCipher::encrypt) ?: old?.key,
        )
    }

    override suspend fun clearApiKey(): ProviderSettingsSummary = runs.change { cancelRuns ->
        val catalog = readProfiles()
        cancelRuns()
        writeProfiles(catalog.copy(profiles = catalog.profiles.map {
            if (it.id == catalog.activeId) it.copy(key = null) else it
        }))
        loadSummary()
    }

    override suspend fun loadRuntimeConfig(): OpenAiProviderConfig {
        val catalog = readProfiles()
        return runtimeConfig(catalog.profiles.firstOrNull { it.id == catalog.activeId })
    }

    override suspend fun loadProfileRuntimeConfig(id: String): OpenAiProviderConfig =
        runtimeConfig(readProfiles().profiles.firstOrNull { it.id == id })

    private fun runtimeConfig(profile: StoredProviderProfile?): OpenAiProviderConfig {
        val summary = profile?.summary()?.settings
        val key = profile?.key
        if (summary == null || !summary.isReady || key == null) {
            throw ProviderSettingsIncompleteException("Complete endpoint, model, and API key in Settings")
        }
        return OpenAiProviderConfig(
            providerType = summary.providerType, endpoint = summary.endpoint,
            apiKey = apiKeyCipher.decrypt(key), model = summary.model,
            apiVersion = summary.apiVersion, timeoutSeconds = summary.timeoutSeconds.toLong(),
            maxResponseBytes = summary.maxResponseBytes, imageInputEnabled = summary.imageInputEnabled,
        )
    }

    private suspend fun readProfiles(): StoredProviderProfiles {
        dataStore.edit { preferences ->
            if (preferences[PROFILES] == null) {
                require(preferences[API_KEY_CIPHERTEXT].isNullOrBlank() == preferences[API_KEY_IV].isNullOrBlank()) {
                    "Stored provider API key is incomplete."
                }
                val legacy = preferences.toSummary()
                val present = legacy.endpoint.isNotBlank() || legacy.model.isNotBlank() || legacy.hasApiKey
                val preset = when (legacy.providerType) {
                    ProviderType.OPENAI -> ProviderPreset.OPENAI
                    ProviderType.AZURE_OPENAI -> ProviderPreset.AZURE
                    ProviderType.CUSTOM -> ProviderPreset.CUSTOM
                }
                val profile = StoredProviderProfile(
                    id = "migrated-connection", name = preset.title, preset = preset,
                    providerType = legacy.providerType, endpoint = legacy.endpoint, model = legacy.model,
                    apiVersion = legacy.apiVersion, timeoutSeconds = legacy.timeoutSeconds,
                    maxResponseBytes = legacy.maxResponseBytes, imageInputEnabled = legacy.imageInputEnabled,
                    key = if (legacy.hasApiKey) EncryptedSecret(
                        preferences[API_KEY_CIPHERTEXT]!!, preferences[API_KEY_IV]!!,
                    ) else null,
                )
                preferences[PROFILES] = json.encodeToString(StoredProviderProfiles(
                    profiles = if (present) listOf(profile) else emptyList(),
                    activeId = if (present) profile.id else null,
                ))
                listOf(ENDPOINT, MODEL, PROVIDER_TYPE, API_VERSION, API_KEY_CIPHERTEXT, API_KEY_IV)
                    .forEach { preferences.remove(it) }
                preferences.remove(TIMEOUT_SECONDS)
                preferences.remove(MAX_RESPONSE_BYTES)
                preferences.remove(IMAGE_INPUT_ENABLED)
            }
        }
        return try {
            json.decodeFromString<StoredProviderProfiles>(dataStore.data.first()[PROFILES]!!)
                .also {
                    check(it.version == 1 && it.profiles.map { profile -> profile.id }.distinct().size == it.profiles.size &&
                        (it.activeId == null || it.profiles.any { profile -> profile.id == it.activeId })) {
                        "Stored AI connections are invalid."
                    }
                }
        } catch (_: SerializationException) {
            throw ProviderSecretException("Stored AI connections are invalid.")
        }
    }

    private suspend fun writeProfiles(profiles: StoredProviderProfiles) {
        dataStore.edit { it[PROFILES] = json.encodeToString(profiles) }
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
            imageInputEnabled = this[IMAGE_INPUT_ENABLED]
                ?: DEFAULT_MULTIMODAL_INPUT_ENABLED,
            hasApiKey =
                !this[API_KEY_CIPHERTEXT].isNullOrBlank() &&
                    !this[API_KEY_IV].isNullOrBlank(),
        )

    private companion object {
        val PROFILES = stringPreferencesKey("provider.profiles.v1")
        val ENDPOINT = stringPreferencesKey("provider.endpoint")
        val MODEL = stringPreferencesKey("provider.model")
        val PROVIDER_TYPE = stringPreferencesKey("provider.type")
        val API_VERSION = stringPreferencesKey("provider.api_version")
        val TIMEOUT_SECONDS = intPreferencesKey("provider.timeout_seconds")
        val MAX_RESPONSE_BYTES = longPreferencesKey("provider.max_response_bytes")
        val IMAGE_INPUT_ENABLED =
            booleanPreferencesKey("provider.image_input_enabled")
        val API_KEY_CIPHERTEXT =
            stringPreferencesKey("provider.api_key_ciphertext")
        val API_KEY_IV = stringPreferencesKey("provider.api_key_iv")
    }

    @Serializable
    private data class StoredProviderProfiles(
        val version: Int = 1,
        val profiles: List<StoredProviderProfile> = emptyList(),
        val activeId: String? = null,
    ) {
        fun summary() = ProviderProfilesSummary(profiles.map { it.summary() }, activeId)
    }

    @Serializable
    private data class StoredProviderProfile(
        val id: String,
        val name: String,
        val preset: ProviderPreset,
        val providerType: ProviderType,
        val endpoint: String,
        val model: String,
        val apiVersion: String,
        val timeoutSeconds: Int,
        val maxResponseBytes: Long,
        val imageInputEnabled: Boolean,
        val key: EncryptedSecret?,
    ) {
        fun summary() = ProviderProfileSummary(id, name, preset, ProviderSettingsSummary(
            providerType, endpoint, model, apiVersion, timeoutSeconds, maxResponseBytes,
            imageInputEnabled, key != null,
        ))
    }
}

const val DEFAULT_PROVIDER_TIMEOUT_SECONDS = 60
const val DEFAULT_PROVIDER_MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
