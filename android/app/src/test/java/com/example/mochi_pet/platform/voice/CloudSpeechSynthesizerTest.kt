package com.example.mochi_pet.platform.voice

import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudSpeechSynthesizerTest {
    private lateinit var server: MockWebServer
    private val iflytek = SpeechRuntimeConfig.IFlytek("test-app", "test-key", "test-secret")
    private val azure = SpeechRuntimeConfig.Azure(
        "https://test.cognitiveservices.azure.com",
        "test-key",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `Azure derives synthesis endpoints without STT paths or query parameters`() {
        listOf(
            "https://eastus.stt.speech.microsoft.com/speech/recognition?language=en" to
                "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1",
            "https://eastus.api.cognitive.microsoft.com/" to
                "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1",
            "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1" to
                "https://eastus.tts.speech.microsoft.com/cognitiveservices/v1",
            "https://test.cognitiveservices.azure.com/stt/speech/recognition?format=simple" to
                "https://test.cognitiveservices.azure.com/tts/cognitiveservices/v1",
        ).forEach { (endpoint, expected) ->
            assertEquals(expected, azureSynthesisUrl(endpoint).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            azureSynthesisUrl("http://test.cognitiveservices.azure.com")
        }
    }

    @Test
    fun `Azure requests raw PCM with reused key and escaped SSML`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0, 1, 2, 3))))
        val result = azureSynthesizer().synthesize(
            azure.copy(voice = "zh-CN-XiaoxiaoNeural"),
            "<test> & \"quoted\"",
            Locale.SIMPLIFIED_CHINESE,
        )
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), result)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("test-key", request.getHeader("Ocp-Apim-Subscription-Key"))
        assertEquals("raw-16khz-16bit-mono-pcm", request.getHeader("X-Microsoft-OutputFormat"))
        assertEquals("/tts/cognitiveservices/v1", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"zh-CN-XiaoxiaoNeural\""))
        assertTrue(body.contains("&lt;test&gt; &amp; &quot;quoted&quot;"))
        assertFalse(body.contains("<test>"))
    }

    @Test
    fun `iFlytek collects frames skips null data and signs the TTS path`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"code":0,"data":null}""")
                        webSocket.send("""{"code":0,"data":{"status":1,"audio":"AAE="}}""")
                        webSocket.send("""{"code":0,"data":{"status":2,"audio":"AgM="}}""")
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }
                },
            ),
        )
        val result = CloudSpeechSynthesizer(
            iFlytekEndpoint = server.url("/v2/tts"),
        ).synthesize(iflytek, "hello", Locale.ENGLISH)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), result)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertTrue(request.path!!.startsWith("/v2/tts?"))
        assertNotNull(request.requestUrl!!.queryParameter("authorization"))
        assertEquals(server.url("/").host, request.requestUrl!!.queryParameter("host"))
    }

    @Test
    fun `iFlytek request uses UTF8 and selected voice`() {
        val request = Json.parseToJsonElement(
            iFlytekSynthesisRequest(iflytek.copy(voice = "x4_xiaoyan"), "Hello \u4f60\u597d"),
        ).jsonObject
        assertEquals("raw", request["business"]!!.jsonObject["aue"]!!.jsonPrimitive.content)
        assertEquals("UTF8", request["business"]!!.jsonObject["tte"]!!.jsonPrimitive.content)
        assertEquals("x4_xiaoyan", request["business"]!!.jsonObject["vcn"]!!.jsonPrimitive.content)
        assertEquals(
            "Hello \u4f60\u597d",
            String(
                Base64.getDecoder().decode(
                    request["data"]!!.jsonObject["text"]!!.jsonPrimitive.content,
                ),
                Charsets.UTF_8,
            ),
        )
    }

    @Test
    fun `invalid iFlytek frames produce safe errors rather than provider text`() {
        listOf(
            "{}",
            """{"code":0,"data":{"status":9}}""",
            """{"code":0,"data":{"status":2,"audio":"@@@"}}""",
        ).forEach {
            assertEquals(
                SynthesisFailure.INVALID_AUDIO,
                assertThrows(SpeechSynthesisException::class.java) {
                    parseIFlytekSynthesisFrame(it)
                }.failure,
            )
        }
        val error = assertThrows(SpeechSynthesisException::class.java) {
            parseIFlytekSynthesisFrame("""{"code":11202,"message":"private provider detail"}""")
        }
        assertEquals(SynthesisFailure.QUOTA, error.failure)
        assertEquals(11202, error.providerCode)
        assertEquals(
            "failure=QUOTA providerCode=11202 httpStatus=none audioFailure=none frameStatus=none",
            error.safeDiagnostic(),
        )
        assertFalse(error.message!!.contains("private"))
    }

    @Test
    fun `provider rejection code survives nullable or malformed error data`() {
        listOf(
            """{"audio":null,"status":null}""",
            """{"audio":null}""",
            "\"not an audio frame\"",
            "null",
        ).forEach { data ->
            val error = assertThrows(SpeechSynthesisException::class.java) {
                parseIFlytekSynthesisFrame(
                    """{"code":11200,"message":"private detail","data":$data}""",
                )
            }
            assertEquals(SynthesisFailure.REJECTED, error.failure)
            assertEquals(11200, error.providerCode)
            assertEquals(null, error.audioFailure)
            assertFalse(error.safeDiagnostic().contains("private"))
        }
    }

    @Test
    fun `successful terminal frame may contain null audio after previous frames`() {
        val frame = parseIFlytekSynthesisFrame(
            """{"code":0,"data":{"status":2,"audio":null}}""",
        )
        assertTrue(frame.finished)
        assertTrue(frame.audio.isEmpty())
    }

    @Test
    fun `Azure rejects failed status empty odd and oversized PCM`() {
        listOf(
            MockResponse().setResponseCode(401).setBody("private detail") to SynthesisFailure.AUTHORIZATION,
            MockResponse().setResponseCode(429) to SynthesisFailure.QUOTA,
            MockResponse().setBody("") to SynthesisFailure.INVALID_AUDIO,
            MockResponse().setBody("x") to SynthesisFailure.INVALID_AUDIO,
            MockResponse().setBody("{}").setHeader("Content-Type", "application/json") to
                SynthesisFailure.INVALID_AUDIO,
            MockResponse().setBody(Buffer().write(ByteArray(MAX_SYNTHESIS_AUDIO_BYTES + 2))) to
                SynthesisFailure.INVALID_AUDIO,
        ).forEach { (response, failure) ->
            server.enqueue(response)
            val error = assertThrows(SpeechSynthesisException::class.java) {
                runBlocking { azureSynthesizer().synthesize(azure, "test", Locale.ENGLISH) }
            }
            assertEquals(failure, error.failure)
            if (response.status.contains("401")) {
                assertEquals(401, error.httpStatus)
                assertEquals(
                    "failure=AUTHORIZATION providerCode=none httpStatus=401 audioFailure=none frameStatus=none",
                    error.safeDiagnostic(),
                )
            }
            assertFalse(error.message!!.contains("private"))
        }
    }

    @Test
    fun `request timeout cancels unfinished Azure audio`() {
        server.enqueue(MockResponse().setBody("00").setBodyDelay(2, TimeUnit.SECONDS))
        val error = assertThrows(SpeechSynthesisException::class.java) {
            runBlocking {
                azureSynthesizer(timeoutMillis = 100).synthesize(azure, "test", Locale.ENGLISH)
            }
        }
        assertEquals(SynthesisFailure.TIMEOUT, error.failure)
    }

    @Test
    fun `caller cancellation is not turned into synthesis success or retry`() = runBlocking {
        server.enqueue(MockResponse().setBody("00").setBodyDelay(2, TimeUnit.SECONDS))
        val pending = async { azureSynthesizer().synthesize(azure, "test", Locale.ENGLISH) }
        withContext(Dispatchers.IO) {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Unicode chunks preserve text and stay strictly below provider byte limit`() {
        val text = ("\u4f60\u597d\u3002" + String(Character.toChars(0x1F600))).repeat(2_000)
        val chunks = synthesisChunks(text)
        assertEquals(text, chunks.joinToString(""))
        chunks.forEach {
            assertTrue(it.toByteArray(Charsets.UTF_8).size < 8_000)
            assertFalse(Character.isLowSurrogate(it.first()))
            assertFalse(Character.isHighSurrogate(it.last()))
        }
    }

    private fun azureSynthesizer(timeoutMillis: Long = 30_000): CloudSpeechSynthesizer =
        CloudSpeechSynthesizer(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .url(server.url(chain.request().url.encodedPath))
                            .build(),
                    )
                }.build(),
            requestTimeoutMillis = timeoutMillis,
        )
}
