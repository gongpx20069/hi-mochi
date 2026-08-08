package com.example.mochi_pet.core.agent

import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
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
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentsTest {
    private val context = ToolExecutionContext(
        currentDate = LocalDate.of(2026, 7, 31),
        currentSurface = MochiSurface.Face,
    )

    @Test
    fun `delegate tool validates input and returns structured result`() =
        runBlocking {
            var delegatedType: SubagentType? = null
            var delegatedTask: String? = null
            val registry = ToolRegistry(
                listOf(
                    DelegateAgentTool(
                        SerialSubagentCoordinator(
                            executor = SubagentExecutor { type, task, _ ->
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
    fun `coordinator permits two serial delegations and rejects a third`() =
        runBlocking {
            val calls = mutableListOf<SubagentType>()
            val registry = ToolRegistry(
                listOf(
                    DelegateAgentTool(
                        SerialSubagentCoordinator(
                            executor = SubagentExecutor { type, _, _ ->
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
                        executor = SubagentExecutor { _, _, _ ->
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
            executor = SubagentExecutor { _, _, _ ->
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
            )
        }

        started.await()
        execution.cancelAndJoin()

        assertTrue(cancelled)
    }
}
