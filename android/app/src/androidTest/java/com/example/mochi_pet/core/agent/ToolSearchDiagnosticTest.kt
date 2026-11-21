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
import com.example.mochi_pet.core.agent.tool.ToolSearchIndex
import com.example.mochi_pet.core.agent.tool.ToolSchemaLanguage
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.skills.LoadSkillTool
import com.example.mochi_pet.core.skills.prepareForModel
import com.example.mochi_pet.core.skills.resolveTools
import com.example.mochi_pet.core.skills.SkillToolDirectory
import com.example.mochi_pet.core.agent.tool.ToolDirectorySnapshot
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
        val measurementStarted = System.currentTimeMillis()
        listOf("tool-retrieval-results.json", "tool-retrieval-timings.json").forEach { name ->
            val previous = app.cacheDir.resolve(name)
            check(!previous.exists() || previous.delete()) { "Cannot remove the previous diagnostic artifact" }
        }
        val source = app.cacheDir.resolve("tool-retrieval-cases.json")
        val sourceText = if (source.exists()) source.readText() else
            InstrumentationRegistry.getInstrumentation().context.assets.open("tool-search-english-cases.json")
                .bufferedReader().use { it.readText() }
        check(sourceText.length in 1..65_536) { "A bounded, pre-labelled query fixture is required" }
        val cases = Json.parseToJsonElement(sourceText).jsonArray.map { it.jsonObject }.toMutableList()
        val ready = app.toolCatalogRepository.loadSummary().readyToolNames()
        val compareScopes = arguments.getString("mochiCompareSkillScopes") == "true"
        val actorReady = ready + setOf("load_skill", "request_user_assistance", "delegate_agent")
        val scopes = app.skillRepository.listEnabledMetadata(actorReady).associate { metadata ->
            metadata.name to checkNotNull(app.skillRepository.loadEnabledSkill(metadata.name)).resolveTools(actorReady).toolNames
        }
        (ready - ToolDisclosurePolicy.baseNames).sorted().forEach { name ->
            cases += buildJsonObject {
                put("group", "exact")
                put("query", name)
                put("expected", JsonArray(listOf(JsonPrimitive(name))))
                if (compareScopes) {
                    scopes.entries.filter { name in it.value }.minWithOrNull(
                        compareBy<Map.Entry<String, Set<String>>> { it.value.size }.thenBy { it.key },
                    )?.let { put("skill_name", it.key) }
                }
            }
        }
        val measurements = if (compareScopes) cases.flatMapIndexed { caseIndex, case ->
            val global = buildJsonObject {
                case.filterKeys { it != "skill_name" }.forEach { (key, value) -> put(key, value) }
                put("variant", "global")
            }
            if ("skill_name" !in case) listOf(global) else listOf(global, buildJsonObject {
                case.forEach { (key, value) -> put(key, value) }
                put("variant", "scoped")
            }).let { if (caseIndex % 2 == 0) it else it.reversed() }
        } else cases
        var current = buildJsonObject {}
        var round = 0
        var started = 0L
        var runStarted = 0L
        var toolStarted = 0L
        var continuationStarted = 0L
        var initialRequestMs = 0L
        var result: JsonObject? = null
        val rows = mutableListOf<JsonObject>()
        val observedSchemas = linkedMapOf<String, JsonObject>()
        val client = object : OpenAiChatClient {
            override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
                val requestReadyAt = SystemClock.elapsedRealtime()
                request.tools.filter { ToolDiscovery.schemaName(it) != ToolDisclosurePolicy.SEARCH_NAME }
                    .forEach { observedSchemas[ToolDiscovery.schemaName(it)] = it }
                val names = request.tools.mapTo(mutableSetOf(), ToolDiscovery::schemaName)
                if (round++ == 0) {
                    check(names.all { it in ToolDisclosurePolicy.baseNames }) { "Deferred schema leaked into initial request" }
                    started = requestReadyAt
                    initialRequestMs = started - runStarted
                    return response(OpenAiChatMessage("assistant", toolCalls = listOf(
                        OpenAiToolCall("search", function = OpenAiFunctionCall("tool_search", buildJsonObject {
                            put("query", current.getValue("query"))
                            put("limit", 5)
                            current["skill_name"]?.let { put("skill_name", it) }
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
                val data = envelope["data"] as? JsonObject
                val duplicatedDescriptions = if (data == null || "loaded_tools" !in data) envelope else
                    JsonObject(envelope + ("data" to JsonObject(data + ("loaded_tools" to JsonArray(found.map { name ->
                        val schema = request.tools.single { ToolDiscovery.schemaName(it) == name }
                        buildJsonObject {
                            put("name", name)
                            put("description", schema.getValue("function").jsonObject["description"]?.jsonPrimitive?.content.orEmpty().take(256))
                        }
                    })))))
                result = buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("status", envelope.getValue("status"))
                    envelope["code"]?.let { put("code", it) }
                    put("top5", JsonArray(found.map(::JsonPrimitive)))
                    put("elapsed_ms", requestReadyAt - started)
                    put("initial_request_ms", initialRequestMs)
                    put("tool_phase_ms", continuationStarted - toolStarted)
                    put("next_request_ms", requestReadyAt - continuationStarted)
                    put("next_tools_bytes", ToolDisclosurePolicy.arrayBytes(request.tools))
                    put("result_bytes", envelope.toString().toByteArray(Charsets.UTF_8).size)
                    put("result_with_descriptions_bytes", duplicatedDescriptions.toString().toByteArray(Charsets.UTF_8).size)
                    (envelope["data"] as? JsonObject)?.get("candidate_count")?.let { put("candidate_count", it) }
                }
                return response(OpenAiChatMessage("assistant", """{"reply":"diagnostic","emotion":"neutral"}"""))
            }
        }
        suspend fun createRunner() = app.createAgentRunner(
            sink = { error("Retrieval must not navigate") }, observer = AgentPipelineObserver { stage, detail ->
                if (stage == AgentPipelineStage.TOOL && detail == "tool_search") toolStarted = SystemClock.elapsedRealtime()
                if (stage == AgentPipelineStage.SUMMARY) continuationStarted = SystemClock.elapsedRealtime()
            },
            onWeatherLoaded = { error("Retrieval must not execute weather") },
            includeBrowser = true, taskTitle = "Tool retrieval diagnostic", chatClient = client,
        )
        val cache = app.cacheDir.resolve("english-tool-schemas")
        val cacheEntriesBefore = cache.listFiles { file -> file.extension == "json" }.orEmpty().size
        val preparationStarted = SystemClock.elapsedRealtime()
        Log.i(TAG, "preparation_started cache_entries=$cacheEntriesBefore")
        createRunner()
        val firstPreparationMs = SystemClock.elapsedRealtime() - preparationStarted
        Log.i(TAG, "first_preparation_complete elapsed_ms=$firstPreparationMs")
        val warmPreparationStarted = SystemClock.elapsedRealtime()
        val runner = createRunner()
        val warmPreparationMs = SystemClock.elapsedRealtime() - warmPreparationStarted
        Log.i(TAG, "warm_preparation_complete elapsed_ms=$warmPreparationMs")
        val config = OpenAiProviderConfig(endpoint = "https://example.invalid", apiKey = "unused", model = "local")
        for (case in measurements) {
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
            toolStarted = 0L
            continuationStarted = 0L
            runStarted = SystemClock.elapsedRealtime()
            withTimeout(30_000) { runner.run(runRequest(config, "Run the local retrieval diagnostic.")) }
            rows += buildJsonObject {
                checkNotNull(result).forEach { (key, value) -> put(key, value) }
                put("run_ms", SystemClock.elapsedRealtime() - runStarted)
            }
            app.cacheDir.resolve("tool-retrieval-results.json").writeText(JsonArray(rows).toString())
            if (rows.size % 10 == 0) Log.i(TAG, "retrieval_progress cases=${rows.size}/${measurements.size}")
        }
        val report = JsonArray(rows).toString()
        app.cacheDir.resolve("tool-retrieval-results.json").writeText(report)
        app.cacheDir.resolve("tool-description-audit.json").writeText(buildJsonObject {
            put("tools", JsonArray(observedSchemas.values.map { schema ->
                val function = schema.getValue("function").jsonObject
                buildJsonObject {
                    put("name", function.getValue("name"))
                    put("description", function.getValue("description"))
                }
            }))
            put("skills", buildJsonObject {
                scopes.forEach { (name, tools) -> put(name, JsonArray(tools.sorted().map(::JsonPrimitive))) }
            })
        }.toString())
        val descriptions = observedSchemas.mapValues { (_, schema) ->
            schema.getValue("function").jsonObject.getValue("description").jsonPrimitive.content
        }
        val snapshot = ToolDirectorySnapshot(descriptions, ToolDisclosurePolicy.baseNames)
        val directoryMeasurements = scopes.map { (name, toolNames) ->
            val skill = checkNotNull(app.skillRepository.loadEnabledSkill(name))
            repeat(5) { SkillToolDirectory.page(skill, toolNames, snapshot, null) }
            val samples = List(100) {
                val before = SystemClock.elapsedRealtimeNanos()
                SkillToolDirectory.page(skill, toolNames, snapshot, null)
                JsonPrimitive((SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0)
            }
            val page = SkillToolDirectory.page(skill, toolNames, snapshot, null)
            check(page.nextCursor == null) { "This catalog measurement expects one directory page per Skill" }
            buildJsonObject {
                put("skill_name", name)
                put("tool_count", page.totalCount)
                put("appendix_bytes", page.content.toByteArray(Charsets.UTF_8).size)
                put("render_samples_ms", JsonArray(samples))
            }
        }
        app.cacheDir.resolve("skill-directory-measurements.json").writeText(JsonArray(directoryMeasurements).toString())
        val indexStarted = SystemClock.elapsedRealtimeNanos()
        val index = ToolSearchIndex(observedSchemas.values.toList())
        val indexBuildMs = (SystemClock.elapsedRealtimeNanos() - indexStarted) / 1_000_000.0
        val measuredRows = rows.filter { it["top5"] != null }
        fun candidates(row: JsonObject): Set<String>? =
            row["skill_name"]?.jsonPrimitive?.content?.let { scopes[it].orEmpty() }
        repeat(5) {
            measuredRows.forEach { index.search(it.getValue("query").jsonPrimitive.content, candidates(it)) }
        }
        val indexRows = measuredRows.map { row ->
            val query = row.getValue("query").jsonPrimitive.content
            val samples = List(20) {
                val before = SystemClock.elapsedRealtimeNanos()
                index.search(query, candidates(row))
                JsonPrimitive((SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0)
            }
            buildJsonObject {
                put("group", row.getValue("group"))
                put("query", query)
                row["skill_name"]?.let { put("skill_name", it) }
                put("samples_ms", JsonArray(samples))
                put("snapshot_top5", JsonArray(index.search(query, candidates(row)).take(5).map(::JsonPrimitive)))
                put("actual_top5", row.getValue("top5"))
            }
        }
        app.cacheDir.resolve("tool-retrieval-timings.json").writeText(buildJsonObject {
            put("measurement_started_epoch_ms", measurementStarted)
            put("first_factory_ms", firstPreparationMs)
            put("warm_factory_ms", warmPreparationMs)
            put("cache_entries_before", cacheEntriesBefore)
            put("cache_entries_after", cache.listFiles { file -> file.extension == "json" }.orEmpty().size)
            put("catalog_ready_count", ready.size)
            put("observed_schema_count", observedSchemas.size)
            put("index_build_ms", indexBuildMs)
            put("index_warmup_passes", 5)
            put("index_samples", JsonArray(indexRows))
        }.toString())
        Log.i(TAG, "retrieval_complete cases=${rows.size} catalog=${ready.size} scripted_agent_model_calls=0 business_calls=0")
    }

    @Test fun verifyEnglishSkillInputs() = runBlocking<Unit> {
        assumeTrue(arguments.getString("mochiToolRetrievalDiagnostic") == "true")
        suspend fun available() = app.toolCatalogRepository.loadSummary().readyToolNames() +
            setOf("load_skill", "request_user_assistance", "delegate_agent")
        val metadata = app.skillRepository.listEnabledMetadata(available())
            .prepareForModel(app.englishToolSchemas::prepareInstruction)
        val loader = LoadSkillTool(
            repository = app.skillRepository,
            availableToolNames = available(),
            prepareText = app.englishToolSchemas::prepareInstruction,
            availableToolNamesProvider = { available() },
        )
        metadata.forEach { skill ->
            ToolSchemaLanguage.requireEnglishText(skill.description)
            val result = loader.execute(
                buildJsonObject { put("skill_name", skill.name) },
                ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation),
            )
            check(result.status == "ok") { "Enabled Skill failed instruction preparation" }
            ToolSchemaLanguage.requireEnglishText(result.data!!.jsonObject.getValue("content").jsonPrimitive.content)
        }
        Log.i(TAG, "english_skills_complete count=${metadata.size} business_calls=0")
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
                    check(call.type == "function" && call.function.name in setOf("load_skill", "tool_search", "amap_weather")) {
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
