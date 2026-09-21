package com.example.mochi_pet.platform.voice

import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import com.example.mochi_pet.core.voice.SpeechVoice
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class AzureSpeechVoices(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun load(config: SpeechRuntimeConfig.Azure): List<SpeechVoice> =
        suspendCancellableCoroutine { continuation ->
            val synthesisUrl = azureSynthesisUrl(config.endpoint)
            val url = synthesisUrl.newBuilder().encodedPath(
                synthesisUrl.encodedPath.removeSuffix("/v1") + "/voices/list",
            ).build()
            val call = client.newCall(
                Request.Builder().url(url)
                    .header("Ocp-Apim-Subscription-Key", config.apiKey)
                    .build(),
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException("Unable to load voices. Check the saved connection and try again."),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val voices = response.use {
                            if (!it.isSuccessful) throw IOException("Unable to load voices. Check the saved connection and try again.")
                            val source = it.body.source()
                            if (source.request(MAX_VOICE_CATALOG_BYTES + 1)) {
                                throw IOException("Voice catalog is invalid or too large")
                            }
                            parseAzureVoices(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(voices)
                    } catch (_: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IOException("Unable to load voices. Check the saved connection and try again."),
                        )
                    }
                }
            })
        }
}

private const val MAX_VOICE_CATALOG_BYTES = 2_097_152L

@Serializable
private data class AzureVoiceDescription(
    @SerialName("ShortName") val id: String,
    @SerialName("LocalName") val name: String = "",
    @SerialName("Locale") val locale: String,
    @SerialName("VoiceType") val type: String = "",
)

internal fun parseAzureVoices(text: String): List<SpeechVoice> {
    try {
        val voices = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<AzureVoiceDescription>>(text)
        if (voices.isEmpty() || voices.size > 2_000 || voices.any {
                !it.id.matches(Regex("[A-Za-z0-9_:-]{1,100}")) ||
                    !it.locale.matches(Regex("[A-Za-z0-9-]{2,40}")) ||
                    it.name.length > 160 || it.type.length > 60
            }
        ) throw IOException("Voice catalog is invalid or too large")
        return voices.distinctBy { it.id }.map {
            SpeechVoice(it.id, it.name.ifBlank { it.id }, it.locale, it.type)
        }.sortedWith(compareBy({ it.category != "Neural" }, { it.id.contains(':') }, { it.name }))
    } catch (_: SerializationException) {
        throw IOException("Voice catalog is invalid or too large")
    }
}
