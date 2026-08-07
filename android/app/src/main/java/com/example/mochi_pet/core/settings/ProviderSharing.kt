package com.example.mochi_pet.core.settings

import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.ProviderType
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
    val version: Int = 1,
    val llm: SharedLlmProvider,
    val speech: SharedSpeechProvider,
)

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
) {
    suspend fun createShareLink(): String {
        val llm = providerRepository.loadRuntimeConfig()
        val speech = speechRepository.loadRuntimeConfig()
        return ProviderShareCodec.encode(
            SharedProviderBundle(
                llm = llm.toShared(),
                speech = speech.toShared(),
            ),
        )
    }

    suspend fun importShareLink(link: String) {
        val bundle = ProviderShareCodec.decode(link)
        require(bundle.version == 1) {
            "This Provider share version is not supported"
        }
        providerRepository.save(
            ProviderSettingsInput(
                providerType = bundle.llm.providerType,
                endpoint = bundle.llm.endpoint,
                model = bundle.llm.model,
                apiVersion = bundle.llm.apiVersion,
                timeoutSeconds = bundle.llm.timeoutSeconds.toInt(),
                maxResponseBytes = bundle.llm.maxResponseBytes,
                apiKeyReplacement = bundle.llm.apiKey,
            ),
        )
        val speech = bundle.speech
        speechRepository.save(
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
            ),
        )
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
        return "$LINK_PREFIX${key.urlBase64()}.${iv.urlBase64()}." +
            ciphertext.urlBase64()
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

    private const val LINK_PREFIX = "mochi://provider/import#v1."
    private const val MAX_LINK_CHARS = 16_384
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val AAD = "mochi-provider-share-v1"
        .toByteArray(StandardCharsets.UTF_8)
}
