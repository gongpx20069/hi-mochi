package com.example.mochi_pet.core.agent.llm

import com.example.mochi_pet.core.settings.ProviderPreset
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpOpenAiChatClientTest {
    @Test fun `official presets generate exact chat completion URLs without network access`() = runBlocking {
        val expected = mapOf(
            ProviderPreset.OPENAI to "https://api.openai.com/v1/chat/completions",
            ProviderPreset.DEEPSEEK to "https://api.deepseek.com/chat/completions",
            ProviderPreset.KIMI to "https://api.moonshot.cn/v1/chat/completions",
            ProviderPreset.GLM to "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            ProviderPreset.MINIMAX to "https://api.minimax.cn/v1/chat/completions",
            ProviderPreset.AGNES to "https://apihub.agnes-ai.com/v1/chat/completions",
        )
        val urls = mutableListOf<String>()
        val intercepted = OkHttpOpenAiChatClient(OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","content":"{}"}}]}""".toResponseBody())
                .build()
        }.build())
        expected.forEach { (preset, url) ->
            intercepted.complete(OpenAiProviderConfig(
                providerType = preset.protocol, endpoint = preset.endpoint,
                apiKey = "fixture-key", model = "fixture-model",
            ), request())
            assertEquals(url, urls.last())
        }
        assertEquals(expected.size, urls.size)
    }

    @Test fun `cancellation interrupts a stalled response body without waiting for provider timeout`() {
        server.enqueue(successResponse().setBodyDelay(3, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(500) { client.complete(config(server.url("/").toString()), request()) } }
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertEquals(1, server.requestCount)
    }

    @Test fun `compatible provider reasoning remains in the same-run assistant tool message`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"synthetic reasoning","tool_calls":[{"id":"tool-1","type":"function","function":{"name":"fixture","arguments":"{}"}}]}}]}""",
        ))
        val assistant = client.complete(config(server.url("/v1").toString()), request()).choices.single().message
        assertEquals("synthetic reasoning", assistant.reasoningContent)
        server.takeRequest()
        server.enqueue(successResponse())
        client.complete(config(server.url("/v1").toString()), OpenAiChatRequest(messages = listOf(
            assistant.copy(role = "assistant"),
            OpenAiChatMessage("tool", "{}", toolCallId = "tool-1"),
        )))
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("synthetic reasoning", sent["messages"]!!.jsonArray.first().jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpOpenAiChatClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpOpenAiChatClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `foreground timeout preserves shorter limits and all provider options`() {
        for (timeout in listOf(1L, 10L, 20L, 60L, 300L)) {
            val original = OpenAiProviderConfig(
                providerType = ProviderType.AZURE_OPENAI,
                endpoint = server.url("/").toString(),
                apiKey = "test-key",
                model = "test-deployment",
                apiVersion = "2024-10-21",
                timeoutSeconds = timeout,
                maxResponseBytes = 1234,
                imageInputEnabled = false,
            )
            val foreground = original.forForegroundRequest()

            assertEquals(minOf(timeout, 20L), foreground.timeoutSeconds)
            assertEquals(timeout, original.timeoutSeconds)
            assertEquals(original.providerType, foreground.providerType)
            assertEquals(original.endpoint, foreground.endpoint)
            assertEquals(original.apiKey, foreground.apiKey)
            assertEquals(original.model, foreground.model)
            assertEquals(original.apiVersion, foreground.apiVersion)
            assertEquals(original.maxResponseBytes, foreground.maxResponseBytes)
            assertEquals(original.imageInputEnabled, foreground.imageInputEnabled)
        }
    }

    @Test(timeout = 30_000)
    fun `stalled foreground request times out at twenty seconds without replay`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val start = System.nanoTime()

        val error = assertThrows(ProviderNetworkException::class.java) {
            runBlocking {
                client.complete(
                    config = config(server.url("/").toString()).forForegroundRequest(),
                    request = request(),
                )
            }
        }

        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("Elapsed: $elapsed ms", elapsed in 19_000L..25_000L)
        assertTrue(error.cause is InterruptedIOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `posts OpenAI-compatible request with bearer token`() = runBlocking {
        server.enqueue(successResponse())

        val response = client.complete(
            config = config(server.url("/v1/").toString()),
            request = request(),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer secret", recorded.getHeader("Authorization"))
        assertEquals("{}", response.choices.single().message.content)
    }

    @Test
    fun `posts Azure request with deployment path and api key header`() =
        runBlocking {
            server.enqueue(successResponse())

            client.complete(
                config = OpenAiProviderConfig(
                    providerType = ProviderType.AZURE_OPENAI,
                    endpoint = server.url("/").toString(),
                    apiKey = "azure-secret",
                    model = "mochi-deployment",
                    apiVersion = "2024-10-21",
                ),
                request = request(),
            )

            val recorded = server.takeRequest()
            assertEquals(
                "/openai/deployments/mochi-deployment/chat/completions" +
                    "?api-version=2024-10-21",
                recorded.path,
            )
            assertEquals("azure-secret", recorded.getHeader("api-key"))
            assertEquals(null, recorded.getHeader("Authorization"))
            assertEquals(false, recorded.body.readUtf8().contains("\"model\""))
        }

    @Test
    fun `serializes bounded image content parts`() = runBlocking {
        server.enqueue(successResponse())

        client.complete(
            config = config(server.url("/v1/").toString()),
            request = OpenAiChatRequest(
                messages = listOf(
                    OpenAiChatMessage(
                        role = "user",
                        contentParts = listOf(
                            OpenAiChatContentPart(
                                type = "text",
                                text = "Describe this image.",
                            ),
                            OpenAiChatContentPart(
                                type = "image_url",
                                imageUrl = OpenAiImageUrl(
                                    url = "data:image/jpeg;base64,AQID",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("data:image/jpeg;base64,AQID"))
        assertTrue(!body.contains("\"contentParts\""))
    }

    @Test
    fun `accepts null tool calls in provider response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Done",
                            "tool_calls": null
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val response = client.complete(
            config = config(server.url("/v1/").toString()),
            request = OpenAiChatRequest(
                messages = listOf(
                    OpenAiChatMessage(role = "user", content = "Hello"),
                ),
            ),
        )

        assertEquals("Done", response.choices.single().message.content)
        assertEquals(null, response.choices.single().message.toolCalls)
    }

    @Test
    fun `surfaces provider HTTP error without exposing request secret`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Invalid secret"}}"""),
        )

        val error = assertThrows(ProviderHttpException::class.java) {
            runBlocking {
                client.complete(
                    config = config(server.url("/").toString()),
                    request = request(),
                )
            }
        }

        assertEquals(401, error.statusCode)
        assertEquals("Invalid [REDACTED]", error.message)
    }

    @Test
    fun `rejects response larger than configured limit`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("x".repeat(200)),
        )

        assertThrows(ProviderResponseTooLargeException::class.java) {
            runBlocking {
                client.complete(
                    config = config(
                        endpoint = server.url("/").toString(),
                        maxResponseBytes = 100,
                    ),
                    request = request(),
                )
            }
        }
    }

    private fun successResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "choices":[
                    {"message":{"role":"assistant","content":"{}"}}
                  ]
                }
                """.trimIndent(),
            )

    private fun config(
        endpoint: String,
        maxResponseBytes: Long = 1024,
    ): OpenAiProviderConfig =
        OpenAiProviderConfig(
            endpoint = endpoint,
            apiKey = "secret",
            model = "test-model",
            maxResponseBytes = maxResponseBytes,
        )

    private fun request(): OpenAiChatRequest =
        OpenAiChatRequest(
            model = "test-model",
            messages = listOf(
                OpenAiChatMessage(role = "user", content = "hello"),
            ),
        )
}
