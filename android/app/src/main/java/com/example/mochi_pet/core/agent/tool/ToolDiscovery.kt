package com.example.mochi_pet.core.agent.tool

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ToolDisclosurePolicy {
    const val SEARCH_NAME = "tool_search"
    const val MAX_VISIBLE_BYTES = 128 * 1024
    const val BASE_REGRESSION_BYTES = 32 * 1024
    val baseNames = setOf(
        SEARCH_NAME, "load_skill", "request_user_assistance", "recall_memory",
        "navigate_mochi_ui", "manage_mochi_calendar", "manage_mochi_schedule",
        "get_current_location", "get_current_weather",
        "browser_read", "browser_navigate", "browser_click", "browser_input", "browser_scroll",
        "app_read", "app_navigate", "app_click", "app_input", "app_scroll",
        "media_read", "media_play_from_search", "media_control", "media_volume",
        "run_sandboxed_javascript", "delegate_agent",
    )

    val searchSchema = functionToolSchema(
        name = SEARCH_NAME,
        description = "Search this actor's enabled tools by capability or exact name; does not execute them. " +
            "Use for maps, documents, connected services, extensions or other tools not already supplied. " +
            "Write queries in English with the service, operation and object; translate the user's wording first. " +
            "Exact tool names are also accepted. Matched complete definitions are added to the NEXT " +
            "model request; do not call newly found tools in this same response. Refine the query on a miss.",
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "English capability query (service, operation, object) or exact tool name, not just the user's topic.")
                put("minLength", 1)
                put("maxLength", 512)
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 8)
                put("description", "Maximum matching tools to load; default 5.")
            })
        },
        required = listOf("query"),
    )

    fun arrayBytes(schemas: List<JsonObject>): Int =
        JsonArray(schemas).toString().toByteArray(Charsets.UTF_8).size

    // Responses flattening removes 13 bytes; adding the default ,"strict":false adds 15.
    fun budgetBytes(schemas: List<JsonObject>): Int = arrayBytes(schemas) + 2 * schemas.size
}

class ToolDiscovery(
    private val registry: ToolRegistry,
    baseNames: Set<String> = ToolDisclosurePolicy.baseNames,
    private val maxVisibleBytes: Int = ToolDisclosurePolicy.MAX_VISIBLE_BYTES,
) {
    private val loaded = registry.names.intersect(baseNames).toMutableSet()
    private val index = ToolSearchIndex(registry.schemas)
    private val searchRegistry = ToolRegistry(listOf(object : AgentTool {
        override val name = ToolDisclosurePolicy.SEARCH_NAME
        override val schema = ToolDisclosurePolicy.searchSchema
        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext) = search(arguments)
    }))

    init {
        require(ToolDisclosurePolicy.SEARCH_NAME !in registry.names) { "tool_search is reserved for local discovery" }
        require(maxVisibleBytes > ToolDisclosurePolicy.budgetBytes(listOf(ToolDisclosurePolicy.searchSchema))) {
            "Tool disclosure budget cannot contain the search entry"
        }
    }

    suspend fun schemas(): List<JsonObject> {
        val available = registry.availableSchemas()
        val availableNames = available.mapTo(mutableSetOf(), ::schemaName)
        loaded.retainAll(availableNames)
        val visible = available.filter { schemaName(it) in loaded } + ToolDisclosurePolicy.searchSchema
        check(ToolDisclosurePolicy.budgetBytes(visible) <= maxVisibleBytes) {
            "Base Tool definitions exceed the model-visible schema budget"
        }
        return visible
    }

    suspend fun execute(
        name: String,
        argumentsJson: String,
        context: ToolExecutionContext,
        suppliedNames: Set<String>,
    ): ToolResultEnvelope {
        currentCoroutineContext().ensureActive()
        if (name != ToolDisclosurePolicy.SEARCH_NAME && name !in registry.names) {
            return ToolResultEnvelope.error(ToolErrorCode.UNKNOWN_TOOL, "Unknown tool: $name")
        }
        if (name !in suppliedNames) {
            return ToolResultEnvelope.error(
                ToolErrorCode.PERMISSION_DENIED,
                "Tool was not supplied in this model request. Use tool_search and wait for the next round.",
            )
        }
        return if (name == ToolDisclosurePolicy.SEARCH_NAME) {
            searchRegistry.execute(name, argumentsJson, context)
        } else {
            registry.execute(name, argumentsJson, context)
        }
    }

    private suspend fun search(arguments: JsonObject): ToolResultEnvelope {
        if (arguments.keys.any { it !in setOf("query", "limit") }) throw ToolInputException("Unexpected search fields")
        val query = (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?: throw ToolInputException("query must be a string")
        if (query.isBlank() || query.length > 512) throw ToolInputException("query must contain 1-512 characters")
        val limit = if ("limit" in arguments) {
            (arguments["limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?.takeIf { it in 1..8 } ?: throw ToolInputException("limit must be an integer from 1 to 8")
        } else 5
        val available = registry.availableSchemas().associateBy(::schemaName)
        loaded.retainAll(available.keys)
        val ranked = index.search(query).filter { it in available }
        if (ranked.isEmpty()) {
            return ToolResultEnvelope.error(
                ToolErrorCode.NOT_FOUND,
                "No available tool matched. Try a capability, service name, English synonym or exact tool name.",
            )
        }
        val selected = mutableListOf<JsonObject>()
        val omitted = mutableListOf<JsonObject>()
        var bytes = ToolDisclosurePolicy.budgetBytes(
            available.filterKeys { it in loaded }.values.toList() + ToolDisclosurePolicy.searchSchema,
        )
        for (name in ranked.take(limit)) {
            val schema = available.getValue(name)
            val schemaBytes = schema.toString().toByteArray(Charsets.UTF_8).size
            val additionalBytes = if (name in loaded) 0 else schemaBytes + 3
            if (bytes + additionalBytes > maxVisibleBytes) {
                omitted += buildJsonObject { put("name", name); put("schema_bytes", schemaBytes) }
            } else {
                selected += schema
                bytes += additionalBytes
            }
        }
        // Recheck after suspending availability reads before publishing any selection.
        val live = registry.availableSchemas().mapTo(mutableSetOf(), ::schemaName)
        currentCoroutineContext().ensureActive()
        loaded.retainAll(live)
        val accepted = selected.filter { schemaName(it) in live }
        if (accepted.isEmpty()) {
            return ToolResultEnvelope.error(
                if (omitted.isNotEmpty()) ToolErrorCode.CONFLICT else ToolErrorCode.PERMISSION_DENIED,
                if (omitted.isNotEmpty()) {
                    "Matching complete schemas exceed the run's $maxVisibleBytes-byte Tool budget. " +
                        "Narrow the task or start a separate task; no definitions were truncated."
                } else "Matching tools became unavailable. Check connection and Tool settings.",
            ).copy(data = buildJsonObject { put("omitted_for_budget", JsonArray(omitted)) })
        }
        loaded += accepted.map(::schemaName)
        return ToolResultEnvelope.success(buildJsonObject {
            put("loaded_tools", JsonArray(accepted.map { schema ->
                val function = schema.getValue("function").jsonObject
                buildJsonObject {
                    put("name", schemaName(schema))
                    put("description", function["description"]?.jsonPrimitive?.content.orEmpty().take(256))
                }
            }))
            put("omitted_for_budget", JsonArray(omitted))
            put("more_matches", ranked.size > limit)
            put("notice", "Complete definitions are supplied in the next request's tools. Search does not execute them.")
        })
    }

    companion object {
        fun schemaName(schema: JsonObject): String =
            schema.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
    }
}

internal class ToolSearchIndex(schemas: List<JsonObject>) {
    private data class Entry(val name: String, val nameTokens: Set<String>, val counts: Map<String, Int>, val length: Int)
    private val entries = schemas.map { schema ->
        val name = ToolDiscovery.schemaName(schema)
        val description = schema.getValue("function").jsonObject["description"]?.jsonPrimitive?.content.orEmpty()
        val names = tokens(name)
        val words = names + names + names + tokens(description.take(4096))
        Entry(name, names.toSet(), words.groupingBy { it }.eachCount(), words.size.coerceAtLeast(1))
    }
    private val averageLength = entries.map { it.length }.average().takeIf { it.isFinite() } ?: 1.0
    private val documentFrequency = entries.flatMap { it.counts.keys }.groupingBy { it }.eachCount()

    fun search(query: String): List<String> {
        val terms = tokens(query).toSet()
        return entries.map { entry ->
            val exact = entry.name.equals(query, ignoreCase = true)
            val score = terms.sumOf { term ->
                val count = entry.counts[term]?.toDouble() ?: return@sumOf 0.0
                val frequency = documentFrequency.getValue(term)
                val idf = ln(1.0 + (entries.size - frequency + 0.5) / (frequency + 0.5))
                val bm25 = idf * count * 2.2 / (count + 1.2 * (0.25 + 0.75 * entry.length / averageLength))
                bm25 + if (term in entry.nameTokens) idf else 0.0
            }
            Triple(entry.name, exact, score)
        }.filter { it.second || it.third > 0.0 }
            .sortedWith(compareByDescending<Triple<String, Boolean, Double>> { it.second }
                .thenByDescending { it.third }.thenBy { it.first })
            .map { it.first }
    }

    private fun tokens(value: String): List<String> {
        val text = Normalizer.normalize(value.replace(CAMEL_CASE, "$1 $2"), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val words = WORD.findAll(text).flatMap { match ->
            val word = match.value
            if (word.first().code > 127) {
                if (word.length == 1) sequenceOf(word) else word.windowed(2).asSequence()
            } else sequenceOf(if (word.length > 3) word.removeSuffix("s") else word)
        }.filter { it !in STOP_WORDS }.toMutableList()
        ALIASES.forEach { (canonical, aliases) ->
            if (aliases.any(text::contains)) words += canonical
        }
        return words
    }

    private companion object {
        val CAMEL_CASE = Regex("([a-z])([A-Z])")
        val WORD = Regex("[a-z0-9]+|[\\p{IsHan}]+")
        val STOP_WORDS = setOf("a", "an", "the", "to", "of", "for", "and", "or", "with", "tool", "please")
        val ALIASES = mapOf(
            "tencent" to listOf("腾讯"), "feishu" to listOf("飞书"), "notion" to listOf("笔记"),
            "smartsheet" to listOf("智能表"), "sheet" to listOf("表格", "电子表"),
            "field" to listOf("字段", "列"), "record" to listOf("记录"),
            "search" to listOf("搜索", "查找", "检索"), "read" to listOf("读取", "查看"),
            "add" to listOf("添加", "新增"), "create" to listOf("创建", "新建"),
            "update" to listOf("更新", "修改", "编辑"), "delete" to listOf("删除"),
            "document" to listOf("文档"), "file" to listOf("文件"), "folder" to listOf("文件夹", "目录"),
            "amap" to listOf("高德", "地图"), "poi" to listOf("商户", "餐厅", "附近", "地点"),
            "direction" to listOf("路线", "导航"), "weather" to listOf("天气"),
            "mijia" to listOf("米家", "智能家居"), "device" to listOf("设备"),
            "scene" to listOf("场景"), "camera" to listOf("摄像头"),
            "termux" to listOf("终端", "命令", "脚本"), "webdav" to listOf("网盘"),
            "agentlink" to listOf("远程代理", "远程任务"),
        )
    }
}
