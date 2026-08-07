package com.example.mochi_pet.platform.voice

import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal interface CloudSpeechTranscriber {
    suspend fun transcribe(
        pcmFile: File,
        locale: Locale,
    ): String
}

internal class SpeechTranscriptionException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class AzureSpeechTranscriber(
    private val endpoint: String,
    private val apiKey: String,
    private val client: OkHttpClient = defaultSpeechHttpClient(),
) : CloudSpeechTranscriber {
    override suspend fun transcribe(
        pcmFile: File,
        locale: Locale,
    ): String {
        val url = azureSpeechUrl(endpoint, speechLanguageTag(locale))
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .header(
                "Content-Type",
                "audio/wav; codecs=audio/pcm; samplerate=16000",
            )
            .post(
                pcm16Wav(pcmFile).toRequestBody(null),
            )
            .build()
        client.newCall(request).await().use { response ->
            val body = response.body.string().take(MAX_RESPONSE_CHARS)
            if (!response.isSuccessful) {
                throw SpeechTranscriptionException(
                    message = "Azure Speech returned HTTP ${response.code}",
                    retryable =
                        response.code == 408 ||
                            response.code == 429 ||
                            response.code >= 500,
                )
            }
            return parseAzureTranscript(body)
        }
    }
}

internal class IFlytekSpeechTranscriber(
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val client: OkHttpClient = defaultSpeechHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
) : CloudSpeechTranscriber {
    override suspend fun transcribe(
        pcmFile: File,
        locale: Locale,
    ): String =
        suspendCancellableCoroutine { continuation ->
            val requestStartedAt = SystemClock.elapsedRealtime()
            val transcript = StringBuilder()
            val request = Request.Builder()
                .url(iFlytekSignedUrl(apiKey, apiSecret, clock))
                .build()
            val listener = object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    Log.i(
                        SPEECH_LOG_TAG,
                        "iflytek_websocket_open latencyMs=" +
                            (SystemClock.elapsedRealtime() -
                                requestStartedAt),
                    )
                    thread(
                        name = "MochiIFlytekSpeechUpload",
                        isDaemon = true,
                    ) {
                        try {
                            sendIFlytekAudio(
                                webSocket = webSocket,
                                pcmFile = pcmFile,
                                appId = appId,
                                locale = locale,
                            )
                        } catch (error: IOException) {
                            webSocket.cancel()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    SpeechTranscriptionException(
                                        "Could not upload audio to iFlytek",
                                        retryable = true,
                                        cause = error,
                                    ),
                                )
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            webSocket.cancel()
                        }
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    val result = try {
                        parseIFlytekMessage(text)
                    } catch (error: SpeechTranscriptionException) {
                        webSocket.close(1000, null)
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                        return
                    }
                    transcript.append(result.text)
                    if (result.isFinal) {
                        Log.i(
                            SPEECH_LOG_TAG,
                            "iflytek_final_result latencyMs=" +
                                (SystemClock.elapsedRealtime() -
                                    requestStartedAt),
                        )
                        webSocket.close(1000, null)
                        val value = transcript.toString().trim()
                        if (continuation.isActive) {
                            if (value.isEmpty()) {
                                continuation.resumeWithException(
                                    SpeechTranscriptionException(
                                        "iFlytek did not recognize speech",
                                        retryable = false,
                                    ),
                                )
                            } else {
                                continuation.resume(value)
                            }
                        }
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechTranscriptionException(
                                message = iFlytekConnectionError(
                                    response = response,
                                    failure = t,
                                ),
                                retryable = response == null ||
                                    response.code == 408 ||
                                    response.code == 429 ||
                                    response.code >= 500,
                                cause = t,
                            ),
                        )
                    }
                }
            }
            val webSocket = client.newWebSocket(request, listener)
            continuation.invokeOnCancellation {
                webSocket.cancel()
            }
        }
}

internal class IFlytekLiveSpeechSession(
    private val appId: String,
    apiKey: String,
    apiSecret: String,
    private val locale: Locale,
    private val onProviderEndpoint: () -> Unit,
    client: OkHttpClient = defaultSpeechHttpClient(),
    clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val result = CompletableDeferred<String>()
    private val opened = CountDownLatch(1)
    private val sender = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MochiIFlytekLiveUpload").apply {
            isDaemon = true
        }
    }
    private val pendingAudio = ByteArrayOutputStream(IFLYTEK_FRAME_BYTES)
    private val transcript = StringBuilder()
    private val requestStartedAt = SystemClock.elapsedRealtime()
    private var firstFrame = true
    private var finished = false
    private val webSocket: WebSocket

    init {
        val request = Request.Builder()
            .url(iFlytekSignedUrl(apiKey, apiSecret, clock))
            .build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    Log.i(
                        SPEECH_LOG_TAG,
                        "iflytek_live_websocket_open latencyMs=" +
                            (SystemClock.elapsedRealtime() -
                                requestStartedAt),
                    )
                    opened.countDown()
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    val message = try {
                        parseIFlytekMessage(text)
                    } catch (error: SpeechTranscriptionException) {
                        fail(error)
                        return
                    }
                    transcript.append(message.text)
                    if (message.isFinal) {
                        val value = transcript.toString().trim()
                        if (value.isEmpty()) {
                            fail(
                                SpeechTranscriptionException(
                                    "iFlytek did not recognize speech",
                                    retryable = false,
                                ),
                            )
                        } else {
                            onProviderEndpoint()
                            Log.i(
                                SPEECH_LOG_TAG,
                                "iflytek_live_final_result latencyMs=" +
                                    (SystemClock.elapsedRealtime() -
                                        requestStartedAt),
                            )
                            result.complete(value)
                            webSocket.close(1000, null)
                        }
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    fail(
                        SpeechTranscriptionException(
                            message = iFlytekConnectionError(response, t),
                            retryable = response == null ||
                                response.code == 408 ||
                                response.code == 429 ||
                                response.code >= 500,
                            cause = t,
                        ),
                    )
                }
            },
        )
    }

    @Synchronized
    fun acceptPcm(
        samples: ShortArray,
        count: Int,
    ) {
        if (finished || result.isCompleted) {
            return
        }
        repeat(count) { index ->
            val sample = samples[index].toInt()
            pendingAudio.write(sample and 0xff)
            pendingAudio.write((sample shr 8) and 0xff)
            if (pendingAudio.size() == IFLYTEK_FRAME_BYTES) {
                enqueueAudioFrame(pendingAudio.toByteArray())
                pendingAudio.reset()
            }
        }
    }

    @Synchronized
    fun finish() {
        if (finished || result.isCompleted) {
            return
        }
        if (pendingAudio.size() > 0) {
            enqueueAudioFrame(pendingAudio.toByteArray())
            pendingAudio.reset()
        }
        finished = true
        enqueueFrame(IFLYTEK_LAST_FRAME, ByteArray(0))
    }

    suspend fun awaitResult(): String = result.await()

    override fun close() {
        synchronized(this) {
            finished = true
            pendingAudio.reset()
        }
        webSocket.cancel()
        opened.countDown()
        sender.shutdownNow()
        result.cancel()
    }

    private fun enqueueAudioFrame(audio: ByteArray) {
        val status = if (firstFrame) {
            firstFrame = false
            IFLYTEK_FIRST_FRAME
        } else {
            IFLYTEK_CONTINUE_FRAME
        }
        enqueueFrame(status, audio)
    }

    private fun enqueueFrame(
        status: Int,
        audio: ByteArray,
    ) {
        try {
            sender.execute {
                if (
                    !opened.await(
                        IFLYTEK_OPEN_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                ) {
                    fail(
                        SpeechTranscriptionException(
                            "Timed out connecting to iFlytek Speech",
                            retryable = true,
                        ),
                    )
                    return@execute
                }
                if (result.isCompleted) {
                    return@execute
                }
                val encoded = Base64.getEncoder().encodeToString(audio)
                if (
                    !webSocket.send(
                        iFlytekFrame(
                            status = status,
                            audio = encoded,
                            appId = appId,
                            locale = locale,
                        ).toString(),
                    )
                ) {
                    fail(
                        SpeechTranscriptionException(
                            "Could not upload audio to iFlytek",
                            retryable = true,
                        ),
                    )
                } else if (status == IFLYTEK_LAST_FRAME) {
                    Log.i(
                        SPEECH_LOG_TAG,
                        "iflytek_live_audio_finished latencyMs=" +
                            (SystemClock.elapsedRealtime() -
                                requestStartedAt),
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!result.isCompleted) {
                fail(
                    SpeechTranscriptionException(
                        "iFlytek audio upload stopped unexpectedly",
                        retryable = true,
                    ),
                )
            }
        }
    }

    private fun fail(error: SpeechTranscriptionException) {
        opened.countDown()
        result.completeExceptionally(error)
        webSocket.cancel()
    }
}

internal fun azureSpeechUrl(
    endpoint: String,
    language: String,
): HttpUrl =
    endpoint
        .trim()
        .trimEnd('/')
        .toHttpUrl()
        .newBuilder()
        .addPathSegments(
            "stt/speech/recognition/conversation/cognitiveservices/v1",
        )
        .addQueryParameter("language", language)
        .addQueryParameter("format", "simple")
        .build()

internal fun parseAzureTranscript(raw: String): String {
    val value = parseJsonObject(raw, "Azure Speech")
    val status = value["RecognitionStatus"]
        ?.jsonPrimitive
        ?.contentOrNull
    if (status != "Success") {
        throw SpeechTranscriptionException(
            message = "Azure Speech did not recognize speech",
            retryable = false,
        )
    }
    return value["DisplayText"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.take(MAX_TRANSCRIPT_CHARS)
        .orEmpty()
        .ifEmpty {
            throw SpeechTranscriptionException(
                message = "Azure Speech returned an empty transcript",
                retryable = false,
            )
        }
}

internal fun iFlytekSignedUrl(
    apiKey: String,
    apiSecret: String,
    clock: Clock,
): HttpUrl {
    val date = IFLYTEK_DATE_FORMATTER.format(
        clock.instant().atZone(ZoneOffset.UTC),
    )
    val signatureOrigin =
        "host: $IFLYTEK_HOST\n" +
            "date: $date\n" +
            "GET $IFLYTEK_PATH HTTP/1.1"
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(
            SecretKeySpec(
                apiSecret.toByteArray(Charsets.UTF_8),
                "HmacSHA256",
            ),
        )
    }

    val signature = Base64.getEncoder().encodeToString(
        mac.doFinal(signatureOrigin.toByteArray(Charsets.UTF_8)),
    )
    val authorizationOrigin =
        """api_key="$apiKey", algorithm="hmac-sha256", """ +
            """headers="host date request-line", signature="$signature""""
    val authorization = Base64.getEncoder().encodeToString(
        authorizationOrigin.toByteArray(Charsets.UTF_8),
    )
    return "https://$IFLYTEK_HOST$IFLYTEK_PATH"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("authorization", authorization)
        .addQueryParameter("date", date)
        .addQueryParameter("host", IFLYTEK_HOST)
        .build()
}

private fun iFlytekConnectionError(
    response: Response?,
    failure: Throwable,
): String {
    if (response == null) {
        return "Could not connect to iFlytek Speech: " +
            failure.javaClass.simpleName
    }
    val detail = response.body
        .string()
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(IFLYTEK_ERROR_DETAIL_CHARS)
    return buildString {
        append("iFlytek Speech connection failed (HTTP ")
        append(response.code)
        append(')')
        if (detail.isNotEmpty()) {
            append(": ")
            append(detail)
        }
    }
}

private fun sendIFlytekAudio(
    webSocket: WebSocket,
    pcmFile: File,
    appId: String,
    locale: Locale,
) {
    val bytes = pcmFile.readBytes()
    val uploadStartedAt = SystemClock.elapsedRealtime()
    var offset = 0
    var status = IFLYTEK_FIRST_FRAME
    while (offset < bytes.size) {
        val end = minOf(offset + IFLYTEK_FRAME_BYTES, bytes.size)
        val audio = Base64.getEncoder().encodeToString(
            bytes.copyOfRange(offset, end),
        )
        if (
            !webSocket.send(
                iFlytekFrame(
                    status = status,
                    audio = audio,
                    appId = appId,
                    locale = locale,
                ).toString(),
            )
        ) {
            throw IOException(
                "iFlytek WebSocket closed during audio upload",
            )
        }
        offset = end
        status = IFLYTEK_CONTINUE_FRAME
        if (offset < bytes.size) {
            Thread.sleep(IFLYTEK_FRAME_MILLIS)
        }
    }
    if (
        !webSocket.send(
            iFlytekFrame(
                status = IFLYTEK_LAST_FRAME,
                audio = "",
                appId = appId,
                locale = locale,
            ).toString(),
        )
    ) {
        throw IOException("iFlytek WebSocket closed before the final frame")
    }
    Log.i(
        SPEECH_LOG_TAG,
        "iflytek_audio_sent audioMs=" +
            (bytes.size * 1_000L / (16_000L * 2L)) +
            " uploadMs=" +
            (SystemClock.elapsedRealtime() - uploadStartedAt),
    )
}

internal fun iFlytekFrame(
    status: Int,
    audio: String,
    appId: String,
    locale: Locale,
): JsonObject =
    buildJsonObject {
        if (status == IFLYTEK_FIRST_FRAME) {
            put(
                "common",
                buildJsonObject {
                    put("app_id", appId)
                },
            )
            put(
                "business",
                buildJsonObject {
                    val isChinese =
                        locale.language.equals("zh", ignoreCase = true)
                    put("language", if (isChinese) "zh_cn" else "en_us")
                    put("domain", "iat")
                    if (isChinese) {
                        put("accent", "mandarin")
                    }
                    put("vad_eos", IFLYTEK_VAD_EOS_MILLIS)
                    put("ptt", 1)
                },
            )
        }
        put(
            "data",
            buildJsonObject {
                put("status", status)
                put("format", "audio/L16;rate=16000")
                put("encoding", "raw")
                put("audio", audio)
            },
        )
    }

private data class IFlytekResult(
    val text: String,
    val isFinal: Boolean,
)

private const val SPEECH_LOG_TAG = "MochiSpeech"

private fun parseIFlytekMessage(raw: String): IFlytekResult {
    val value = parseJsonObject(raw, "iFlytek Speech")
    val code = value["code"]?.jsonPrimitive?.intOrNull ?: -1
    if (code != 0) {
        val message = value["message"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
        throw SpeechTranscriptionException(
            message = "iFlytek Speech error $code: $message",
            retryable = code in IFLYTEK_RETRYABLE_CODES,
        )
    }
    val data = value["data"]?.jsonObject
    val words = data
        ?.get("result")
        ?.jsonObject
        ?.get("ws")
        ?.jsonArray
        ?: JsonArray(emptyList())
    val text = words.joinToString(separator = "") { word ->
        word.jsonObject["cw"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("w")
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
    }
    return IFlytekResult(
        text = text,
        isFinal = data?.get("status")?.jsonPrimitive?.intOrNull == 2,
    )
}

private fun parseJsonObject(
    raw: String,
    provider: String,
): JsonObject =
    try {
        SPEECH_JSON.parseToJsonElement(raw).jsonObject
    } catch (error: SerializationException) {
        throw SpeechTranscriptionException(
            "$provider returned invalid JSON",
            retryable = false,
            cause = error,
        )
    } catch (error: IllegalArgumentException) {
        throw SpeechTranscriptionException(
            "$provider returned an invalid response",
            retryable = false,
            cause = error,
        )
    }

private fun speechLanguageTag(locale: Locale): String =
    if (locale.language.equals("zh", ignoreCase = true)) {
        "zh-CN"
    } else {
        "en-US"
    }

private fun pcm16Wav(file: File): ByteArray {
    val pcm = file.readBytes()
    val result = ByteArray(WAV_HEADER_BYTES + pcm.size)
    writeAscii(result, 0, "RIFF")
    writeLittleEndianInt(result, 4, result.size - 8)
    writeAscii(result, 8, "WAVE")
    writeAscii(result, 12, "fmt ")
    writeLittleEndianInt(result, 16, 16)
    writeLittleEndianShort(result, 20, 1)
    writeLittleEndianShort(result, 22, 1)
    writeLittleEndianInt(result, 24, SPEECH_SAMPLE_RATE)
    writeLittleEndianInt(result, 28, SPEECH_SAMPLE_RATE * 2)
    writeLittleEndianShort(result, 32, 2)
    writeLittleEndianShort(result, 34, 16)
    writeAscii(result, 36, "data")
    writeLittleEndianInt(result, 40, pcm.size)
    pcm.copyInto(result, WAV_HEADER_BYTES)
    return result
}

private fun writeAscii(
    target: ByteArray,
    offset: Int,
    value: String,
) {
    value.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
}

private fun writeLittleEndianInt(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    repeat(4) { index ->
        target[offset + index] = (value shr (index * 8)).toByte()
    }
}

private fun writeLittleEndianShort(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    repeat(2) { index ->
        target[offset + index] = (value shr (index * 8)).toByte()
    }
}

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            SpeechTranscriptionException(
                                "Speech provider network error",
                                retryable = true,
                                cause = e,
                            ),
                        )
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response)
                }
            },
        )
    }

private fun defaultSpeechHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(75, TimeUnit.SECONDS)
        .build()

private val SPEECH_JSON = Json {
    ignoreUnknownKeys = true
}
private val IFLYTEK_DATE_FORMATTER = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
    .toFormatter(Locale.US)
private val IFLYTEK_RETRYABLE_CODES =
    setOf(10114, 10116, 10139, 10163, 10200, 10201, 10202)
private const val IFLYTEK_HOST = "iat-api.xfyun.cn"
private const val IFLYTEK_PATH = "/v2/iat"
private const val IFLYTEK_FIRST_FRAME = 0
private const val IFLYTEK_CONTINUE_FRAME = 1
private const val IFLYTEK_LAST_FRAME = 2
private const val IFLYTEK_FRAME_BYTES = 1_280
private const val IFLYTEK_FRAME_MILLIS = 40L
private const val IFLYTEK_OPEN_TIMEOUT_SECONDS = 15L
private const val IFLYTEK_VAD_EOS_MILLIS = 700
private const val SPEECH_SAMPLE_RATE = 16_000
private const val WAV_HEADER_BYTES = 44
private const val MAX_RESPONSE_CHARS = 64_000
private const val MAX_TRANSCRIPT_CHARS = 20_000
private const val IFLYTEK_ERROR_DETAIL_CHARS = 300
