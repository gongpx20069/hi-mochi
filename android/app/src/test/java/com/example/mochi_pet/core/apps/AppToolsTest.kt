package com.example.mochi_pet.core.apps

import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.extensions.ExtensionToolScope
import com.example.mochi_pet.core.media.MediaFailure
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppToolsTest {
    private val context = ToolExecutionContext(LocalDate.of(2026, 11, 3), MochiSurface.Conversation)
    private val platform = FakeAppPlatform()
    private var enabled = true
    private val runtime = AppToolRuntime(platform)
    private fun registry() = ToolRegistry(runtime.tools(ExtensionToolScope.FOREGROUND_MAIN) { enabled })

    @Test fun `discovery and opening are independent of media and accessibility but UI needs assistance`() = runTest {
        val registry = registry()
        assertEquals(APP_TOOL_NAMES, registry.names)
        assertTrue(runtime.tools(ExtensionToolScope.SCHEDULED) { true }.isEmpty())
        assertTrue(runtime.tools(ExtensionToolScope.SUBAGENT) { true }.isEmpty())
        platform.assistance = false
        assertEquals("ok", registry.execute("app_read", "{}", context).status)
        assertEquals("NOT_FOUND", registry.execute("app_navigate", """{"app_id":"guessed","action":"open"}""", context).code)
        assertTrue(platform.actions.isEmpty())
        val result = registry.execute("app_navigate", """{"app_id":"app-1","action":"open"}""", context)
        assertEquals("ok", result.status)
        assertEquals("false", result.data!!.jsonObject.getValue("verified").jsonPrimitive.content)
        assertEquals("PERMISSION_DENIED", registry.execute("app_read", """{"app_id":"app-1"}""", context).code)
        assertEquals(listOf("open"), platform.actions)
    }

    @Test fun `read click input and scroll share references within one registry but not across registries`() = runTest {
        val first = registry()
        val second = registry()
        assertEquals("ok", first.execute("app_read", """{"app_id":"app-1"}""", context).status)
        val click = """{"app_id":"app-1","ref":"ref-1"}"""
        assertEquals("CONFLICT", second.execute("app_click", click, context).code)
        assertEquals("ok", first.execute("app_click", click, context).status)
        assertEquals("CONFLICT", first.execute("app_click", click, context).code)
        first.execute("app_read", """{"app_id":"app-1"}""", context)
        assertEquals("ok", first.execute("app_input", """{"app_id":"app-1","ref":"ref-2","text":"song"}""", context).status)
        first.execute("app_read", """{"app_id":"app-1"}""", context)
        assertEquals("ok", first.execute("app_scroll", """{"app_id":"app-1","ref":"ref-3","direction":"forward"}""", context).status)
        assertEquals(listOf("CLICK", "INPUT", "SCROLL_FORWARD"), platform.actions)
    }

    @Test fun `invalid inputs and live revocation never dispatch actions`() = runTest {
        val registry = registry()
        for ((name, args) in listOf(
            "app_read" to """{"player_id":"app-1"}""",
            "app_navigate" to """{"app_id":"app-1","action":"return","title":"song"}""",
            "app_click" to """{"app_id":"app-1","ref":"r","x":12}""",
            "app_input" to """{"app_id":"app-1","ref":"r","text":12}""",
            "app_input" to """{"app_id":"app-1","ref":"r","text":"a\nb"}""",
            "app_scroll" to """{"app_id":"app-1","ref":"r","direction":"sideways"}""",
        )) assertEquals(name, "INVALID_ARGS", registry.execute(name, args, context).code)
        platform.beforeDispatch = { enabled = false }
        assertEquals("PERMISSION_DENIED",
            registry.execute("app_navigate", """{"app_id":"app-1","action":"open"}""", context).code)
        assertTrue(platform.actions.isEmpty())
    }

    @Test fun `return reports navigation separately without implying playback or retrying`() = runTest {
        val blocked = registry().execute("app_navigate", """{"app_id":"app-1","action":"return"}""", context)
        assertEquals("error", blocked.status)
        assertEquals("CONFLICT", blocked.code)
        assertTrue(blocked.message!!.contains("Background pop-up permission"))
        assertEquals(listOf("return"), platform.actions)
        platform.returned = true
        val result = registry().execute("app_navigate", """{"app_id":"app-1","action":"return"}""", context)
        assertEquals("ok", result.status)
        assertEquals("true", result.data!!.jsonObject.getValue("returnedToMochi").jsonPrimitive.content)
        assertFalse("playbackVerified" in result.data.jsonObject)
        assertEquals(listOf("return", "return"), platform.actions)
    }

    private class FakeAppPlatform : AppPlatform {
        var assistance = true
        var returned = false
        var beforeDispatch: () -> Unit = {}
        val actions = mutableListOf<String>()
        private var owner: String? = null
        private var currentRef: String? = null
        private var reads = 0
        override suspend fun apps() = AppCatalog(listOf(AppInfo("app-1", "fixture.reader", "Reader", true)), assistance, assistance)
        override suspend fun open(appId: String, checkActive: suspend () -> Unit) {
            beforeDispatch()
            checkActive()
            actions += "open"
        }
        override suspend fun returnToMochi(checkActive: suspend () -> Unit): Boolean {
            checkActive()
            actions += "return"
            return returned
        }
        override suspend fun appUi(
            appId: String, action: AppUiAction, ref: String?, text: String?, owner: String,
            checkActive: suspend () -> Unit,
        ): AppUiSnapshot? {
            checkActive()
            if (!assistance) throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "No app assistance")
            if (action == AppUiAction.READ) {
                this.owner = owner
                val newRef = "ref-${++reads}"
                currentRef = newRef
                return AppUiSnapshot(listOf(AppUiElement(newRef, "Search", listOf("click", "input", "scroll_forward"))), false)
            }
            if (action == AppUiAction.RETURN) return null
            if (this.owner != owner || currentRef != ref || ref == null) {
                throw MediaFailure(ToolErrorCode.CONFLICT, "Stale or foreign reference")
            }
            currentRef = null
            actions += action.name
            return null
        }
    }
}
