package com.example.mochi_pet.platform.voice

import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSpeechVoicesTest {
    private val response = """
        [{"ShortName":"zh-CN-XiaoxiaoNeural","LocalName":"Xiaoxiao","Locale":"zh-CN","VoiceType":"Neural"},
         {"ShortName":"en-US-JennyNeural","LocalName":"Jenny","Locale":"en-US","VoiceType":"Neural"}]
    """.trimIndent()

    @Test
    fun `voice list uses saved key and correct regional or custom path`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            listOf(
                "https://eastus.stt.speech.microsoft.com" to "/cognitiveservices/voices/list",
                "https://test.cognitiveservices.azure.com" to "/tts/cognitiveservices/voices/list",
            ).forEach { (endpoint, path) ->
                server.enqueue(MockResponse().setBody(response))
                val voices = repository(server).load(SpeechRuntimeConfig.Azure(endpoint, "test-key"))
                assertEquals(2, voices.size)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(path, request.path)
                assertEquals("GET", request.method)
                assertEquals("test-key", request.getHeader("Ocp-Apim-Subscription-Key"))
            }
        }
    }

    @Test
    fun `failed and oversized catalogs remain errors without raw provider content`() {
        MockWebServer().use { server ->
            server.start()
            listOf(
                MockResponse().setResponseCode(401).setBody("private provider details"),
                MockResponse().setBody("x".repeat(2_097_153)),
                MockResponse().setBody("[]"),
                MockResponse().setBody("""[{"ShortName":"invalid id","Locale":"en-US"}]"""),
            ).forEach { reply ->
                server.enqueue(reply)
                val error = assertThrows(IOException::class.java) {
                    runBlocking {
                        repository(server).load(
                            SpeechRuntimeConfig.Azure("https://test.cognitiveservices.azure.com", "test-key"),
                        )
                    }
                }
                assertFalse(error.message.orEmpty().contains("private"))
            }
        }
    }

    @Test
    fun `cancelling catalog cancels its pending request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(response).setBodyDelay(2, TimeUnit.SECONDS))
            val pending = async {
                repository(server).load(SpeechRuntimeConfig.Azure("https://test.cognitiveservices.azure.com", "test-key"))
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }

    private fun repository(server: MockWebServer) = AzureSpeechVoices(
        OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor {
                it.proceed(it.request().newBuilder().url(server.url(it.request().url.encodedPath)).build())
            }
            .build(),
    )
}
