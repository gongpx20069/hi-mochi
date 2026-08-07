package com.example.mochi_pet.platform.javascript

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.example.mochi_pet.core.agent.tool.JavaScriptExecutionResult
import com.example.mochi_pet.core.agent.tool.JavaScriptExecutionException
import com.example.mochi_pet.core.agent.tool.JavaScriptExecutor
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class AndroidJavaScriptExecutor(
    context: Context,
) : JavaScriptExecutor {
    private val applicationContext = context.applicationContext
    private val mutex = Mutex()
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    override suspend fun execute(
        code: String,
        input: JsonElement,
    ): JavaScriptExecutionResult = mutex.withLock {
        if (!JavaScriptSandbox.isSupported()) {
            throw JavaScriptExecutionException(
                ToolErrorCode.PROVIDER_ERROR,
                "The installed Android WebView does not support JavaScript sandboxing",
            )
        }
        var sandbox: JavaScriptSandbox? = null
        var result: JsonElement? = null
        val duration = measureTimeMillis {
            try {
                withTimeout(EXECUTION_TIMEOUT_MS) {
                    sandbox = JavaScriptSandbox
                        .createConnectedInstanceAsync(applicationContext)
                        .await()
                    val activeSandbox = requireNotNull(sandbox)
                    if (!activeSandbox.isFeatureSupported(
                            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
                        )
                    ) {
                        throw JavaScriptExecutionException(
                            ToolErrorCode.PROVIDER_ERROR,
                            "The installed Android WebView lacks required " +
                                "sandbox controls",
                        )
                    }
                    val parameters = IsolateStartupParameters()
                    if (activeSandbox.isFeatureSupported(
                            JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                        )
                    ) {
                        parameters.maxHeapSizeBytes = MAX_HEAP_BYTES
                    } else {
                        throw JavaScriptExecutionException(
                            ToolErrorCode.PROVIDER_ERROR,
                            "The installed Android WebView lacks required " +
                                "sandbox controls",
                        )
                    }
                    if (activeSandbox.isFeatureSupported(
                            JavaScriptSandbox
                                .JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
                        )
                    ) {
                        parameters.maxEvaluationReturnSizeBytes =
                            MAX_RESULT_BYTES
                    } else {
                        throw JavaScriptExecutionException(
                            ToolErrorCode.PROVIDER_ERROR,
                            "The installed Android WebView lacks required " +
                                "sandbox controls",
                        )
                    }
                    activeSandbox.createIsolate(parameters).use { isolate ->
                        val raw = try {
                            isolate.evaluateJavaScriptAsync(
                                buildSandboxedProgram(code, input),
                            ).await()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            throw JavaScriptExecutionException(
                                ToolErrorCode.INVALID_ARGS,
                                error.message
                                    ?: "JavaScript syntax or runtime error",
                                error,
                            )
                        }
                        result = parseSandboxResult(raw)
                    }
                }
            } catch (error: TimeoutCancellationException) {
                throw JavaScriptExecutionException(
                    ToolErrorCode.TIMEOUT,
                    "JavaScript execution exceeded ${EXECUTION_TIMEOUT_MS}ms",
                    error,
                )
            } catch (error: JavaScriptExecutionException) {
                throw error
            } catch (error: ToolInputException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw JavaScriptExecutionException(
                    ToolErrorCode.PROVIDER_ERROR,
                    error.message ?: "JavaScript sandbox failed",
                    error,
                )
            } finally {
                sandbox?.close()
            }
        }
        JavaScriptExecutionResult(
            result = requireNotNull(result),
            durationMillis = duration,
        )
    }

    private fun parseSandboxResult(raw: String): JsonElement {
        if (raw.isBlank()) {
            throw ToolInputException(
                "JavaScript must explicitly return a JSON-compatible value",
            )
        }
        return try {
            json.parseToJsonElement(raw)
        } catch (error: SerializationException) {
            throw ToolInputException(
                "JavaScript returned a value that could not be encoded as JSON",
            )
        }
    }
}

internal fun buildSandboxedProgram(
    code: String,
    input: JsonElement,
): String {
    val encodedInput = JsonPrimitive(input.toString()).toString()
    return """
        (() => {
          "use strict";
          const input = JSON.parse($encodedInput);
          const result = (function(input) {
            "use strict";
            $code
          })(input);
          if (result === undefined) {
            throw new Error(
              "JavaScript must explicitly return a JSON-compatible value"
            );
          }
          return result;
        })()
    """.trimIndent()
}

private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: ExecutionException) {
                    continuation.resumeWithException(
                        error.cause ?: error,
                    )
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            DIRECT_EXECUTOR,
        )
        continuation.invokeOnCancellation {
            cancel(true)
        }
    }

private val DIRECT_EXECUTOR = Executor(Runnable::run)
private const val EXECUTION_TIMEOUT_MS = 1_000L
private const val MAX_HEAP_BYTES = 16L * 1024L * 1024L
private const val MAX_RESULT_BYTES = 64 * 1024
