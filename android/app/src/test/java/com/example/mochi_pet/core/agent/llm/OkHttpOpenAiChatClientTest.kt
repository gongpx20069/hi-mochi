package com.example.mochi_pet.core.agent.llm

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpOpenAiChatClientTest {
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
