package com.example.mochi_pet.core.agent.llm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.agent.DelegateAgentTool
import com.example.mochi_pet.core.agent.SerialSubagentCoordinator
import com.example.mochi_pet.core.agent.SubagentExecutor
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ManageMochiCalendarTool
import com.example.mochi_pet.core.agent.tool.SandboxedJavaScriptTool
import com.example.mochi_pet.core.agent.tool.ToolDisclosurePolicy
import com.example.mochi_pet.core.apps.AppToolRuntime
import com.example.mochi_pet.core.browser.RequestUserAssistanceTool
import com.example.mochi_pet.core.browser.agentBrowserTools
import com.example.mochi_pet.core.extensions.ExtensionToolScope
import com.example.mochi_pet.core.location.CurrentLocationTool
import com.example.mochi_pet.core.media.MediaToolRuntime
import com.example.mochi_pet.core.memory.RecallMemoryTool
import com.example.mochi_pet.core.navigation.NavigateMochiUiTool
import com.example.mochi_pet.core.navigation.NavigationPolicy
import com.example.mochi_pet.core.schedule.ManageMochiScheduleTool
import com.example.mochi_pet.core.skills.LoadSkillTool
import com.example.mochi_pet.core.weather.CurrentWeatherTool
import com.example.mochi_pet.platform.browser.AgentBrowserRuntime
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class BaseToolSchemasTest {
    @Test
    fun measureActualSchemasWithoutExecutingTools() {
        val browser = AgentBrowserRuntime(ApplicationProvider.getApplicationContext())
        val previousBase = listOf(
            LoadSkillTool(unusedDependency(), emptySet()),
            RequestUserAssistanceTool(browser),
            RecallMemoryTool(unusedDependency()),
            NavigateMochiUiTool(NavigationPolicy()) { error("Unexpected navigation") },
            ManageMochiCalendarTool(unusedDependency()),
            ManageMochiScheduleTool(unusedDependency(), unusedDependency()),
            CurrentLocationTool(unusedDependency()),
            CurrentWeatherTool(unusedDependency()),
            SandboxedJavaScriptTool(unusedDependency()),
            DelegateAgentTool(SerialSubagentCoordinator(SubagentExecutor { _, _, _, _ ->
                error("Unexpected delegation")
            })),
        ) + agentBrowserTools(browser)
        val apps = AppToolRuntime(unusedDependency()).tools(ExtensionToolScope.FOREGROUND_MAIN) { true }
        val media = MediaToolRuntime(unusedDependency()).tools(ExtensionToolScope.FOREGROUND_MAIN) { true }
        val all = previousBase + apps + media
        assertEquals(15, previousBase.size)
        assertEquals(5, apps.size)
        assertEquals(4, media.size)
        assertEquals(24, all.map(AgentTool::name).distinct().size)
        val search = ToolDisclosurePolicy.searchSchema
        assertEquals(ToolDisclosurePolicy.baseNames, all.map(AgentTool::name).toSet() + ToolDisclosurePolicy.SEARCH_NAME)
        val chatSchemas = all.map(AgentTool::schema) + search
        assertTrue("Base Chat schemas exceed 32 KiB", bytes(chatSchemas) <= ToolDisclosurePolicy.BASE_REGRESSION_BYTES)
        assertTrue("Base Responses schemas exceed 32 KiB",
            bytes(responses(chatSchemas)) <= ToolDisclosurePolicy.BASE_REGRESSION_BYTES)
        val rows = all.map { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("group", when (tool) {
                    in apps -> "app"
                    in media -> "media"
                    else -> "previous_base"
                })
                put("chat_bytes", bytes(tool.schema))
                put("responses_bytes", bytes(responses(listOf(tool.schema)).single().jsonObject))
            }
        }
        val totals = buildJsonObject {
            put("existing_15_chat_array", bytes(previousBase.map(AgentTool::schema)))
            put("existing_24_chat_array", bytes(all.map(AgentTool::schema)))
            put("existing_15_responses_array", bytes(responses(previousBase.map(AgentTool::schema))))
            put("existing_24_responses_array", bytes(responses(all.map(AgentTool::schema))))
            put("base_16_chat_array", bytes(previousBase.map(AgentTool::schema) + search))
            put("base_25_chat_array", bytes(chatSchemas))
            put("base_16_responses_array", bytes(responses(previousBase.map(AgentTool::schema) + search)))
            put("base_25_responses_array", bytes(responses(chatSchemas)))
            put("app_chat_array", bytes(apps.map(AgentTool::schema)))
            put("media_chat_array", bytes(media.map(AgentTool::schema)))
            put("search_chat_item", bytes(search))
            put("search_responses_item", bytes(responses(listOf(search)).single().jsonObject))
        }
        val report = buildJsonObject {
            put("unit", "compact JSON UTF-8 bytes; tools array includes brackets and commas")
            put("scope", "Current source; schemas only; no prompts, history, results, HTTP headers, credentials, or model calls")
            put("search_status", "Actual ordinary-function tool_search definition")
            put("source_commit", System.getProperty("mochi.sourceCommit"))
            put("totals", totals)
            put("tools", JsonArray(rows))
            put("chat_schemas", JsonArray(chatSchemas))
        }
        System.getProperty("mochi.schemaReport")?.let { File(it).writeText(report.toString()) }
        println("SCHEMA_SIZE_TOTALS=$totals")
    }

    private fun responses(schemas: List<JsonObject>): JsonArray {
        val encoded = ResponsesAdapter.encode(
            Json,
            OpenAiProviderConfig(
                endpoint = "https://example.invalid",
                apiKey = "non-secret-unused-fixture",
                model = "schema-measurement",
                apiProtocol = ApiProtocol.RESPONSES,
            ),
            OpenAiChatRequest(model = "schema-measurement", messages = emptyList(), tools = schemas),
        )
        return Json.parseToJsonElement(encoded).jsonObject.getValue("tools").jsonArray
    }

    private fun bytes(value: JsonObject): Int = value.toString().toByteArray(Charsets.UTF_8).size
    private fun bytes(value: JsonArray): Int = value.toString().toByteArray(Charsets.UTF_8).size
    private fun bytes(values: List<JsonObject>): Int = bytes(JsonArray(values))

    private inline fun <reified T> unusedDependency(): T {
        val type = T::class.java
        check(type.isInterface)
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            error("Schema measurement unexpectedly invoked ${type.name}.${method.name}")
        })
    }
}
