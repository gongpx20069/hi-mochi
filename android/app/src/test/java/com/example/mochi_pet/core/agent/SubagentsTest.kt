package com.example.mochi_pet.core.agent

import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatMessage
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.OpenAiChatResponse
import com.example.mochi_pet.core.agent.llm.OpenAiChoice
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.agent.tool.ModelImageAttachment
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentsTest {
    private val context = ToolExecutionContext(
        currentDate = LocalDate.of(2026, 7, 31),
        currentSurface = MochiSurface.Face,
    )

    @Test
    fun `subagent roles explicitly support deeper investigation`() {
        assertTrue(
            SubagentType.RESEARCHER.instructions.contains("deeper research"),
        )
        assertTrue(
            SubagentType.ANALYST.instructions.contains(
                "deeper research and analysis",
            ),
        )
    }

    @Test
    fun `isolated image analysis sends no Tool schemas`() = runBlocking {
        val client = RecordingSubagentImageClient(
            OpenAiChatResponse(
                choices = listOf(
                    OpenAiChoice(
                        OpenAiChatMessage(
                            role = "assistant",
                            content = "No person is visible near the door.",
                        ),
                    ),
                ),
            ),
        )

        val result = IsolatedSubagentImageAnalyzer(client).analyze(
            type = SubagentType.ANALYST,
            task = "Describe the latest event",
            provider = provider(),
            image = ModelImageAttachment(
                mimeType = "image/jpeg",
                bytes = byteArrayOf(1, 2, 3),
            ),
        )

        assertEquals("No person is visible near the door.", result)
        assertTrue(client.request.tools.isEmpty())
        assertEquals(
            1,
            client.request.messages.count { message ->
                message.contentParts?.any { it.type == "image_url" } == true
            },
        )
    }

    @Test
    fun `isolated image analysis rejects echoed raw image data`() {
        val client = RecordingSubagentImageClient(
            OpenAiChatResponse(
                choices = listOf(
                    OpenAiChoice(
                        OpenAiChatMessage(
                            role = "assistant",
                            content = "data:image/jpeg;base64,AQID",
                        ),
                    ),
                ),
            ),
        )

        assertThrows(AgentProtocolException::class.java) {
            runBlocking {
                IsolatedSubagentImageAnalyzer(client).analyze(
                    type = SubagentType.RESEARCHER,
                    task = "Describe the latest event",
                    provider = provider(),
                    image = ModelImageAttachment(
                        mimeType = "image/jpeg",
                        bytes = byteArrayOf(1, 2, 3),
                    ),
                )
            }
        }
    }

    @Test
    fun `isolated image analysis rejects wrapped partial Base64`() {
        val bytes = ByteArray(256) { index -> index.toByte() }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val wrappedPartial = encoded
            .substring(80, 176)
            .chunked(24)
            .joinToString("\n")
        val client = RecordingSubagentImageClient(
            OpenAiChatResponse(
                choices = listOf(
                    OpenAiChoice(
                        OpenAiChatMessage(
                            role = "assistant",
                            content = wrappedPartial,
                        ),
                    ),
                ),
            ),
        )

        assertThrows(AgentProtocolException::class.java) {
            runBlocking {
                IsolatedSubagentImageAnalyzer(client).analyze(
                    type = SubagentType.RESEARCHER,
                    task = "Describe the latest event",
                    provider = provider(),
                    image = ModelImageAttachment(
                        mimeType = "image/jpeg",
                        bytes = bytes,
                    ),
                )
            }
        }
    }

    @Test
    fun `delegate tool validates input and returns structured result`() =
        runBlocking {
            var delegatedType: SubagentType? = null
            var delegatedTask: String? = null
            val registry = ToolRegistry(
                listOf(
                    DelegateAgentTool(
                        SerialSubagentCoordinator(
                            executor = SubagentExecutor { type, task, _, _ ->
                                delegatedType = type
                                delegatedTask = task
                                "Evidence report"
                            },
                        ),
                    ),
                ),
            )

            val invalid = registry.execute(
                name = "delegate_agent",
                arguments = buildJsonObject {
                    put("agent", "writer")
                    put("task", "Research the source")
                },
                context = context,
            )
            val valid = registry.execute(
                name = "delegate_agent",
                arguments = buildJsonObject {
                    put("agent", "researcher")
                    put("task", " Research the source ")
                },
                context = context,
            )

            assertEquals(ToolErrorCode.INVALID_ARGS.name, invalid.code)
            assertEquals(SubagentType.RESEARCHER, delegatedType)
            assertEquals("Research the source", delegatedTask)
            assertEquals("ok", valid.status)
            assertEquals(
                "researcher",
                valid.data?.jsonObject
                    ?.get("agent")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "Evidence report",
                valid.data?.jsonObject
                    ?.get("result")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `validated image can be handed to only one subagent`() = runBlocking {
        val delegatedImages = mutableListOf<ModelImageAttachment?>()
        context.modelImageRelay.offer(
            ModelImageAttachment(
                mimeType = "image/jpeg",
                bytes = byteArrayOf(1, 2, 3),
            ),
        )
        val registry = ToolRegistry(
            listOf(
                DelegateAgentTool(
                    SerialSubagentCoordinator(
                        executor = SubagentExecutor { _, _, _, image ->
                            delegatedImages += image
                            "Image report"
                        },
                    ),
                ),
            ),
        )

        val first = registry.execute(
            name = "delegate_agent",
            arguments = buildJsonObject {
                put("agent", "analyst")
                put("task", "Analyze the attached event image")
                put("include_image", true)
            },
            context = context,
        )
        val second = registry.execute(
            name = "delegate_agent",
            arguments = buildJsonObject {
                put("agent", "researcher")
                put("task", "Analyze the attached event image again")
                put("include_image", true)
            },
            context = context,
        )

        assertEquals("ok", first.status)
        assertEquals(
            true,
            first.data?.jsonObject
                ?.get("image_included")
                ?.jsonPrimitive
                ?.content
                ?.toBoolean(),
        )
        assertEquals(
            byteArrayOf(1, 2, 3).toList(),
            delegatedImages.single()?.bytes?.toList(),
        )
        assertEquals(ToolErrorCode.NOT_FOUND.name, second.code)
    }

    private class RecordingSubagentImageClient(
        private val response: OpenAiChatResponse,
    ) : OpenAiChatClient {
        lateinit var request: OpenAiChatRequest

        override suspend fun complete(
            config: OpenAiProviderConfig,
            request: OpenAiChatRequest,
        ): OpenAiChatResponse {
            this.request = request
            return response
        }
    }

    private fun provider(): OpenAiProviderConfig =
        OpenAiProviderConfig(
            endpoint = "https://example.com/v1",
            apiKey = "test-key",
            model = "vision-model",
            imageInputEnabled = true,
        )

    @Test
    fun `coordinator permits two serial delegations and rejects a third`() =
        runBlocking {
            val calls = mutableListOf<SubagentType>()
            val registry = ToolRegistry(
                listOf(
                    DelegateAgentTool(
                        SerialSubagentCoordinator(
                            executor = SubagentExecutor { type, _, _, _ ->
                                calls += type
                                type.displayName
                            },
                        ),
                    ),
                ),
            )

            listOf("researcher", "analyst").forEach { agent ->
                val result = registry.execute(
                    name = "delegate_agent",
                    arguments = buildJsonObject {
                        put("agent", agent)
                        put("task", "Complete step")
                    },
                    context = context,
                )
                assertEquals("ok", result.status)
            }
            val rejected = registry.execute(
                name = "delegate_agent",
                arguments = buildJsonObject {
                    put("agent", "researcher")
                    put("task", "Complete step three")
                },
                context = context,
            )

            assertEquals(
                listOf(SubagentType.RESEARCHER, SubagentType.ANALYST),
                calls,
            )
            assertEquals(ToolErrorCode.CONFLICT.name, rejected.code)
        }

    @Test
    fun `child tool limit returns recoverable tool error`() = runBlocking {
        val registry = ToolRegistry(
            listOf(
                DelegateAgentTool(
                    SerialSubagentCoordinator(
                        executor = SubagentExecutor { _, _, _, _ ->
                            throw AgentToolRoundLimitException(30)
                        },
                    ),
                ),
            ),
        )

        val result = registry.execute(
            name = "delegate_agent",
            arguments = buildJsonObject {
                put("agent", "researcher")
                put("task", "Research all seven companies")
            },
            context = context,
        )

        assertEquals("error", result.status)
        assertEquals(ToolErrorCode.CONFLICT.name, result.code)
        assertTrue(result.message.orEmpty().contains("Researcher"))
    }

    @Test
    fun `parent cancellation cancels active subagent`() = runBlocking {
        var cancelled = false
        val started = CompletableDeferred<Unit>()
        val coordinator = SerialSubagentCoordinator(
            executor = SubagentExecutor { _, _, _, _ ->
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
        )
        val execution = async {
            coordinator.execute(
                SubagentType.RESEARCHER,
                "Wait for evidence",
                context,
                null,
            )
        }

        started.await()
        execution.cancelAndJoin()

        assertTrue(cancelled)
    }
}
