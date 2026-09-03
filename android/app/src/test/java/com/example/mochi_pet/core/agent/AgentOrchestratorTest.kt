package com.example.mochi_pet.core.agent

import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatMessage
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.OpenAiChatResponse
import com.example.mochi_pet.core.agent.llm.OpenAiChoice
import com.example.mochi_pet.core.agent.llm.OpenAiFunctionCall
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.OpenAiToolCall
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ModelImageAttachment
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.NavigationDecision
import com.example.mochi_pet.core.navigation.NavigationPolicy
import com.example.mochi_pet.core.presentation.CardPlacement
import com.example.mochi_pet.core.presentation.CardType
import com.example.mochi_pet.core.skills.AgentSkillMetadata
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrchestratorTest {
    private val provider = OpenAiProviderConfig(
        endpoint = "https://example.com/v1",
        apiKey = "test-key",
        model = "test-model",
    )
    private val context = ToolExecutionContext(
        currentDate = LocalDate.of(2026, 7, 31),
        currentSurface = MochiSurface.Face,
    )

    @Test
    fun `prompt contains persona memory time and Skill metadata only`() {
        val prompt = AgentPromptBuilder(
            Clock.fixed(
                Instant.parse("2026-07-31T10:00:00Z"),
                ZoneId.of("Asia/Shanghai"),
            ),
        ).build(
            request().copy(
                personaSections = listOf(
                    "# Mochi\nPractical assistant.",
                    "# User\nPrefers concise answers.",
                    "# Agent Rules\nNever invent tool results.",
                ),
                recalledMemories = listOf(
                    "- [2026-07-20T17:30:00+08:00] User: " +
                        "Project Mochi uses Kotlin.",
                ),
                availableSkills = listOf(
                    AgentSkillMetadata(
                        name = "web-search",
                        description = "Research current public information.",
                        location = "builtin://web-search/SKILL.md",
                    ),
                ),
            ),
        )

        assertTrue(prompt.contains("# Mochi"))
        assertTrue(prompt.contains("# Relevant memories"))
        assertTrue(prompt.contains("NOTE:Asia/Shanghai"))
        assertTrue(
            prompt.contains(
                "distinguish past context from the current conversation",
            ),
        )
        assertTrue(
            prompt.contains(
                "[2026-07-20T17:30:00+08:00]",
            ),
        )
        assertTrue(
            !prompt.substringAfter("NOTE:Asia/Shanghai")
                .substringBefore("# Runtime context")
                .contains("[Asia/Shanghai]"),
        )
        assertTrue(prompt.contains("Timezone: Asia/Shanghai"))
        assertTrue(prompt.contains("<name>web-search</name>"))
        assertTrue(prompt.contains("Call load_skill"))
        assertTrue(prompt.contains("visual presentation never replaces"))
        assertTrue(
            prompt.contains(
                """{"surface":"date_time","reason":"current_time_date","section":"time"}""",
            ),
        )
        assertTrue(
            prompt.contains(
                """{"surface":"weather","reason":"current_weather","section":"weather"}""",
            ),
        )
        assertTrue(
            prompt.contains(
                "Do not apply those current-information directives",
            ),
        )
        assertTrue(prompt.contains("Valid ui_directive reasons:"))
        assertTrue(prompt.contains("card_directive"))
        assertTrue(!prompt.contains("Current surface:"))
        assertTrue(!prompt.contains("Current emotion:"))
    }

    @Test
    fun `prompt contract remains English under Chinese UI locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            val prompt = AgentPromptBuilder().build(
                request().copy(
                    personaSections = listOf(
                        "# Mochi\nPractical assistant.",
                        "# User\nPrefers concise answers.",
                        "# Agent Rules\nUse tools carefully.",
                    ),
                ),
            )

            assertTrue(prompt.contains("# Agent Rules"))
            assertTrue(prompt.contains("# Runtime context"))
            assertTrue(prompt.contains("# Android presentation policy"))
            assertTrue(prompt.contains("Return only JSON"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `returns structured final reply`() = runBlocking {
        val client = QueueChatClient(
            finalResponse("""{"reply":"Hello","emotion":"happy"}"""),
        )
        val orchestrator = orchestrator(client)

        val result = orchestrator.run(request())

        assertEquals("Hello", result.reply)
        assertEquals("happy", result.emotion)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `executes tool and continues with tool response message`() = runBlocking {
        val client = QueueChatClient(
            toolResponse("call_1", "echo", """{"value":"calendar"}"""),
            finalResponse("""{"reply":"Done","emotion":"neutral"}"""),
        )
        val registry = ToolRegistry(listOf(EchoTool()))
        val orchestrator = orchestrator(client, registry)

        val result = orchestrator.run(request())

        assertEquals("Done", result.reply)
        assertEquals(2, client.requests.size)
        val toolMessage = client.requests[1].messages.last()
        assertEquals("tool", toolMessage.role)
        assertEquals("call_1", toolMessage.toolCallId)
        assertTrue(toolMessage.content.orEmpty().contains("calendar"))
    }

    @Test
    fun `adds transient image message when provider image input is enabled`() =
        runBlocking {
            val client = QueueChatClient(
                toolResponse("call_1", "camera_image", "{}"),
                toolResponse("call_2", "camera_image", "{}"),
                finalResponse("""{"reply":"I see it","emotion":"neutral"}"""),
            )
            val orchestrator = orchestrator(
                client,
                ToolRegistry(listOf(CameraImageTool())),
            )

            orchestrator.run(
                request().copy(
                    provider = OpenAiProviderConfig(
                        endpoint = "https://example.com/v1",
                        apiKey = "test-key",
                        model = "vision-model",
                        imageInputEnabled = true,
                    ),
                    context = context.copy(
                        modelImageInputAllowed = true,
                    ),
                ),
            )

            val messages = client.requests[1].messages
            assertEquals("tool", messages[messages.lastIndex - 1].role)
            val imageMessage = messages.last()
            assertEquals("user", imageMessage.role)
            assertEquals("text", imageMessage.contentParts?.first()?.type)
            assertEquals("image_url", imageMessage.contentParts?.last()?.type)
            assertTrue(
                imageMessage.contentParts?.last()?.imageUrl?.url
                    ?.startsWith("data:image/jpeg;base64,") == true,
            )
            assertTrue(
                messages[messages.lastIndex - 1].content
                    .orEmpty()
                    .contains("modelImages")
                    .not(),
            )
            assertTrue(
                client.requests[2].messages.none {
                    it.contentParts?.any { part ->
                        part.type == "image_url"
                    } == true
                },
            )
            assertEquals(
                1,
                client.requests.sumOf { request ->
                    request.messages.count { message ->
                        message.contentParts?.any { part ->
                            part.type == "image_url"
                        } == true
                    }
                },
            )
        }

    @Test
    fun `does not add image message when provider image input is disabled`() =
        runBlocking {
            val client = QueueChatClient(
                toolResponse("call_1", "camera_image", "{}"),
                finalResponse("""{"reply":"Shown locally","emotion":"neutral"}"""),
            )
            val orchestrator = orchestrator(
                client,
                ToolRegistry(listOf(CameraImageTool())),
            )

            orchestrator.run(request())

            assertEquals("tool", client.requests[1].messages.last().role)
            assertTrue(
                client.requests[1].messages.none {
                    it.contentParts?.any { part ->
                        part.type == "image_url"
                    } == true
                },
            )
        }

    @Test
    fun `reports pipeline stages around tool execution`() = runBlocking {
        val stages = mutableListOf<Pair<AgentPipelineStage, String?>>()
        val client = QueueChatClient(
            toolResponse("call_1", "echo", "{}"),
            finalResponse("""{"reply":"Done","emotion":"neutral"}"""),
        )
        val orchestrator = orchestrator(
            client = client,
            registry = ToolRegistry(listOf(EchoTool())),
            observer = AgentPipelineObserver { stage, detail ->
                stages += stage to detail
            },
        )

        orchestrator.run(request())

        assertEquals(
            listOf(
                AgentPipelineStage.SKILLING to null,
                AgentPipelineStage.THINKING to null,
                AgentPipelineStage.TOOL to "echo",
                AgentPipelineStage.SUMMARY to null,
            ),
            stages,
        )
    }

    @Test
    fun `reports privacy safe execution diagnostics`() = runBlocking {
        val events = mutableListOf<AgentDiagnosticEvent>()
        val times = ArrayDeque(listOf(100L, 110L, 125L, 150L))
        val client = QueueChatClient(
            toolResponse("call_1", "echo", """{"secret":"private"}"""),
            finalResponse("""{"reply":"Done","emotion":"neutral"}"""),
        )
        val orchestrator = orchestrator(
            client = client,
            registry = ToolRegistry(listOf(EchoTool())),
            diagnosticLogger = AgentDiagnosticLogger(events::add),
            elapsedRealtimeMillis = { times.removeFirst() },
            runIdProvider = { "run-123" },
        )

        orchestrator.run(request())

        assertEquals(
            listOf(
                AgentDiagnosticEventType.RUN_STARTED,
                AgentDiagnosticEventType.MODEL_ROUND_STARTED,
                AgentDiagnosticEventType.TOOL_STARTED,
                AgentDiagnosticEventType.TOOL_FINISHED,
                AgentDiagnosticEventType.MODEL_ROUND_STARTED,
                AgentDiagnosticEventType.RUN_COMPLETED,
            ),
            events.map { it.type },
        )
        assertTrue(events.all { it.runId == "run-123" })
        assertTrue(events.all { it.actor == "main" })
        assertEquals("echo", events[2].toolName)
        assertEquals("ok", events[3].toolStatus)
        assertEquals(15L, events[3].durationMs)
        assertEquals(2, events.last().modelRound)
        assertEquals(1, events.last().toolRound)
        assertEquals(1, events.last().toolCall)
        assertTrue(events.none { it.toString().contains("private") })
        assertTrue(events.none { it.toString().contains("Hello Mochi") })
    }

    @Test
    fun `unknown tool result is returned to provider for recovery`() = runBlocking {
        val client = QueueChatClient(
            toolResponse("call_1", "missing_tool", "{}"),
            finalResponse("""{"reply":"I cannot do that","emotion":"neutral"}"""),
        )
        val orchestrator = orchestrator(client)

        val result = orchestrator.run(request())

        assertEquals("I cannot do that", result.reply)
        assertTrue(
            client.requests[1].messages.last().content.orEmpty()
                .contains("UNKNOWN_TOOL"),
        )
    }

    @Test
    fun `applies validated final ui directive`() = runBlocking {
        var applied: NavigationDecision? = null
        val client = QueueChatClient(
            finalResponse(
                """
                {
                  "reply":"Tomorrow is ready",
                  "emotion":"neutral",
                  "ui_directive":{
                    "surface":"calendar_day",
                    "reason":"other_date",
                    "date":"2026-08-01",
                    "section":"agenda"
                  }
                }
                """.trimIndent(),
            ),
        )
        val orchestrator = orchestrator(
            client = client,
            sink = { decision -> applied = decision },
        )

        val result = orchestrator.run(request())

        assertNotNull(applied)
        assertEquals(LocalDate.of(2026, 8, 1), result.uiDirective?.date)
    }

    @Test
    fun `returns validated card directive without adding a tool`() = runBlocking {
        val client = QueueChatClient(
            finalResponse(
                """
                {
                  "reply":"Keep the next step small.",
                  "emotion":"neutral",
                  "card_directive":{
                    "type":"insight",
                    "placement":"home",
                    "title":"Next step"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = orchestrator(client).run(request())

        assertEquals(CardType.INSIGHT, result.card?.type)
        assertEquals(CardPlacement.HOME, result.card?.placement)
        assertEquals("Next step", result.card?.title)
    }

    @Test
    fun `binds card sources from successful tool evidence`() = runBlocking {
        val client = QueueChatClient(
            toolResponse(
                "call_1",
                "browser_navigate",
                """{"operation":"goto","url":"https://example.com"}""",
            ),
            finalResponse(
                """
                {
                  "reply":"I found an official source.",
                  "emotion":"neutral",
                  "card_directive":{
                    "type":"research_summary",
                    "placement":"inline",
                    "sources":["web_search"]
                  }
                }
                """.trimIndent(),
            ),
        )
        val orchestrator = orchestrator(
            client = client,
            registry = ToolRegistry(listOf(SearchTool())),
        )

        val result = orchestrator.run(request())

        assertEquals("Official result", result.card?.sources?.single()?.title)
        assertEquals(CardPlacement.HOME, result.card?.placement)
        assertEquals(
            "https://example.com/result",
            result.card?.sources?.single()?.url,
        )
    }

    @Test
    fun `invalid optional card keeps accepted text reply`() = runBlocking {
        val client = QueueChatClient(
            finalResponse(
                """
                {
                  "reply":"The text answer remains available.",
                  "emotion":"neutral",
                  "card_directive":{
                    "type":"insight",
                    "actions":[
                      {"type":"open_talk"},
                      {"type":"open_today"},
                      {"type":"dismiss"},
                      {"type":"expand"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = orchestrator(client).run(request())

        assertEquals("The text answer remains available.", result.reply)
        assertEquals(null, result.card)
    }

    @Test
    fun `tool navigation remains effective for card precedence`() = runBlocking {
        val applied = NavigationDecision(
            directive = com.example.mochi_pet.core.navigation.UiDirective(
                surface = "today",
            ),
            intent = com.example.mochi_pet.core.navigation.MochiNavigationIntent
                .ShowToday,
        )
        val client = QueueChatClient(
            finalResponse(
                """
                {
                  "reply":"Today is ready.",
                  "emotion":"neutral",
                  "card_directive":{
                    "type":"insight",
                    "placement":"home"
                  }
                }
                """.trimIndent(),
            ),
        )
        val orchestrator = orchestrator(
            client = client,
            appliedDecision = { applied },
        )

        val result = orchestrator.run(request())

        assertEquals("today", result.uiDirective?.surface)
        assertEquals(CardPlacement.DEFERRED, result.card?.placement)
    }

    @Test
    fun `repairs one malformed final response without tools`() = runBlocking {
        val client = QueueChatClient(
            finalResponse("not-json"),
            finalResponse("""{"reply":"Recovered","emotion":"neutral"}"""),
        )
        val orchestrator = orchestrator(client)

        val result = orchestrator.run(request())

        assertEquals("Recovered", result.reply)
        assertEquals(2, client.requests.size)
        assertTrue(client.requests[1].tools.isEmpty())
        assertTrue(
            client.requests[1].messages.last().content.orEmpty()
                .contains("Do not call tools"),
        )
    }

    @Test
    fun `rejects a second malformed final response`() {
        val client = QueueChatClient(
            finalResponse("not-json"),
            finalResponse("still-not-json"),
        )

        assertThrows(AgentProtocolException::class.java) {
            runBlocking { orchestrator(client).run(request()) }
        }
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `stops when provider exceeds tool round limit`() {
        val client = QueueChatClient(
            toolResponse("call_1", "echo", "{}"),
            toolResponse("call_2", "echo", "{}"),
        )
        val orchestrator = orchestrator(
            client = client,
            registry = ToolRegistry(listOf(EchoTool())),
            maxToolRounds = 1,
        )

        assertThrows(AgentToolRoundLimitException::class.java) {
            runBlocking { orchestrator.run(request()) }
        }
    }

    @Test
    fun `supports thirty round child budget`() = runBlocking {
        val orchestrator = orchestrator(
            client = QueueChatClient(
                finalResponse("""{"reply":"Done","emotion":"neutral"}"""),
            ),
            maxToolRounds = 30,
        )

        assertEquals("Done", orchestrator.run(request()).reply)
    }

    @Test
    fun `propagates cancellation`() {
        val orchestrator = orchestrator(CancellingChatClient())

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator.run(request()) }
        }
    }

    private fun orchestrator(
        client: OpenAiChatClient,
        registry: ToolRegistry = ToolRegistry(emptyList()),
        sink: suspend (NavigationDecision) -> Unit = {},
        maxToolRounds: Int = 5,
        observer: AgentPipelineObserver = AgentPipelineObserver { _, _ -> },
        appliedDecision: () -> NavigationDecision? = { null },
        diagnosticLogger: AgentDiagnosticLogger =
            AgentDiagnosticLogger { },
        elapsedRealtimeMillis: () -> Long =
            { System.nanoTime() / 1_000_000L },
        runIdProvider: () -> String = { "test-run" },
    ): AgentOrchestrator =
        AgentOrchestrator(
            chatClient = client,
            toolRegistry = registry,
            navigationPolicy = NavigationPolicy(),
            uiDirectiveSink = sink,
            appliedNavigationDecision = appliedDecision,
            promptBuilder = AgentPromptBuilder(
                Clock.fixed(
                    Instant.parse("2026-07-31T10:00:00Z"),
                    ZoneOffset.UTC,
                ),
            ),
            maxToolRounds = maxToolRounds,
            pipelineObserver = observer,
            diagnosticLogger = diagnosticLogger,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            runIdProvider = runIdProvider,
        )

    private fun request(): AgentRunRequest =
        AgentRunRequest(
            provider = provider,
            query = "Hello Mochi",
            currentEmotion = "neutral",
            context = context,
        )
}

private class QueueChatClient(
    vararg responses: OpenAiChatResponse,
) : OpenAiChatClient {
    private val remaining = ArrayDeque(responses.toList())
    val requests = mutableListOf<OpenAiChatRequest>()

    override suspend fun complete(
        config: OpenAiProviderConfig,
        request: OpenAiChatRequest,
    ): OpenAiChatResponse {
        requests += request
        return remaining.removeFirst()
    }
}

private class CancellingChatClient : OpenAiChatClient {
    override suspend fun complete(
        config: OpenAiProviderConfig,
        request: OpenAiChatRequest,
    ): OpenAiChatResponse = throw CancellationException("cancelled")
}

private class EchoTool : AgentTool {
    override val name: String = "echo"
    override val schema: JsonObject = functionToolSchema(
        name = name,
        description = "Echo test arguments",
        properties = buildJsonObject {},
        required = emptyList(),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope = ToolResultEnvelope.success(arguments)
}

private class CameraImageTool : AgentTool {
    override val name: String = "camera_image"
    override val schema: JsonObject = functionToolSchema(
        name = name,
        description = "Return a transient test image",
        properties = buildJsonObject {},
        required = emptyList(),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope =
        ToolResultEnvelope.success(
            data = buildJsonObject {
                put("image_available", true)
            },
            modelImages = if (context.modelImageInputAllowed) {
                listOf(
                    ModelImageAttachment(
                        mimeType = "image/jpeg",
                        bytes = byteArrayOf(1, 2, 3),
                    ),
                )
            } else {
                emptyList()
            },
        )
}

private class SearchTool : AgentTool {
    override val name: String = "browser_navigate"
    override val schema: JsonObject = functionToolSchema(
        name = name,
        description = "Search test",
        properties = buildJsonObject {},
        required = emptyList(),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope =
        ToolResultEnvelope.success(
            buildJsonObject {
                put(
                    "results",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("title", "Official result")
                                put("url", "https://example.com/result")
                                put("snippet", "Evidence")
                                put("source", "bing")
                            },
                        )
                    },
                )
            },
        )
}

private fun finalResponse(content: String): OpenAiChatResponse =
    OpenAiChatResponse(
        choices = listOf(
            OpenAiChoice(
                message = OpenAiChatMessage(
                    role = "assistant",
                    content = content,
                ),
            ),
        ),
    )

private fun toolResponse(
    id: String,
    name: String,
    arguments: String,
): OpenAiChatResponse =
    OpenAiChatResponse(
        choices = listOf(
            OpenAiChoice(
                message = OpenAiChatMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        OpenAiToolCall(
                            id = id,
                            function = OpenAiFunctionCall(
                                name = name,
                                arguments = arguments,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
