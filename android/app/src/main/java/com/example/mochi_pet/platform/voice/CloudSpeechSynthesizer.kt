package com.example.mochi_pet.platform.voice

import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal const val MAX_SYNTHESIS_AUDIO_BYTES = 4 * 1024 * 1024
internal const val SYNTHESIS_SAMPLE_RATE = 16_000

internal enum class SynthesisFailure(val message: String) {
    NETWORK("Cannot connect to the speech synthesis provider."),
    TIMEOUT("Speech synthesis timed out. Please try again."),
    AUTHORIZATION("Speech synthesis authorization failed. Check your provider credentials."),
    QUOTA("Speech synthesis is rate limited or its quota is exhausted."),
    REJECTED("Speech synthesis was rejected. Check service activation and voice access."),
    INVALID_AUDIO("The speech provider returned invalid or oversized audio."),
    PLAYBACK("Speech audio could not be played on this device."),
    SETTINGS("Speech synthesis settings are incomplete or invalid."),
}

internal class SpeechSynthesisException(
    val failure: SynthesisFailure,
    val providerCode: Int? = null,
    val httpStatus: Int? = null,
    val audioFailure: SynthesisAudioFailure? = null,
    val frameStatus: Int? = null,
) : IOException(failure.message) {
    fun safeDiagnostic(): String =
        "failure=${failure.name} providerCode=${providerCode ?: "none"} " +
            "httpStatus=${httpStatus ?: "none"} " +
            "audioFailure=${audioFailure?.name ?: "none"} " +
            "frameStatus=${frameStatus ?: "none"}"
}

internal enum class SynthesisAudioFailure {
    EMPTY,
    ODD_PCM_SIZE,
    SIZE_LIMIT,
    CONTENT_TYPE,
    EARLY_CLOSE,
    INVALID_JSON,
    INVALID_STATUS,
    INVALID_BASE64,
}

internal fun SpeechRuntimeConfig.synthesisProviderName(): String =
    when (this) {
        SpeechRuntimeConfig.System -> "system"
        is SpeechRuntimeConfig.IFlytek -> "iflytek"
        is SpeechRuntimeConfig.Azure -> "azure"
    }

internal fun interface SpeechSynthesizer {
    suspend fun synthesize(
        config: SpeechRuntimeConfig,
        text: String,
        locale: Locale,
    ): ByteArray
}

internal class CloudSpeechSynthesizer(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val clock: Clock = Clock.systemUTC(),
    private val iFlytekEndpoint: HttpUrl =
        "https://tts-api.xfyun.cn/v2/tts".toHttpUrl(),
    private val requestTimeoutMillis: Long = 30_000,
) : SpeechSynthesizer {
    override suspend fun synthesize(
        config: SpeechRuntimeConfig,
        text: String,
        locale: Locale,
    ): ByteArray {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size < 8_000)
        val audio = withTimeoutOrNull(requestTimeoutMillis) {
            when (config) {
                is SpeechRuntimeConfig.IFlytek -> synthesizeIFlytek(config, text)
                is SpeechRuntimeConfig.Azure -> synthesizeAzure(config, text, locale)
                SpeechRuntimeConfig.System -> error("Cloud speech provider is required")
            }
        } ?: throw SpeechSynthesisException(SynthesisFailure.TIMEOUT)
        val audioFailure = when {
            audio.isEmpty() -> SynthesisAudioFailure.EMPTY
            audio.size > MAX_SYNTHESIS_AUDIO_BYTES -> SynthesisAudioFailure.SIZE_LIMIT
            audio.size % 2 != 0 -> SynthesisAudioFailure.ODD_PCM_SIZE
            else -> null
        }
        if (audioFailure != null) {
            throw SpeechSynthesisException(
                SynthesisFailure.INVALID_AUDIO,
                audioFailure = audioFailure,
            )
        }
        return audio
    }

    private suspend fun synthesizeIFlytek(
        config: SpeechRuntimeConfig.IFlytek,
        text: String,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val audio = ByteArrayOutputStream()
        val socket = client.newWebSocket(
            Request.Builder().url(
                iFlytekSignedUrl(
                    config.apiKey,
                    config.apiSecret,
                    clock,
                    iFlytekEndpoint,
                ),
            ).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!continuation.isActive) {
                        webSocket.cancel()
                    } else if (!webSocket.send(iFlytekSynthesisRequest(config, text))) {
                        continuation.resumeWithException(
                            SpeechSynthesisException(SynthesisFailure.NETWORK),
                        )
                        webSocket.cancel()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!continuation.isActive) return
                    try {
                        val frame = parseIFlytekSynthesisFrame(text)
                        if (audio.size() + frame.audio.size > MAX_SYNTHESIS_AUDIO_BYTES) {
                            throw SpeechSynthesisException(
                                SynthesisFailure.INVALID_AUDIO,
                                audioFailure = SynthesisAudioFailure.SIZE_LIMIT,
                            )
                        }
                        audio.write(frame.audio)
                        if (frame.finished) {
                            continuation.resume(audio.toByteArray())
                            webSocket.close(1000, null)
                        }
                    } catch (error: SpeechSynthesisException) {
                        continuation.resumeWithException(error)
                        webSocket.cancel()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechSynthesisException(
                                SynthesisFailure.INVALID_AUDIO,
                                audioFailure = SynthesisAudioFailure.EARLY_CLOSE,
                            ),
                        )
                    }
                    webSocket.close(code, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechSynthesisException(
                                response?.code?.let(::synthesisHttpFailure)
                                    ?: SynthesisFailure.NETWORK,
                                httpStatus = response?.code,
                            ),
                        )
                    }
                }
            },
        )
        continuation.invokeOnCancellation { socket.cancel() }
    }

    private suspend fun synthesizeAzure(
        config: SpeechRuntimeConfig.Azure,
        text: String,
        locale: Locale,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(
            Request.Builder()
                .url(azureSynthesisUrl(config.endpoint))
                .header("Ocp-Apim-Subscription-Key", config.apiKey)
                .header("X-Microsoft-OutputFormat", "raw-16khz-16bit-mono-pcm")
                .header("User-Agent", "Mochi")
                .post(
                    azureSynthesisSsml(text, config.voice, locale)
                        .toRequestBody("application/ssml+xml; charset=utf-8".toMediaType()),
                )
                .build(),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechSynthesisException(SynthesisFailure.NETWORK),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val audio = response.use {
                            if (!it.isSuccessful) {
                                throw SpeechSynthesisException(
                                    synthesisHttpFailure(it.code),
                                    httpStatus = it.code,
                                )
                            }
                            if (it.body.contentLength() > MAX_SYNTHESIS_AUDIO_BYTES) {
                                throw SpeechSynthesisException(
                                    SynthesisFailure.INVALID_AUDIO,
                                    audioFailure = SynthesisAudioFailure.SIZE_LIMIT,
                                )
                            }
                            val mediaType = it.body.contentType()
                            if (
                                mediaType != null &&
                                mediaType.type != "audio" &&
                                mediaType.toString() != "application/octet-stream"
                            ) {
                                throw SpeechSynthesisException(
                                    SynthesisFailure.INVALID_AUDIO,
                                    audioFailure = SynthesisAudioFailure.CONTENT_TYPE,
                                )
                            }
                            val output = ByteArrayOutputStream()
                            it.body.byteStream().use { input ->
                                val buffer = ByteArray(8_192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (output.size() + count > MAX_SYNTHESIS_AUDIO_BYTES) {
                                        throw SpeechSynthesisException(
                                            SynthesisFailure.INVALID_AUDIO,
                                            audioFailure = SynthesisAudioFailure.SIZE_LIMIT,
                                        )
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray()
                        }
                        if (continuation.isActive) continuation.resume(audio)
                    } catch (error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                error as? SpeechSynthesisException
                                    ?: SpeechSynthesisException(SynthesisFailure.NETWORK),
                            )
                        }
                    }
                }
            },
        )
    }
}

internal fun synthesisHttpFailure(code: Int): SynthesisFailure =
    when (code) {
        401, 403 -> SynthesisFailure.AUTHORIZATION
        408, 504 -> SynthesisFailure.TIMEOUT
        429 -> SynthesisFailure.QUOTA
        in 500..599 -> SynthesisFailure.NETWORK
        else -> SynthesisFailure.REJECTED
    }

internal fun iFlytekSynthesisRequest(
    config: SpeechRuntimeConfig.IFlytek,
    text: String,
): String = buildJsonObject {
    put("common", buildJsonObject { put("app_id", config.appId) })
    put(
        "business",
        buildJsonObject {
            put("aue", "raw")
            put("auf", "audio/L16;rate=16000")
            put("vcn", config.voice.ifBlank { "x4_xiaoyan" })
            put("tte", "UTF8")
        },
    )
    put(
        "data",
        buildJsonObject {
            put("status", 2)
            put("text", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        },
    )
}.toString()

@Serializable
private data class IFlytekSynthesisResponse(
    val code: Int,
    val data: JsonElement? = null,
)

@Serializable
private data class IFlytekSynthesisData(
    val status: Int,
    val audio: String? = null,
)

internal data class SynthesisFrame(val audio: ByteArray, val finished: Boolean)

private val synthesisJson = Json { ignoreUnknownKeys = true }

internal fun parseIFlytekSynthesisFrame(text: String): SynthesisFrame {
    if (text.length > MAX_SYNTHESIS_AUDIO_BYTES * 2) {
        throw SpeechSynthesisException(
            SynthesisFailure.INVALID_AUDIO,
            audioFailure = SynthesisAudioFailure.SIZE_LIMIT,
        )
    }
    try {
        val response = synthesisJson.decodeFromString<IFlytekSynthesisResponse>(text)
        if (response.code != 0) {
            throw SpeechSynthesisException(
                when (response.code) {
                    10313, 11204 -> SynthesisFailure.AUTHORIZATION
                    11201, 11202, 11203 -> SynthesisFailure.QUOTA
                    10019, 10200 -> SynthesisFailure.TIMEOUT
                    else -> SynthesisFailure.REJECTED
                },
                providerCode = response.code,
            )
        }
        val payload = response.data
        if (payload == null || payload == JsonNull) {
            return SynthesisFrame(ByteArray(0), finished = false)
        }
        val data = synthesisJson.decodeFromJsonElement<IFlytekSynthesisData>(payload)
        if (data.status !in 1..2) {
            throw SpeechSynthesisException(
                SynthesisFailure.INVALID_AUDIO,
                audioFailure = SynthesisAudioFailure.INVALID_STATUS,
                frameStatus = data.status,
            )
        }
        return SynthesisFrame(
            audio = Base64.getDecoder().decode(data.audio.orEmpty()),
            finished = data.status == 2,
        )
    } catch (_: SerializationException) {
        throw SpeechSynthesisException(
            SynthesisFailure.INVALID_AUDIO,
            audioFailure = SynthesisAudioFailure.INVALID_JSON,
        )
    } catch (_: IllegalArgumentException) {
        throw SpeechSynthesisException(
            SynthesisFailure.INVALID_AUDIO,
            audioFailure = SynthesisAudioFailure.INVALID_BASE64,
        )
    }
}

internal fun azureSynthesisUrl(endpoint: String): HttpUrl {
    val url = endpoint.toHttpUrl()
    require(url.isHttps) { "Speech synthesis settings are incomplete or invalid." }
    val regionalSuffixes = listOf(
        ".stt.speech.microsoft.com",
        ".tts.speech.microsoft.com",
        ".api.cognitive.microsoft.com",
    )
    val suffix = regionalSuffixes.firstOrNull { url.host.endsWith(it) }
    return url.newBuilder()
        .apply {
            if (suffix != null) {
                host(url.host.removeSuffix(suffix) + ".tts.speech.microsoft.com")
            }
        }
        .encodedPath(
            if (suffix == null) "/tts/cognitiveservices/v1" else "/cognitiveservices/v1",
        )
        .query(null)
        .fragment(null)
        .build()
}

internal fun azureSynthesisSsml(text: String, voice: String, locale: Locale): String {
    val language = speechLanguageTag(locale)
    val selectedVoice = voice.ifBlank {
        if (language.startsWith("zh")) "zh-CN-XiaoxiaoNeural" else "en-US-JennyNeural"
    }
    return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\"" +
        " xml:lang=\"${language.xmlEscaped()}\"><voice name=\"${selectedVoice.xmlEscaped()}\">" +
        "${text.xmlEscaped()}</voice></speak>"
}

private fun String.xmlEscaped(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

internal fun synthesisChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val maximumEnd = text.offsetByCodePoints(
            start,
            minOf(300, text.codePointCount(start, text.length)),
        )
        val sentenceEnd = (start until maximumEnd).lastOrNull {
            text[it] in ".!?;\n\u3002\uff01\uff1f\uff1b"
        }?.plus(1)
        val end = if (maximumEnd < text.length && sentenceEnd != null) {
            sentenceEnd
        } else {
            maximumEnd
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
