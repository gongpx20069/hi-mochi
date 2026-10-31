package com.example.mochi_pet.core.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.mcp.NOTION_SERVER_ID
import com.example.mochi_pet.core.mcp.FEISHU_SERVER_ID
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import com.example.mochi_pet.core.mcp.McpServerRuntime
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentToolCatalogDiagnosticTest {
    @Test
    fun createAndEditIndependentSmartCanvas(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiTencentWriteDiagnostic") == "true")
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MochiApplication
        val repository = application.toolCatalogRepository
        val before = repository.loadSummary()
        assertNull("Tencent catalog refresh failed", before.feedback)
        val registry = ToolRegistry(repository.loadEnabledMcpTools())
        val names = setOf("tencent_docs_create_smartcanvas_by_mdx", "tencent_docs_smartcanvas_read", "tencent_docs_smartcanvas_edit")
        check(registry.names.containsAll(names)) { "Current Smart Canvas tools must already be enabled" }
        val receipt = application.cacheDir.resolve("mochi-smartcanvas-write-diagnostic.json")
        check(!receipt.exists()) { "A previous fixture receipt exists; inspect it before authorizing another creation" }
        val title = "Mochi SmartCanvas check ${UUID.randomUUID().toString().take(8)}"
        val original = "Mochi initial body."
        val appended = "Mochi appended body."
        receipt.writeText(buildJsonObject { put("title", title); put("stage", "creating") }.toString())
        val created = registry.callTencent("create_smartcanvas_by_mdx", buildJsonObject {
            put("title", title); put("content_format", "markdown"); put("mdx", "# Body check\n\n$original")
        })
        val fileId = checkNotNull(created.findString("file_id")) { "Creation did not return a file_id" }
        val fileUrl = created.findString("file_url").orEmpty()
        receipt.writeText(buildJsonObject { put("title", title); put("file_id", fileId); put("file_url", fileUrl) }.toString())
        Log.i("MochiDocumentTools", "fixture_created title=$title file_id=$fileId file_url=$fileUrl")
        val readArguments = buildJsonObject { put("file_id", fileId) }
        val initialRead = registry.callTencent("smartcanvas_read", readArguments)
        check(initialRead.toString().contains(original)) { "Created body was not present on readback" }
        registry.callTencent("smartcanvas_edit", buildJsonObject {
            put("file_id", fileId); put("action", "INSERT_AFTER")
            put("content", "<Paragraph>\n    $appended\n</Paragraph>")
        })
        val after = registry.callTencent("smartcanvas_read", readArguments)
        check(after.toString().contains(original) && after.toString().contains(appended)) {
            "Append was not verified or original content was lost"
        }
        updateFixture(registry, fileId)
        check(before == repository.loadSummary()) { "Tool settings changed during write diagnostic" }
        Log.i("MochiDocumentTools", "fixture_verified create_body=true append=true update=true original_preserved=true")
    }

    @Test
    fun updatePreviouslyCreatedFixture(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiTencentUpdateFixtureDiagnostic") == "true")
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MochiApplication
        val receipt = Json.parseToJsonElement(application.cacheDir.resolve("mochi-smartcanvas-write-diagnostic.json").readText()).jsonObject
        check(receipt["title"]?.jsonPrimitive?.content?.matches(Regex("Mochi SmartCanvas check [0-9a-f]{8}")) == true)
        val repository = application.toolCatalogRepository
        assertNull(repository.loadSummary().feedback)
        updateFixture(ToolRegistry(repository.loadEnabledMcpTools()), checkNotNull(receipt["file_id"]).jsonPrimitive.content)
    }

    private suspend fun updateFixture(registry: ToolRegistry, fileId: String) {
        val args = buildJsonObject { put("file_id", fileId) }
        val before = registry.callTencent("smartcanvas_read", args).findString("content").orEmpty()
        check(before.contains("Mochi initial body.")) { "Fixture original body is missing" }
        val target = Regex("""<Paragraph id="([^"]+)">\s*Mochi appended body\.\s*</Paragraph>""").findAll(before).toList()
        check(target.size == 1) { "Fixture append target is missing or ambiguous; never replay an update" }
        registry.callTencent("smartcanvas_edit", buildJsonObject {
            put("file_id", fileId); put("action", "UPDATE"); put("id", target.single().groupValues[1])
            put("content", "<Paragraph>\n    Mochi updated body.\n</Paragraph>")
        })
        val after = registry.callTencent("smartcanvas_read", args).findString("content").orEmpty()
        check(after.contains("Mochi initial body.") && after.contains("Mochi updated body.") &&
            !after.contains("Mochi appended body.")) { "Targeted update was not verified" }
        Log.i("MochiDocumentTools", "fixture_verified update=true original_preserved=true file_id=$fileId")
    }

    private suspend fun ToolRegistry.callTencent(name: String, arguments: JsonObject): JsonElement {
        val context = ToolExecutionContext(LocalDate.now(), MochiSurface.Conversation)
        val result = execute("tencent_docs_$name", arguments, context)
        check(result.status == "ok") { "Smart Canvas $name failed: ${result.code}" }
        val data = checkNotNull(result.data).jsonObject
        val body = data["structured_content"] ?: Json.parseToJsonElement(checkNotNull(data["text"]).jsonPrimitive.content)
        check(body.findString("error").isNullOrEmpty()) { "Smart Canvas $name returned an embedded error" }
        return body
    }

    @Test
    fun inspectLiveTencentTools(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiTencentDiscoveryDiagnostic") == "true")
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MochiApplication
        val repository = application.toolCatalogRepository
        val before = repository.loadSummary()
        val server = before.servers.single { it.id == TENCENT_DOCS_SERVER_ID }
        check(server.connected && server.enabled) { "Tencent Docs must already be connected and enabled" }
        // Keep the native credential in memory; never serialize the sharing model or log responses.
        val connection = checkNotNull(repository.exportSharedTools(ToolShareSelection(includeTencentDocs = true)).tencentDocs)
        val tools = McpStreamableHttpClient().listTools(McpServerRuntime(
            id = server.id, name = server.name, endpoint = server.endpoint,
            accessToken = connection.token, authorizationHeader = connection.token,
        ))
        Log.i("MochiDocumentTools", "live_provider=${server.id} tools=${tools.size}")
        tools.forEach { tool ->
            val properties = (tool.inputSchema["properties"] as? JsonObject)?.keys.orEmpty()
            Log.i("MochiDocumentTools", "live_tool=${tool.name} properties=${properties.joinToString(",")} required=${tool.inputSchema["required"]}")
            if (tool.name in setOf("create_smartcanvas_by_mdx", "smartcanvas.edit", "smartcanvas.read", "get_content")) {
                Log.i("MochiDocumentTools", "description_tool=${tool.name} description=${tool.description}")
                tool.inputSchema.toString().chunked(2_000).forEachIndexed { index, chunk ->
                    Log.i("MochiDocumentTools", "schema_tool=${tool.name} chunk=$index schema=$chunk")
                }

            }
        }
        check(before == repository.loadSummary()) { "Tool settings changed during read-only discovery" }
    }

    private fun JsonElement.findString(name: String): String? = when (this) {
        is JsonObject -> (this[name] as? JsonPrimitive)?.content
            ?: values.firstNotNullOfOrNull { it.findString(name) }
        is JsonArray -> firstNotNullOfOrNull { it.findString(name) }
        else -> null
    }

    @Test
    fun inspectConnectedDocumentTools(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mochiDocumentToolDiagnostic") == "true")
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MochiApplication
        val summary = application.toolCatalogRepository.loadSummary()
        assertNull("Document Tool discovery failed; inspect connection in Tools", summary.feedback)
        summary.servers.filter { it.id in setOf(NOTION_SERVER_ID, TENCENT_DOCS_SERVER_ID, FEISHU_SERVER_ID) }.forEach { server ->
            Log.i("MochiDocumentTools", "provider=${server.id} connected=${server.connected} enabled=${server.enabled} tools=${server.tools.size}")
            server.tools.forEach { tool ->
                Log.i("MochiDocumentTools", "provider=${server.id} tool=${tool.remoteName} enabled=${tool.enabled}")
            }
        }
    }
}
