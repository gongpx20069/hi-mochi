package com.example.mochi_pet.core.agent

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.createAgentRunner
import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatMessage
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.OpenAiChatResponse
import com.example.mochi_pet.core.agent.llm.OpenAiChoice
import com.example.mochi_pet.core.agent.llm.OpenAiFunctionCall
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.OpenAiToolCall
import com.example.mochi_pet.core.agent.tool.ToolDiscovery
import com.example.mochi_pet.core.agent.tool.ToolDisclosurePolicy
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.tools.readyToolNames
import java.time.LocalDate
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
class ToolSearchDiagnosticTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as MochiApplication

    @Test fun inspectAvailableCatalog() = runBlocking {
        assumeTrue(arguments.getString("mochiToolRetrievalDiagnostic") == "true")
        val names = app.toolCatalogRepository.loadSummary().readyToolNames().sorted()
        Log.i(TAG, "available_count=${names.size}")
        names.forEach { Log.i(TAG, "available_tool=$it") }
    }

    @Test fun measureRetrievalCases() = runBlocking<Unit> {
        assumeTrue(arguments.getString("mochiToolRetrievalDiagnostic") == "true")
        val source = app.cacheDir.resolve("tool-retrieval-cases.json")
        check(source.length() in 1..65_536) { "A bounded, pre-labelled query fixture is required" }
        val cases = Json.parseToJsonElement(source.readText()).jsonArray.toMutableList()
        val ready = app.toolCatalogRepository.loadSummary().readyToolNames()
        (ready - ToolDisclosurePolicy.baseNames).sorted().forEach { name ->
            cases += buildJsonObject {
                put("group", "exact")
                put("query", name)
                put("expected", JsonArray(listOf(JsonPrimitive(name))))
            }
        }
        var current = buildJsonObject {}
        var round = 0
        var started = 0L
        var result: JsonObject? = null
        val rows = mutableListOf<JsonObject>()
        val client = object : OpenAiChatClient {
            override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
                val names = request.tools.mapTo(mutableSetOf(), ToolDiscovery::schemaName)
                if (round++ == 0) {
                    check(names.all { it in ToolDisclosurePolicy.baseNames }) { "Deferred schema leaked into initial request" }
                    started = SystemClock.elapsedRealtime()
                    return response(OpenAiChatMessage("assistant", toolCalls = listOf(
                        OpenAiToolCall("search", function = OpenAiFunctionCall("tool_search", buildJsonObject {
                            put("query", current.getValue("query"))
                            put("limit", 5)
                        }.toString())),
                    )))
                }
                check(round == 2) { "Retrieval diagnostic must execute only one search" }
                val envelope = Json.parseToJsonElement(
                    checkNotNull(request.messages.last { it.toolCallId == "search" }.content),
                ).jsonObject
                val found = (envelope["data"] as? JsonObject)?.get("loaded_tools")?.jsonArray
                    ?.map { it.jsonObject.getValue("name").jsonPrimitive.content }.orEmpty()
                check(found.all { it in names }) { "Search results did not become callable on the next request" }
                result = buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("status", envelope.getValue("status"))
                    envelope["code"]?.let { put("code", it) }
                    put("top5", JsonArray(found.map(::JsonPrimitive)))
                    put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                }
                return response(OpenAiChatMessage("assistant", """{"reply":"diagnostic","emotion":"neutral"}"""))
            }
        }
        val runner = app.createAgentRunner(
            sink = { error("Retrieval must not navigate") }, observer = AgentPipelineObserver { _, _ -> },
            onWeatherLoaded = { error("Retrieval must not execute weather") },
            includeBrowser = true, taskTitle = "Tool retrieval diagnostic", chatClient = client,
        )
        val config = OpenAiProviderConfig(endpoint = "https://example.invalid", apiKey = "unused", model = "local")
        for (case in cases) {
            current = case.jsonObject
            val expected = current.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
            if (expected.isNotEmpty() && expected.none { it in ready }) {
                rows += buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("status", "skipped_unavailable")
                }
                continue
            }
            round = 0
            result = null
            withTimeout(30_000) { runner.run(runRequest(config, "Run the local retrieval diagnostic.")) }
            rows += checkNotNull(result)
        }
        val report = JsonArray(rows).toString()
        app.cacheDir.resolve("tool-retrieval-results.json").writeText(report)
        Log.i(TAG, "retrieval_complete cases=${rows.size} catalog=${ready.size} scripted_agent_model_calls=0 business_calls=0")
    }

    @Test fun liveReadOnlyWeatherLoop() = runBlocking<Unit> {
        assumeTrue(arguments.getString("mochiToolSearchLiveDiagnostic") == "true")
        val before = app.providerSettingsRepository.loadProfiles()
        val speechBefore = app.speechSettingsRepository.loadProfiles()
        check(app.toolCatalogRepository.isToolAvailable("amap_weather")) { "Amap weather must already be enabled" }
        val config = app.providerSettingsRepository.loadRuntimeConfig().forForegroundRequest()
        var rounds = 0
        var searchCalls = 0
        var weatherCalls = 0
        var weatherSucceeded = false
        val client = object : OpenAiChatClient {
            override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
                check(++rounds <= 5) { "Live diagnostic exceeded its request budget" }
                val names = request.tools.mapTo(mutableSetOf(), ToolDiscovery::schemaName)
                if (rounds == 1) check("amap_weather" !in names && "tool_search" in names)
                request.messages.filter { it.role == "tool" && it.name == "amap_weather" }.forEach {
                    weatherSucceeded = Json.parseToJsonElement(checkNotNull(it.content))
                        .jsonObject["status"]?.jsonPrimitive?.content == "ok"
                }
                val started = SystemClock.elapsedRealtime()
                val response = app.openAiChatClient.complete(config, request)
                response.choices.single().message.toolCalls.orEmpty().forEach { call ->
                    check(call.type == "function" && call.function.name in setOf("tool_search", "amap_weather")) {
                        "Model requested an operation outside the diagnostic read-only allowlist"
                    }
                    when (call.function.name) {
                        "tool_search" -> searchCalls++
                        "amap_weather" -> {
                            check(++weatherCalls == 1 && searchCalls > 0 && "amap_weather" in names)
                            check(Json.parseToJsonElement(call.function.arguments).jsonObject["adcode"]
                                ?.jsonPrimitive?.content == "310000") { "Only the fixed public Shanghai query is allowed" }
                        }
                    }
                }
                Log.i(TAG, "model_round=$rounds protocol=${config.apiProtocol} tools=${names.size} " +
                    "schema_bytes=${ToolDisclosurePolicy.arrayBytes(request.tools)} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                return response
            }
        }
        val runner = app.createAgentRunner(
            sink = { error("Live diagnostic must not navigate") }, observer = AgentPipelineObserver { _, _ -> },
            onWeatherLoaded = { error("Do not access device location") },
            includeBrowser = true, taskTitle = "Read-only tool search diagnostic", chatClient = client,
        )
        val reply = withTimeout(120_000) {
            runner.run(runRequest(config,
                "请用高德查询上海（行政区划代码310000）的天气预报。只读查询，不要访问设备定位或操作其他应用。"))
        }
        check(searchCalls > 0 && weatherCalls == 1 && weatherSucceeded && reply.reply.isNotBlank()) {
            "The live search, loaded-tool execution and successful result chain was not completed"
        }
        check(before == app.providerSettingsRepository.loadProfiles()) { "Provider settings changed" }
        check(speechBefore == app.speechSettingsRepository.loadProfiles()) { "Speech settings changed" }
        Log.i(TAG, "live_complete search_calls=$searchCalls weather_calls=$weatherCalls weather_ok=$weatherSucceeded")
    }

    private fun runRequest(config: OpenAiProviderConfig, query: String) = AgentRunRequest(
        provider = config, query = query, currentEmotion = "neutral",
        context = ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation),
    )

    private fun response(message: OpenAiChatMessage) = OpenAiChatResponse(listOf(OpenAiChoice(message)))

    private companion object {
        const val TAG = "MochiToolSearch"
    }
}
