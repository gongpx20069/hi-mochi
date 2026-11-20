package com.example.mochi_pet.core.agent.tool

import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ToolDiscoveryTest {
    private val context = ToolExecutionContext(LocalDate.of(2026, 11, 17), MochiSurface.Face)

    @Test fun `batch availability is scoped and execution still checks live permission`() = runBlocking {
        val tools = (1..80).map { fixture("remote_$it", "Read data") }
        var batches = 0
        var individualChecks = 0
        var enabled = true
        val registry = ToolRegistry(tools, availableNames = { requested ->
            batches++
            assertEquals(tools.map { it.name }.toSet(), requested)
            requested + "another_actors_tool"
        }, isAvailable = {
            individualChecks++
            enabled
        })
        assertEquals(80, registry.availableSchemas().size)
        assertEquals(1, batches)
        assertEquals(0, individualChecks)
        enabled = false
        assertEquals("PERMISSION_DENIED", registry.execute("remote_1", "{}", context).code)
        assertEquals(1, individualChecks)
    }

    @Test fun `search rechecks a batch snapshot before publishing newly discovered tools`() = runBlocking {
        val tool = fixture("remote_read", "Read")
        var batches = 0
        val discovery = ToolDiscovery(ToolRegistry(listOf(tool), availableNames = { requested ->
            if (++batches == 1) requested else emptySet()
        }))
        assertEquals("PERMISSION_DENIED", search(discovery, """{"query":"remote_read"}""").code)
        assertEquals(2, batches)
        assertEquals(setOf("tool_search"), discovery.names())
    }

    @Test fun `base apps and media are visible but document schemas require search`() = runBlocking {
        val app = fixture("app_read", "Read apps")
        val media = fixture("media_control", "Playback")
        val document = fixture("tencent_docs_smartsheet_add_fields", "Add fields to a Tencent Docs smartsheet")
        val discovery = ToolDiscovery(ToolRegistry(listOf(app, media, document)))
        val firstNames = discovery.schemas().map(ToolDiscovery::schemaName).toSet()
        assertEquals(setOf(app.name, media.name, "tool_search"), firstNames)
        assertEquals("PERMISSION_DENIED", discovery.execute(document.name, "{}", context, firstNames).code)
        assertEquals("ok", search(discovery, """{"query":"腾讯智能表新增字段","limit":1}""").status)
        assertEquals(document.schema, discovery.schemas().single { ToolDiscovery.schemaName(it) == document.name })
        assertEquals("PERMISSION_DENIED", discovery.execute(document.name, "{}", context, firstNames).code)
        assertEquals("ok", discovery.execute(document.name, "{}", context, discovery.names()).status)
        val before = ToolDisclosurePolicy.arrayBytes(discovery.schemas())
        assertEquals("ok", search(discovery, """{"query":"tencent_docs_smartsheet_add_fields"}""").status)
        assertEquals(before, ToolDisclosurePolicy.arrayBytes(discovery.schemas()))
    }

    @Test fun `ranking uses exact names and Chinese descriptions without indexing nested schema prose`() {
        val schemas = listOf(
            fixture("amap_direction", "Plan driving and walking routes").schema,
            fixture("tencent_docs_smartsheet_add_fields", "Add fields to a spreadsheet").schema,
            fixture("webdav_read", "读取网盘文件").schema,
        )
        val index = ToolSearchIndex(schemas)
        assertEquals("amap_direction", index.search("高德路线").first())
        assertEquals("tencent_docs_smartsheet_add_fields", index.search("新增智能表字段").first())
        assertEquals("webdav_read", index.search("读取网盘文件").first())
        assertEquals("amap_direction", index.search("AMAP_DIRECTION").first())
        assertTrue(index.search("unmatchedzzzz").isEmpty())
    }

    @Test fun `malformed and empty queries fail explicitly and misses do not load everything`() = runBlocking {
        val discovery = ToolDiscovery(ToolRegistry(listOf(fixture("remote_tool", "Remote capability"))))
        for (arguments in listOf(
            "{}", """{"query":""}""", """{"query":12}""", """{"query":"remote","limit":"2"}""",
            """{"query":"remote","limit":0}""", """{"query":"remote","limit":9}""",
            """{"query":"remote","limit":1.5}""", """{"query":"remote","extra":true}""", "[]",
        )) {
            assertEquals(arguments, "INVALID_ARGS", search(discovery, arguments).code)
        }
        assertEquals("NOT_FOUND", search(discovery, """{"query":"unmatchedzzzz"}""").code)
        assertEquals(setOf("tool_search"), discovery.names())
    }

    @Test fun `oversized definition fails without truncation and smaller matches report omission`() = runBlocking {
        val small = fixture("small_fields", "Add fields")
        val large = fixture("large_fields", "Add fields", 5000)
        val maximum = ToolDisclosurePolicy.budgetBytes(listOf(ToolDisclosurePolicy.searchSchema, small.schema))
        val discovery = ToolDiscovery(ToolRegistry(listOf(small, large)), maxVisibleBytes = maximum)
        assertEquals("CONFLICT", search(discovery, """{"query":"large_fields","limit":1}""").code)
        assertEquals(setOf("tool_search"), discovery.names())
        val result = search(discovery, """{"query":"fields","limit":2}""")
        assertEquals("ok", result.status)
        assertTrue(result.data.toString().contains("large_fields"))
        assertEquals(setOf("tool_search", small.name), discovery.names())
        assertEquals(small.schema, discovery.schemas().first())
    }

    @Test fun `revocation removes loaded schemas and blocks calls with old visible names`() = runBlocking {
        val tool = fixture("remote_read", "Read remote data")
        var enabled = true
        val registry = ToolRegistry(listOf(tool)) { enabled }
        val discovery = ToolDiscovery(registry)
        assertEquals("ok", search(discovery, """{"query":"remote_read"}""").status)
        val supplied = discovery.names()
        enabled = false
        assertEquals("PERMISSION_DENIED", discovery.execute(tool.name, "{}", context, supplied).code)
        assertEquals(setOf("tool_search"), discovery.names())
        assertEquals("NOT_FOUND", search(discovery, """{"query":"remote_read"}""").code)
        enabled = true
        assertEquals(setOf("tool_search"), discovery.names())
        assertEquals(setOf("tool_search"), ToolDiscovery(registry).names())
    }

    @Test fun `cancelled search cannot publish definitions after availability suspension`() = runBlocking {
        val tool = fixture("remote_read", "Read")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var block = true
        val discovery = ToolDiscovery(ToolRegistry(listOf(tool)) {
            if (block) {
                entered.complete(Unit)
                release.await()
            }
            true
        })
        val work = async {
            discovery.execute("tool_search", """{"query":"remote_read"}""", context, setOf("tool_search"))
        }
        entered.await()
        work.cancelAndJoin()
        block = false
        release.complete(Unit)
        assertEquals(setOf("tool_search"), discovery.names())
    }

    private suspend fun ToolDiscovery.names() = schemas().mapTo(mutableSetOf(), ToolDiscovery::schemaName)
    private suspend fun search(discovery: ToolDiscovery, arguments: String) =
        discovery.execute("tool_search", arguments, context, setOf("tool_search"))

    private fun fixture(name: String, description: String, padding: Int = 0): AgentTool = object : AgentTool {
        override val name = name
        override val schema = functionToolSchema(name, description, buildJsonObject {
            if (padding > 0) put("value", buildJsonObject {
                put("type", "string")
                put("description", "x".repeat(padding))
            })
        }, emptyList())
        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext) =
            ToolResultEnvelope.success(buildJsonObject { put("executed", true) })
    }
}
