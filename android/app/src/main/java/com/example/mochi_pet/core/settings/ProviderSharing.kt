package com.example.mochi_pet.core.settings

import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.ProviderType
import com.example.mochi_pet.core.tools.SharedToolProviders
import com.example.mochi_pet.core.tools.ToolCatalogRepository
import com.example.mochi_pet.core.tools.ToolShareSelection
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProviderShareException(message: String) : IllegalArgumentException(message)

@Serializable
internal data class SharedProviderBundle(
    val version: Int = 4,
    val llm: SharedLlmProvider? = null,
    val llmProfiles: List<SharedLlmProfile> = emptyList(),
    val activeLlmIndex: Int = 0,
    val speech: SharedSpeechProvider? = null,
    val speechProfiles: List<SharedSpeechProfile> = emptyList(),
    val activeSpeechIndex: Int = 0,
    val tools: SharedToolProviders = SharedToolProviders(),
)

data class ProviderShareSelection(
    val includeLlm: Boolean = true,
    val llmProfileIds: Set<String>? = null,
    val includeSpeech: Boolean = true,
    val speechProfileIds: Set<String>? = null,
    val tools: ToolShareSelection = ToolShareSelection(),
) {
    val isEmpty: Boolean
        get() = !includeLlm &&
            !includeSpeech &&
            !tools.includeAmap &&
            !tools.includeTencentDocs &&
            tools.manualMcpServerIds.isEmpty()
}

@Serializable
internal data class SharedLlmProvider(
    val providerType: ProviderType,
    val endpoint: String,
    val model: String,
    val apiVersion: String,
    val timeoutSeconds: Long,
    val maxResponseBytes: Long,
    val apiKey: String,
    val imageInputEnabled: Boolean = true,
)

@Serializable
internal data class SharedLlmProfile(
    val name: String,
    val preset: ProviderPreset,
    val config: SharedLlmProvider,
)

@Serializable
internal data class SharedSpeechProvider(
    val provider: SpeechProvider,
    val iFlytekAppId: String = "",
    val iFlytekApiKey: String = "",
    val iFlytekApiSecret: String = "",
    val azureEndpoint: String = "",
    val azureApiKey: String = "",
    val synthesisEnabled: Boolean = false,
    val iFlytekVoice: String = "",
    val azureVoice: String = "",
)

@Serializable
internal data class SharedSpeechProfile(val name: String, val config: SharedSpeechProvider)

class ProviderShareManager(
    private val providerRepository: ProviderSettingsRepository,
    private val speechRepository: SpeechSettingsRepository,
    private val toolCatalogRepository: ToolCatalogRepository,
) {
    suspend fun createShareLink(selection: ProviderShareSelection): String {
        require(!selection.isEmpty) {
            "Select at least one Provider or Tool connection"
        }
        val profiles = providerRepository.loadProfiles()
        val selectedIds = if (selection.includeLlm) {
            selection.llmProfileIds ?: setOfNotNull(profiles.activeId)
        } else emptySet()
        require(!selection.includeLlm || selectedIds.isNotEmpty()) { "Select at least one saved AI connection." }
        val selected = profiles.profiles.filter { it.id in selectedIds }
        require(selected.size == selectedIds.size && selected.all { it.settings.isReady }) {
            "A selected AI connection is missing or incomplete."
        }
        val sharedProfiles = selected.map {
            SharedLlmProfile(it.name, it.preset, providerRepository.loadProfileRuntimeConfig(it.id).toShared())
        }
        require(providerRepository.loadProfiles() == profiles) {
            "AI connections changed while preparing the share. Select them again."
        }
        val speechProfiles = speechRepository.loadProfiles()
        val speechIds = if (selection.includeSpeech) {
            selection.speechProfileIds ?: setOf(speechProfiles.activeId)
        } else emptySet()
        require(!selection.includeSpeech || speechIds.isNotEmpty()) { "Select at least one saved speech connection." }
        val selectedSpeech = speechProfiles.profiles.filter { it.id in speechIds }
        require(selectedSpeech.size == speechIds.size && selectedSpeech.all { it.settings.isReady }) {
            "A selected speech connection is missing or incomplete."
        }
        val sharedSpeech = selectedSpeech.map {
            SharedSpeechProfile(it.name, speechRepository.loadProfileRuntimeConfig(it.id).toShared())
        }
        require(speechRepository.loadProfiles() == speechProfiles) { "Speech connections changed while preparing the share. Select them again." }
        return ProviderShareCodec.encode(
            SharedProviderBundle(
                llmProfiles = sharedProfiles,
                activeLlmIndex = selected.indexOfFirst { it.id == profiles.activeId }.coerceAtLeast(0),
                speechProfiles = sharedSpeech,
                activeSpeechIndex = selectedSpeech.indexOfFirst { it.id == speechProfiles.activeId }.coerceAtLeast(0),
                tools = toolCatalogRepository.exportSharedTools(
                    selection.tools,
                ),
            ),
        )
    }

    suspend fun importShareLink(link: String) {
        val bundle = ProviderShareCodec.decode(link)
        require(bundle.version in 2..4) {
            "This Provider share version is not supported"
        }
        require(
            bundle.llm != null ||
                bundle.llmProfiles.isNotEmpty() ||
                bundle.speech != null ||
                bundle.speechProfiles.isNotEmpty() ||
                bundle.tools != SharedToolProviders(),
        ) {
            "Provider share link does not contain any connections"
        }
        require(bundle.llm == null || bundle.llmProfiles.isEmpty()) { "Provider share contains conflicting AI connections." }
        val sharedProfiles = bundle.llmProfiles.ifEmpty {
            bundle.llm?.let {
                val preset = when (it.providerType) {
                    ProviderType.OPENAI -> ProviderPreset.OPENAI
                    ProviderType.AZURE_OPENAI -> ProviderPreset.AZURE
                    ProviderType.CUSTOM -> ProviderPreset.CUSTOM
                }
                listOf(SharedLlmProfile(preset.title, preset, it))
            }.orEmpty()
        }
        require(sharedProfiles.isEmpty() || bundle.activeLlmIndex in sharedProfiles.indices) {
            "Shared AI connection selection is invalid."
        }
        val providerInputs = sharedProfiles.map { profile ->
            val llm = profile.config
            require(profile.name.isNotBlank() && profile.name.length <= 120 &&
                profile.preset.protocol == llm.providerType) { "Shared AI connection details are invalid." }
            require(llm.apiKey.isNotBlank()) {
                "Shared LLM API key is required"
            }
            require(llm.timeoutSeconds in 1..300) {
                "Shared Provider timeout is invalid"
            }
            ProviderProfileInput(name = profile.name, preset = profile.preset, settings = ProviderSettingsInput(
                providerType = llm.providerType,
                endpoint = llm.endpoint,
                model = llm.model,
                apiVersion = llm.apiVersion,
                timeoutSeconds = llm.timeoutSeconds.toInt(),
                maxResponseBytes = llm.maxResponseBytes,
                apiKeyReplacement = llm.apiKey,
                imageInputEnabled = llm.imageInputEnabled,
            ).also { it.validate() })
        }
        require(bundle.speech == null || bundle.speechProfiles.isEmpty()) { "Provider share contains conflicting speech connections." }
        val sharedSpeech = bundle.speechProfiles.ifEmpty {
            bundle.speech?.let { listOf(SharedSpeechProfile(it.provider.connectionTitle, it)) }.orEmpty()
        }
        require(sharedSpeech.isEmpty() || bundle.activeSpeechIndex in sharedSpeech.indices) { "Shared speech connection selection is invalid." }
        val speechInputs = sharedSpeech.map { profile ->
            require(profile.name.trim().length in 1..120) { "Name the speech connection using 1-120 characters." }
            val speech = profile.config
            when (speech.provider) {
                SpeechProvider.SYSTEM -> Unit
                SpeechProvider.IFLYTEK -> require(
                    speech.iFlytekApiKey.isNotBlank() &&
                        speech.iFlytekApiSecret.isNotBlank(),
                ) {
                    "Shared iFlytek credentials are incomplete"
                }
                SpeechProvider.AZURE -> require(
                    speech.azureApiKey.isNotBlank(),
                ) {
                    "Shared Azure Speech API key is required"
                }
            }
            SpeechProfileInput(name = profile.name, settings = SpeechSettingsInput(
                provider = speech.provider,
                iFlytekAppId = speech.iFlytekAppId,
                iFlytekApiKeyReplacement =
                    speech.iFlytekApiKey.takeIf(String::isNotBlank),
                iFlytekApiSecretReplacement =
                    speech.iFlytekApiSecret.takeIf(String::isNotBlank),
                azureEndpoint = speech.azureEndpoint,
                azureApiKeyReplacement =
                    speech.azureApiKey.takeIf(String::isNotBlank),
                synthesisEnabled = speech.synthesisEnabled,
                iFlytekVoice = speech.iFlytekVoice.takeIf { speech.provider == SpeechProvider.IFLYTEK },
                azureVoice = speech.azureVoice.takeIf { speech.provider == SpeechProvider.AZURE },
            ).also { it.validate() })
        }
        val preparedTools =
            toolCatalogRepository.prepareSharedTools(bundle.tools)

        if (providerInputs.isNotEmpty()) providerRepository.importProfiles(providerInputs, bundle.activeLlmIndex)
        if (speechInputs.isNotEmpty()) speechRepository.importProfiles(speechInputs, bundle.activeSpeechIndex)
        toolCatalogRepository.applySharedTools(preparedTools)
    }

    private fun OpenAiProviderConfig.toShared(): SharedLlmProvider =
        SharedLlmProvider(
            providerType = providerType,
            endpoint = endpoint,
            model = model,
            apiVersion = apiVersion,
            timeoutSeconds = timeoutSeconds,
            maxResponseBytes = maxResponseBytes,
            apiKey = apiKey,
            imageInputEnabled = imageInputEnabled,
        )

    private fun SpeechRuntimeConfig.toShared(): SharedSpeechProvider =
        when (this) {
            is SpeechRuntimeConfig.System ->
                SharedSpeechProvider(provider = SpeechProvider.SYSTEM)
            is SpeechRuntimeConfig.IFlytek -> SharedSpeechProvider(
                provider = SpeechProvider.IFLYTEK,
                iFlytekAppId = appId,
                iFlytekApiKey = apiKey,
                iFlytekApiSecret = apiSecret,
                synthesisEnabled = synthesisEnabled,
                iFlytekVoice = voice,
            )
            is SpeechRuntimeConfig.Azure -> SharedSpeechProvider(
                provider = SpeechProvider.AZURE,
                azureEndpoint = endpoint,
                azureApiKey = apiKey,
                synthesisEnabled = synthesisEnabled,
                azureVoice = voice,
            )
        }
}

internal object ProviderShareCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val random = SecureRandom()

    fun encode(bundle: SharedProviderBundle): String {
        require(bundle.version in 2..4) { "This Provider share version is not supported" }
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val plaintext = json.encodeToString(bundle)
            .toByteArray(StandardCharsets.UTF_8)
        val ciphertext = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, KEY_ALGORITHM),
                    GCMParameterSpec(GCM_TAG_BITS, iv),
                )
                updateAAD(aad(bundle.version))
                doFinal(plaintext)
            }
        } catch (error: GeneralSecurityException) {
            throw ProviderShareException(
                "Provider share link could not be encrypted",
            )
        }
        val link = "${linkPrefix(bundle.version)}${key.urlBase64()}.${iv.urlBase64()}." +
            ciphertext.urlBase64()
        if (link.length > MAX_LINK_CHARS) {
            throw ProviderShareException(
                "Selected Provider share is too large; share fewer connections",
            )
        }
        return link
    }

    fun decode(link: String): SharedProviderBundle {
        val version = listOf(2, 3, 4).firstOrNull { link.startsWith(linkPrefix(it)) }
        require(version != null) {
            "This is not a Mochi Provider share link"
        }
        require(link.length <= MAX_LINK_CHARS) {
            "Provider share link is too large"
        }
        val parts = link.removePrefix(linkPrefix(version)).split('.')
        require(parts.size == 3) {
            "Provider share link is malformed"
        }
        val key = parts[0].decodeUrlBase64()
        val iv = parts[1].decodeUrlBase64()
        val ciphertext = parts[2].decodeUrlBase64()
        require(key.size == KEY_BYTES && iv.size == IV_BYTES) {
            "Provider share link has invalid encryption parameters"
        }
        val plaintext = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, KEY_ALGORITHM),
                    GCMParameterSpec(GCM_TAG_BITS, iv),
                )
                updateAAD(aad(version))
                doFinal(ciphertext)
            }
        } catch (_: AEADBadTagException) {
            throw ProviderShareException(
                "Provider share link is damaged or has been modified",
            )
        } catch (error: GeneralSecurityException) {
            throw ProviderShareException(
                "Provider share link could not be decrypted",
            )
        }
        return try {
            json.decodeFromString<SharedProviderBundle>(
                plaintext.toString(StandardCharsets.UTF_8),
            ).also { require(it.version == version) { "Provider share version does not match its envelope." } }
        } catch (error: SerializationException) {
            throw ProviderShareException(
                "Provider share link contains invalid settings",
            )
        }
    }

    private fun ByteArray.urlBase64(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.decodeUrlBase64(): ByteArray =
        try {
            Base64.getUrlDecoder().decode(this)
        } catch (_: IllegalArgumentException) {
            throw ProviderShareException(
                "Provider share link contains invalid encoding",
            )
        }

    private fun linkPrefix(version: Int) = "mochi://provider/import#v$version."
    private const val MAX_LINK_CHARS = 32_768
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private fun aad(version: Int) = "mochi-provider-share-v$version".toByteArray(StandardCharsets.UTF_8)
}
