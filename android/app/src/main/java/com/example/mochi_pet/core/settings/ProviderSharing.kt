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
    val version: Int = 2,
    val llm: SharedLlmProvider? = null,
    val speech: SharedSpeechProvider? = null,
    val tools: SharedToolProviders = SharedToolProviders(),
)

data class ProviderShareSelection(
    val includeLlm: Boolean = true,
    val includeSpeech: Boolean = true,
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
)

@Serializable
internal data class SharedSpeechProvider(
    val provider: SpeechProvider,
    val iFlytekAppId: String = "",
    val iFlytekApiKey: String = "",
    val iFlytekApiSecret: String = "",
    val azureEndpoint: String = "",
    val azureApiKey: String = "",
)

class ProviderShareManager(
    private val providerRepository: ProviderSettingsRepository,
    private val speechRepository: SpeechSettingsRepository,
    private val toolCatalogRepository: ToolCatalogRepository,
) {
    suspend fun createShareLink(selection: ProviderShareSelection): String {
        require(!selection.isEmpty) {
            "Select at least one Provider or Tool connection"
        }
        return ProviderShareCodec.encode(
            SharedProviderBundle(
                llm = if (selection.includeLlm) {
                    providerRepository.loadRuntimeConfig().toShared()
                } else {
                    null
                },
                speech = if (selection.includeSpeech) {
                    speechRepository.loadRuntimeConfig().toShared()
                } else {
                    null
                },
                tools = toolCatalogRepository.exportSharedTools(
                    selection.tools,
                ),
            ),
        )
    }

    suspend fun importShareLink(link: String) {
        val bundle = ProviderShareCodec.decode(link)
        require(bundle.version == 2) {
            "This Provider share version is not supported"
        }
        require(
            bundle.llm != null ||
                bundle.speech != null ||
                bundle.tools != SharedToolProviders(),
        ) {
            "Provider share link does not contain any connections"
        }
        val providerInput = bundle.llm?.let { llm ->
            require(llm.apiKey.isNotBlank()) {
                "Shared LLM API key is required"
            }
            require(llm.timeoutSeconds in 1..300) {
                "Shared Provider timeout is invalid"
            }
            ProviderSettingsInput(
                providerType = llm.providerType,
                endpoint = llm.endpoint,
                model = llm.model,
                apiVersion = llm.apiVersion,
                timeoutSeconds = llm.timeoutSeconds.toInt(),
                maxResponseBytes = llm.maxResponseBytes,
                apiKeyReplacement = llm.apiKey,
            )
        }
        providerInput?.validate()
        val speechInput = bundle.speech?.let { speech ->
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
            SpeechSettingsInput(
                provider = speech.provider,
                iFlytekAppId = speech.iFlytekAppId,
                iFlytekApiKeyReplacement =
                    speech.iFlytekApiKey.takeIf(String::isNotBlank),
                iFlytekApiSecretReplacement =
                    speech.iFlytekApiSecret.takeIf(String::isNotBlank),
                azureEndpoint = speech.azureEndpoint,
                azureApiKeyReplacement =
                    speech.azureApiKey.takeIf(String::isNotBlank),
            )
        }
        speechInput?.validate()
        val preparedTools =
            toolCatalogRepository.prepareSharedTools(bundle.tools)

        providerInput?.let { providerRepository.save(it) }
        speechInput?.let { speechRepository.save(it) }
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
        )

    private fun SpeechRuntimeConfig.toShared(): SharedSpeechProvider =
        when (this) {
            SpeechRuntimeConfig.System ->
                SharedSpeechProvider(provider = SpeechProvider.SYSTEM)
            is SpeechRuntimeConfig.IFlytek -> SharedSpeechProvider(
                provider = SpeechProvider.IFLYTEK,
                iFlytekAppId = appId,
                iFlytekApiKey = apiKey,
                iFlytekApiSecret = apiSecret,
            )
            is SpeechRuntimeConfig.Azure -> SharedSpeechProvider(
                provider = SpeechProvider.AZURE,
                azureEndpoint = endpoint,
                azureApiKey = apiKey,
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
                updateAAD(AAD)
                doFinal(plaintext)
            }
        } catch (error: GeneralSecurityException) {
            throw ProviderShareException(
                "Provider share link could not be encrypted",
            )
        }
        val link = "$LINK_PREFIX${key.urlBase64()}.${iv.urlBase64()}." +
            ciphertext.urlBase64()
        if (link.length > MAX_LINK_CHARS) {
            throw ProviderShareException(
                "Selected Provider share is too large; share fewer Tool connections",
            )
        }
        return link
    }

    fun decode(link: String): SharedProviderBundle {
        require(link.startsWith(LINK_PREFIX)) {
            "This is not a Mochi Provider share link"
        }
        require(link.length <= MAX_LINK_CHARS) {
            "Provider share link is too large"
        }
        val parts = link.removePrefix(LINK_PREFIX).split('.')
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
                updateAAD(AAD)
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
            json.decodeFromString(
                plaintext.toString(StandardCharsets.UTF_8),
            )
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

    private const val LINK_PREFIX = "mochi://provider/import#v2."
    private const val MAX_LINK_CHARS = 32_768
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val AAD = "mochi-provider-share-v2"
        .toByteArray(StandardCharsets.UTF_8)
}
