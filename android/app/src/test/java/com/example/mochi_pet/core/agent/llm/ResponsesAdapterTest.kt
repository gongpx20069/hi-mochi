package com.example.mochi_pet.core.agent.llm

import com.example.mochi_pet.core.agent.AgentOrchestrator
import com.example.mochi_pet.core.agent.AgentRunRequest
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.NavigationPolicy
import com.example.mochi_pet.core.skills.AgentSkillMetadata
import com.example.mochi_pet.core.skills.MochiSkill
import com.example.mochi_pet.core.skills.SkillOrigin
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ResponsesAdapterTest {
    @Test fun `Skill scoped search round trip loads schemas on Chat and Responses without native search items`() = runBlocking {
        for (protocol in ApiProtocol.entries) {
            var executions = 0
            val fixture = object : AgentTool {
                override val name = "fixture"
                override val schema = functionToolSchema(
                    name, "Fixture remote lookup", buildJsonObject {}, emptyList(),
                )
                override suspend fun execute(
                    arguments: JsonObject,
                    context: ToolExecutionContext,
                ): ToolResultEnvelope {
                    executions++
                    return ToolResultEnvelope.success(arguments)
                }
            }
            fun call(id: String, name: String, args: String): String =
                if (protocol == ApiProtocol.RESPONSES) {
                    """{"status":"completed","output":[{"type":"function_call","call_id":"$id","name":"$name","arguments":$args}]}"""
                } else {
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":$args}}]}}]}"""
                }
            server.enqueue(response(call("search", "tool_search", """"{\"query\":\"fixture\",\"skill_name\":\"fixture-skill\"}"""")))
            server.enqueue(response(call("execute", "fixture", """"{}"""")))
            server.enqueue(response(if (protocol == ApiProtocol.RESPONSES) FINAL else
                """{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"ok\"}"}}]}"""))
            val runner = AgentOrchestrator(
                client, ToolRegistry(listOf(fixture)), NavigationPolicy(), {},
                skillCatalogProvider = { listOf(AgentSkillMetadata("fixture-skill", "Fixture", "builtin://fixture-skill")) },
                skillProvider = {
                    MochiSkill(
                        id = "fixture-skill", name = "fixture-skill", description = "Fixture",
                        content = "---\nname: fixture-skill\nallowed-tools: fixture\n---\nRead.",
                        origin = SkillOrigin.MARKET, source = "fixture", sourceUrl = "",
                        upstreamDigest = "fixture", localDigest = "fixture", enabled = true, modified = false,
                        updateAvailable = false, installedAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                    )
                },
            )
            val result = runner.run(AgentRunRequest(
                config(protocol = protocol), "Use the fixture", "neutral",
                ToolExecutionContext(
                    LocalDate.of(2026, 11, 17), MochiSurface.Face,
                ),
            ))
            assertEquals("ok", result.reply)
            assertEquals(1, executions)
            val bodies = (1..3).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            fun names(body: JsonObject) = body.getValue("tools").jsonArray.map {
                val value = if (protocol == ApiProtocol.RESPONSES) it.jsonObject else it.jsonObject.getValue("function").jsonObject
                value.getValue("name").jsonPrimitive.content
            }.toSet()
            assertEquals(setOf("tool_search"), names(bodies[0]))
            assertEquals(setOf("tool_search", "fixture"), names(bodies[1]))
            val input = bodies[1].getValue(if (protocol == ApiProtocol.RESPONSES) "input" else "messages").jsonArray
            assertTrue(input.last().toString().contains("loaded_tools"))
            assertTrue(input.last().toString().contains("fixture-skill"))
            assertEquals("search", input.last().jsonObject.getValue(
                if (protocol == ApiProtocol.RESPONSES) "call_id" else "tool_call_id",
            ).jsonPrimitive.content)
            assertFalse(bodies.any { it.toString().contains("tool_search_output") })
        }
    }

    private val server = MockWebServer()
    private val client = OkHttpOpenAiChatClient()
    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun config(
        path: String = "/openai/v1",
        type: ProviderType = ProviderType.AZURE_OPENAI,
        protocol: ApiProtocol = ApiProtocol.RESPONSES,
    ) =
        OpenAiProviderConfig(
            providerType = type, endpoint = server.url(path).toString(),
            apiKey = "synthetic-key", model = "fixture-deployment",
            apiProtocol = protocol,
        )

    private fun request() = OpenAiChatRequest(messages = listOf(OpenAiChatMessage("user", "Run the fixture.")))
    private fun response(body: String = FINAL) = MockResponse().setBody(body)

    @Test fun `Azure stateless tool loop preserves opaque output and call IDs without chat serialization`() = runBlocking {
        val tool = Json.parseToJsonElement(
            """{"type":"function","function":{"name":"fixture","parameters":{"type":"object","properties":{"optional":{"type":"string"}}}}}""",
        ).jsonObject
        server.enqueue(response(TOOL))
        val first = client.complete(config(), request().copy(tools = listOf(tool))).choices.single().message
        val sent = server.takeRequest()
        assertEquals("/openai/v1/responses", sent.path)
        assertEquals("synthetic-key", sent.getHeader("api-key"))
        assertNull(sent.getHeader("Authorization"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("fixture-deployment", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("false", body["tools"]!!.jsonArray.single().jsonObject["strict"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning"))
        assertFalse(body.containsKey("messages"))
        assertEquals("call_fixture", first.toolCalls!!.single().id)
        assertFalse(Json.encodeToString(OpenAiChatMessage.serializer(), first).contains("opaque-fixture"))

        server.enqueue(response())
        val final = client.complete(config(), request().copy(messages = request().messages + listOf(
            first.copy(role = "assistant"),
            OpenAiChatMessage("tool", """{"value":"ok"}""", toolCallId = "call_fixture"),
        ))).choices.single().message
        assertEquals("""{"reply":"ok"}""", final.content)
        val input = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["input"]!!.jsonArray
        assertEquals(first.responsesOutput!!.toList(), input.drop(1).dropLast(1))
        assertEquals("function_call_output", input.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_fixture", input.last().jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals(2, server.requestCount)
    }

    @Test fun `explicit endpoints normalize and images use Responses content types`() = runBlocking {
        val cases = listOf(
            Triple(ProviderType.AZURE_OPENAI, "/", "/openai/v1/responses"),
            Triple(ProviderType.AZURE_OPENAI, "/openai/v1/responses", "/openai/v1/responses"),
            Triple(ProviderType.AZURE_OPENAI, "/openai/deployments/old/chat/completions?api-version=old", "/openai/v1/responses"),
            Triple(ProviderType.OPENAI, "/v1/chat/completions", "/v1/responses"),
            Triple(ProviderType.CUSTOM, "/gateway/v1/responses", "/gateway/v1/responses"),
        )
        cases.forEach { (type, endpoint, expected) ->
            server.enqueue(response())
            client.complete(config(endpoint, type), OpenAiChatRequest(messages = listOf(
                OpenAiChatMessage("system", "Use the fixture."),
                OpenAiChatMessage("user", contentParts = listOf(
                    OpenAiChatContentPart("text", text = "Read this."),
                    OpenAiChatContentPart("image_url", imageUrl = OpenAiImageUrl("data:image/png;base64,AQID")),
                )),
                OpenAiChatMessage("assistant", "Previous final reply."),
            )))
            val sent = server.takeRequest()
            assertEquals(expected, sent.path)
            assertEquals(type == ProviderType.AZURE_OPENAI, sent.getHeader("api-key") != null)
            assertEquals(type != ProviderType.AZURE_OPENAI, sent.getHeader("Authorization") != null)
            val input = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject["input"]!!.jsonArray
            val parts = input[1].jsonObject["content"]!!.jsonArray
            assertEquals("input_text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("input_image", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("data:image/png;base64,AQID", parts[1].jsonObject["image_url"]!!.jsonPrimitive.content)
            assertEquals("final_answer", input[2].jsonObject["phase"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `incomplete refusal malformed and unsupported output fail without repair or retries`() {
        val failures = listOf(
            """{"status":"incomplete","output":[]}""",
            """{"status":"failed","error":{"message":"failed"}}""",
            """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"no"}]}]}""",
            """{"status":"completed","output":[{"type":"function_call","call_id":"c","name":"fixture","arguments":{}}]}""",
            """{"status":"completed","output":[{"type":"web_search_call"}]}""",
            """{"status":"completed","output":[{"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"Working"}]}]}""",
            """{"status":"completed","output":[]}""",
            "invalid JSON",
        )
        failures.forEachIndexed { index, payload ->
            server.enqueue(response(payload))
            assertThrows(ProviderProtocolException::class.java) { runBlocking { client.complete(config(), request()) } }
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun `switching back to chat reuses explicit Responses endpoints and custom query parameters`() = runBlocking {
        val cases = listOf(
            Triple(ProviderType.CUSTOM, "/v1/responses?route=fixture", "/v1/chat/completions?route=fixture"),
            Triple(ProviderType.AZURE_OPENAI, "/openai/v1/responses", "/openai/deployments/fixture-deployment/chat/completions?api-version=2024-10-21"),
        )
        cases.forEach { (type, endpoint, expected) ->
            server.enqueue(response("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
            client.complete(config(endpoint, type, ApiProtocol.CHAT_COMPLETIONS), request())
            assertEquals(expected, server.takeRequest().path)
        }
    }

    @Test fun `HTTP errors do not downgrade protocol and cancellation interrupts body reading`() {
        server.enqueue(response("""{"error":{"message":"unsupported protocol"}}""").setResponseCode(400))
        assertThrows(ProviderHttpException::class.java) { runBlocking { client.complete(config(), request()) } }
        assertEquals(1, server.requestCount)
        server.enqueue(response().setBodyDelay(3, TimeUnit.SECONDS))
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(500) { client.complete(config(), request()) } }
        }
        assertEquals(2, server.requestCount)
    }

    private companion object {
        const val FINAL = """{"status":"completed","output":[{"type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"{\"reply\":\"ok\"}"}]}]}"""
        const val TOOL = """{"status":"completed","output":[{"type":"reasoning","id":"rs_fixture","summary":[],"encrypted_content":"opaque-fixture"},{"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"Checking."}]},{"type":"function_call","id":"fc_fixture","call_id":"call_fixture","name":"fixture","arguments":"{}","status":"completed"}]}"""
    }
}
