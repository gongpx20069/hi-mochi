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
    fun `rejects malformed final response`() {
        val orchestrator = orchestrator(
            QueueChatClient(finalResponse("not-json")),
        )

        assertThrows(AgentProtocolException::class.java) {
            runBlocking { orchestrator.run(request()) }
        }
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
