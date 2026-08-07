package com.example.mochi_pet.core.agent.tool

import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class JavaScriptToolTest {
    private val context = ToolExecutionContext(
        currentDate = LocalDate.of(2026, 8, 1),
        currentSurface = MochiSurface.Conversation,
    )

    @Test
    fun `returns sandbox result and duration`() = runBlocking {
        val tool = SandboxedJavaScriptTool { code, input ->
            assertEquals("return input.value * 2;", code)
            assertEquals("21", input.jsonObject["value"]?.jsonPrimitive?.content)
            JavaScriptExecutionResult(
                result = JsonPrimitive(42),
                durationMillis = 7,
            )
        }

        val result = tool.execute(
            arguments = buildJsonObject {
                put("code", "return input.value * 2;")
                put(
                    "input",
                    buildJsonObject {
                        put("value", 21)
                    },
                )
            },
            context = context,
        )

        assertEquals("ok", result.status)
        assertEquals(
            "42",
            result.data?.jsonObject?.get("result")?.jsonPrimitive?.content,
        )
        assertEquals(
            "7",
            result.data?.jsonObject?.get("duration_ms")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `returns typed sandbox failure without aborting agent`() = runBlocking {
        val tool = SandboxedJavaScriptTool { _, _ ->
            throw JavaScriptExecutionException(
                ToolErrorCode.TIMEOUT,
                "JavaScript execution exceeded 1000ms",
            )
        }

        val result = tool.execute(
            arguments = buildJsonObject {
                put("code", "while (true) {}")
            },
            context = context,
        )

        assertEquals("error", result.status)
        assertEquals(ToolErrorCode.TIMEOUT.name, result.code)
    }

    @Test
    fun `rejects oversized code before invoking sandbox`() = runBlocking {
        var invoked = false
        val registry = ToolRegistry(
            listOf(
                SandboxedJavaScriptTool { _, _ ->
                    invoked = true
                    JavaScriptExecutionResult(JsonPrimitive(1), 1)
                },
            ),
        )

        val result = registry.execute(
            name = "run_sandboxed_javascript",
            arguments = buildJsonObject {
                put("code", "x".repeat(16_001))
            },
            context = context,
        )

        assertEquals(ToolErrorCode.INVALID_ARGS.name, result.code)
        assertEquals(false, invoked)
    }
}
