package com.example.mochi_pet.core.agent.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun interface JavaScriptExecutor {
    suspend fun execute(
        code: String,
        input: JsonElement,
    ): JavaScriptExecutionResult
}

data class JavaScriptExecutionResult(
    val result: JsonElement,
    val durationMillis: Long,
)

class JavaScriptExecutionException(
    val errorCode: ToolErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class SandboxedJavaScriptTool(
    private val executor: JavaScriptExecutor,
) : AgentTool {
    override val name: String = "run_sandboxed_javascript"

    override val schema: JsonObject = functionToolSchema(
        name = name,
        description =
            "Run a short, pure JavaScript function body in an isolated local " +
                "sandbox. Use only for bounded calculations or JSON transforms. " +
                "The code must return a JSON-compatible value. No network, files, " +
                "packages, Android APIs, or other tools are available.",
        properties = buildJsonObject {
            put(
                "code",
                buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "JavaScript function body. Read JSON from input and " +
                            "explicitly return a JSON-compatible value.",
                    )
                },
            )
            put(
                "input",
                buildJsonObject {
                    put(
                        "description",
                        "Optional JSON value exposed to the code as input.",
                    )
                },
            )
        },
        required = listOf("code"),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        val code = arguments.requiredString("code")
        if (code.length > MAX_CODE_CHARS) {
            throw ToolInputException(
                "code must contain at most $MAX_CODE_CHARS characters",
            )
        }
        val execution = try {
            executor.execute(
                code = code,
                input = arguments["input"] ?: JsonNull,
            )
        } catch (error: JavaScriptExecutionException) {
            return ToolResultEnvelope.error(
                code = error.errorCode,
                message = error.message ?: "JavaScript execution failed",
            )
        }
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("result", execution.result)
                put("duration_ms", execution.durationMillis)
            },
        )
    }
}

private const val MAX_CODE_CHARS = 16_000
