package com.example.mochi_pet.core.agent

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.createAgentRunner
import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.OpenAiChatResponse
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.OpenAiProviderException
import com.example.mochi_pet.core.agent.llm.OpenAiToolCall
import com.example.mochi_pet.core.agent.tool.ToolDiscovery
import com.example.mochi_pet.core.agent.tool.ToolDisclosurePolicy
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolSchemaLanguage
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.tools.readyToolNames
import java.time.LocalDate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveToolSearchQueryDiagnosticTest {
    private class DiagnosticStop(val outcome: String) : RuntimeException(outcome)

    @Test fun measureModelGeneratedQueries() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiLiveToolQueryDiagnostic") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MochiApplication
        val source = app.cacheDir.resolve("live-tool-query-cases.json")
        check(source.length() in 1..65_536) { "A bounded pre-labelled synthetic query fixture is required" }
        val cases = Json.parseToJsonElement(source.readText()).jsonArray
        check(cases.size in 1..60) { "Live evaluation allows at most sixty cases" }
        val before = app.providerSettingsRepository.loadProfiles()
        val config = app.providerSettingsRepository.loadRuntimeConfig().forForegroundRequest()
        val ready = app.toolCatalogRepository.loadSummary().readyToolNames()
        val startedAt = System.currentTimeMillis()
        val rows = mutableListOf<JsonObject>()
        val destination = app.cacheDir.resolve("live-tool-query-results.json")
        fun save(complete: Boolean) {
            destination.writeText(buildJsonObject {
                put("complete", complete)
                put("started_epoch_ms", startedAt)
                put("model", config.model)
                put("protocol", config.apiProtocol.name)
                put("model_timeout_seconds", config.timeoutSeconds)
                put("catalog_ready_count", ready.size)
                put("case_count", cases.size)
                put("private_history_persona_memory_included", false)
                put("business_calls_executed", 0)
                put("rows", JsonArray(rows))
            }.toString())
        }
        save(false)
        for (value in cases) {
            val case = value.jsonObject
            val query = case.getValue("query").jsonPrimitive.content
            val expected = case.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
            if (expected.isNotEmpty() && expected.none { it in ready }) {
                rows += buildJsonObject {
                    case.forEach { (key, item) -> put(key, item) }
                    put("status", "skipped_unavailable")
                }
                save(false)
                continue
            }
            val modelDurations = mutableListOf<Long>()
            val requestedNames = mutableListOf<String>()
            val searchCalls = linkedMapOf<String, OpenAiToolCall>()
            val searchResults = mutableListOf<JsonObject>()
            val toolDurations = mutableMapOf<String, Long>()
            var activeTool: String? = null
            var toolStarted = 0L
            var continuationStarted = 0L
            var lastSearchResponseAt = 0L
            var firstModelAt = 0L
            var searchResultsAt = 0L
            var nextSchemaMs = 0L
            var initialToolCount = 0
            var modelRounds = 0
            val client = object : OpenAiChatClient {
                override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
                    val now = SystemClock.elapsedRealtime()
                    val names = request.tools.mapTo(mutableSetOf(), ToolDiscovery::schemaName)
                    if (firstModelAt == 0L) {
                        firstModelAt = now
                        initialToolCount = names.size
                        check(names.all { it in ToolDisclosurePolicy.baseNames }) { "Deferred schema leaked before search" }
                    }
                    if (searchCalls.isNotEmpty()) {
                        searchResultsAt = now
                        nextSchemaMs = now - continuationStarted
                        searchCalls.forEach { (id, call) ->
                            val envelope = Json.parseToJsonElement(
                                checkNotNull(request.messages.last { it.toolCallId == id }.content),
                            ).jsonObject
                            val found = (envelope["data"] as? JsonObject)?.get("loaded_tools")?.jsonArray
                                ?.map { it.jsonObject.getValue("name").jsonPrimitive.content }.orEmpty()
                            check(found.all { it in names }) { "Search results did not become callable" }
                            val args = Json.parseToJsonElement(call.function.arguments).jsonObject
                            searchResults += buildJsonObject {
                                put("arguments", args)
                                put("query_english_characters", ToolSchemaLanguage.isEnglishText(
                                    args["query"]?.jsonPrimitive?.content.orEmpty(),
                                ))
                                put("status", envelope.getValue("status"))
                                envelope["code"]?.let { put("code", it) }
                                put("returned_tools", JsonArray(found.map(::JsonPrimitive)))
                            }
                        }
                        throw DiagnosticStop("searched")
                    }
                    if (++modelRounds > 4) throw DiagnosticStop("model_round_limit")
                    val modelStarted = SystemClock.elapsedRealtime()
                    val response = try {
                        app.openAiChatClient.complete(config, request)
                    } finally {
                        modelDurations += SystemClock.elapsedRealtime() - modelStarted
                    }
                    val calls = response.choices.single().message.toolCalls.orEmpty()
                    requestedNames += calls.map { it.function.name }
                    if (calls.isEmpty()) throw DiagnosticStop("replied_without_search")
                    // Reject the entire response before dispatch; even a sibling business call must not execute.
                    if (calls.any { it.type != "function" || it.function.name !in setOf("load_skill", "tool_search") }) {
                        throw DiagnosticStop("blocked_business_call")
                    }
                    calls.filter { it.function.name == "tool_search" }.forEach { searchCalls[it.id] = it }
                    if (searchCalls.isNotEmpty()) lastSearchResponseAt = SystemClock.elapsedRealtime()
                    return response
                }
            }
            val factoryStarted = SystemClock.elapsedRealtime()
            val runner = app.createAgentRunner(
                sink = { error("Live query evaluation must not navigate") },
                observer = AgentPipelineObserver { stage, detail ->
                    val now = SystemClock.elapsedRealtime()
                    if (stage == AgentPipelineStage.TOOL || stage == AgentPipelineStage.SUMMARY) {
                        activeTool?.let { tool ->
                            toolDurations[tool] = (toolDurations[tool] ?: 0L) + now - toolStarted
                        }
                        activeTool = null
                    }
                    if (stage == AgentPipelineStage.TOOL) {
                        activeTool = detail
                        toolStarted = now
                    }
                    if (stage == AgentPipelineStage.SUMMARY) continuationStarted = now
                },
                onWeatherLoaded = { error("Live query evaluation must not access weather or location") },
                includeBrowser = true,
                taskTitle = "Model-generated Tool query diagnostic",
                chatClient = client,
            )
            val runStarted = SystemClock.elapsedRealtime()
            var failureType: String? = null
            val status = try {
                withTimeout(120_000) {
                    runner.run(AgentRunRequest(
                        provider = config, query = query, currentEmotion = "neutral",
                        context = ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation),
                    ))
                }
                error("The diagnostic must stop before a business operation or final reply")
            } catch (stop: DiagnosticStop) {
                stop.outcome
            } catch (error: OpenAiProviderException) {
                failureType = error.javaClass.simpleName
                "provider_error"
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failureType = error.javaClass.simpleName
                "case_timeout"
            }
            rows += buildJsonObject {
                case.forEach { (key, item) -> put(key, item) }
                put("status", status)
                failureType?.let { put("error_type", it) }
                put("factory_ms", runStarted - factoryStarted)
                put("initial_request_ms", firstModelAt - runStarted)
                put("initial_tools_count", initialToolCount)
                put("model_request_ms", JsonArray(modelDurations.map(::JsonPrimitive)))
                put("requested_tools", JsonArray(requestedNames.map(::JsonPrimitive)))
                put("searches", JsonArray(searchResults))
                put("tool_phase_ms", buildJsonObject { toolDurations.forEach { (name, ms) -> put(name, ms) } })
                put("run_ms", SystemClock.elapsedRealtime() - runStarted)
                if (searchResultsAt > 0) {
                    put("search_to_next_request_ms", searchResultsAt - lastSearchResponseAt)
                    put("next_schema_ms", nextSchemaMs)
                    put("run_to_results_ms", searchResultsAt - runStarted)
                    put("model_start_to_results_ms", searchResultsAt - firstModelAt)
                }
            }
            save(false)
            Log.i("MochiToolSearch", "live_query_progress cases=${rows.size}/${cases.size} status=$status model_calls=${modelDurations.size}")
        }
        check(before == app.providerSettingsRepository.loadProfiles()) { "Provider configuration changed during evaluation" }
        save(true)
        Log.i("MochiToolSearch", "live_query_complete cases=${rows.size} business_calls=0")
    }
}
