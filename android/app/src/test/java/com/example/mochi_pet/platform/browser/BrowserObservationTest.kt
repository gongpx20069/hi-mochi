package com.example.mochi_pet.platform.browser

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.browser.BrowserClickTool
import com.example.mochi_pet.core.browser.BrowserNavigateTool
import com.example.mochi_pet.core.browser.BrowserReadTool
import com.example.mochi_pet.core.browser.UserAssistanceAnswer
import com.example.mochi_pet.core.model.MochiSurface
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowWebView

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserObservationTest {
    private val context = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
    private val empty = JsonObject(emptyMap())

    @Test fun `navigation returns waiting at 30 seconds and the same page can finish at 50 seconds`() = runBlocking {
        withBrowser { runtime, page ->
            val navigation = async(Dispatchers.Main.immediate) {
                BrowserNavigateTool(runtime).execute(buildJsonObject { put("operation", "reload") }, context)
            }
            shadowOf(page).webViewClient.onPageStarted(page, "https://example.com/", null)
            idle(1_200)
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            idle(28_800)
            assertTrue(navigation.isCompleted)
            val waiting = navigation.await()
            assertEquals("ok", waiting.status)
            assertEquals("waiting", waiting.data!!.jsonObject["observation_status"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive(false), waiting.data.jsonObject["snapshot_available"])
            assertTrue(runtime.state.value.loading)
            assertNull(runtime.state.value.error)
            assertFalse(shadowOf(page).wasDestroyCalled())

            val read = async(Dispatchers.Main.immediate) { BrowserReadTool(runtime).execute(empty, context) }
            assertSame(callback, shadowOf(page).lastEvaluatedJavascriptCallback)
            idle(20_000)
            callback.onReceiveValue(snapshot("Complete episode synopsis"))
            assertEquals("Complete episode synopsis", read.await().data!!.jsonObject["markdown"]!!.jsonPrimitive.content)
            assertEquals(1, shadowOf(page).reloadInvocations)
        }
    }

    @Test fun `agent selected long waits are allowed and the next call defaults to 30 seconds again`() = runBlocking {
        withBrowser { runtime, page ->
            val read = async(Dispatchers.Main.immediate) {
                BrowserReadTool(runtime).execute(buildJsonObject { put("wait_seconds", 3_600) }, context)
            }
            idle(301_000)
            assertFalse(read.isCompleted)
            shadowOf(page).lastEvaluatedJavascriptCallback.onReceiveValue(snapshot("Available"))
            assertEquals("ok", read.await().status)
            val next = async(Dispatchers.Main.immediate) { BrowserReadTool(runtime).execute(empty, context) }
            idle(29_000)
            assertFalse(next.isCompleted)
            idle(1_000)
            assertEquals("waiting", next.await().data!!.jsonObject["observation_status"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `readable content returns early while the page is still loading`() = runBlocking {
        withBrowser { runtime, page ->
            val read = async(Dispatchers.Main.immediate) { BrowserReadTool(runtime).execute(empty, context) }
            idle(2_000)
            shadowOf(page).lastEvaluatedJavascriptCallback.onReceiveValue(snapshot("Readable article", loading = true))
            assertTrue(read.isCompleted)
            assertEquals("Readable article", read.await().data!!.jsonObject["markdown"]!!.jsonPrimitive.content)
            assertTrue(runtime.state.value.loading)
        }
    }

    @Test fun `zero wait returns explicitly unavailable state and does not enqueue repeated page scripts`() = runBlocking {
        withBrowser { runtime, page ->
            val args = buildJsonObject { put("wait_seconds", 0) }
            val first = BrowserReadTool(runtime).execute(args, context).data!!.jsonObject
            assertEquals(JsonPrimitive(false), first["snapshot_available"])
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            BrowserReadTool(runtime).execute(args, context)
            assertSame(callback, shadowOf(page).lastEvaluatedJavascriptCallback)
            callback.onReceiveValue(snapshot("Now ready"))
            val ready = BrowserReadTool(runtime).execute(args, context).data!!.jsonObject
            assertEquals(JsonPrimitive(true), ready["snapshot_fresh"])
            assertEquals("Now ready", ready["markdown"]!!.jsonPrimitive.content)
        }
    }

    @Test
    @Config(shadows = [FocusableWebViewShadow::class])
    fun `an unconfirmed click is dispatched once and must be observed before another mutation`() = runBlocking {
        withBrowser { runtime, page ->
            val args = buildJsonObject { put("ref", "e1"); put("wait_seconds", 0) }
            val first = BrowserClickTool(runtime).execute(args, context)
            assertEquals("ok", first.status)
            assertEquals(JsonPrimitive(true), first.data!!.jsonObject["action_pending"])
            yield()
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            val second = BrowserClickTool(runtime).execute(args, context)
            assertEquals("CONFLICT", second.code)
            assertSame(callback, shadowOf(page).lastEvaluatedJavascriptCallback)
            callback.onReceiveValue(JsonPrimitive("""{"ok":true}""").toString())
            idle(500)
            shadowOf(page).lastEvaluatedJavascriptCallback.onReceiveValue(snapshot("Submission received"))
            val result = BrowserReadTool(runtime).execute(buildJsonObject { put("wait_seconds", 0) }, context)
            assertEquals("Submission received", result.data!!.jsonObject["markdown"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive("acknowledged"), result.data.jsonObject["action_outcome"])
        }
    }

    @Test fun `a changed document rejects the old snapshot without losing the new page`() = runBlocking {
        withBrowser { runtime, page ->
            BrowserReadTool(runtime).execute(buildJsonObject { put("wait_seconds", 0) }, context)
            val old = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            shadowOf(page).webViewClient.onPageStarted(page, "https://example.com/new", null)
            val read = async(Dispatchers.Main.immediate) { BrowserReadTool(runtime).execute(empty, context) }
            old.onReceiveValue(snapshot("Old document"))
            assertFalse(read.isCompleted)
            shadowOf(page).lastEvaluatedJavascriptCallback.onReceiveValue(snapshot("New document"))
            assertEquals("New document", read.await().data!!.jsonObject["markdown"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `renderer termination fails the observation immediately`() = runBlocking {
        withBrowser { runtime, page ->
            val read = async(Dispatchers.Main.immediate) { BrowserReadTool(runtime).execute(empty, context) }
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            shadowOf(page).webViewClient.onRenderProcessGone(page, object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            })
            assertTrue(read.isCompleted)
            assertEquals("error", read.await().status)
            assertEquals("Browser renderer stopped", read.await().message)
            callback.onReceiveValue(snapshot("Late"))
            assertFalse(runtime.state.value.active)
        }
    }

    @Test fun `explicit navigation abandons the old observation without accepting its late snapshot`() = runBlocking {
        withBrowser { runtime, page ->
            BrowserReadTool(runtime).execute(buildJsonObject { put("wait_seconds", 0) }, context)
            val old = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            val navigation = async(Dispatchers.Main.immediate) {
                BrowserNavigateTool(runtime).execute(buildJsonObject { put("operation", "reload") }, context)
            }
            idle(1_200)
            val current = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            assertNotSame(old, current)
            old.onReceiveValue(snapshot("Abandoned page"))
            assertFalse(navigation.isCompleted)
            current.onReceiveValue(snapshot("Current page"))
            assertEquals("Current page", navigation.await().data!!.jsonObject["markdown"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `browser handoff cancels background observations and requires a new read afterward`() = runBlocking {
        withBrowser { runtime, page ->
            page.loadUrl("https://example.com/")
            BrowserReadTool(runtime).execute(buildJsonObject { put("wait_seconds", 0) }, context)
            val old = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            val handoff = async(Dispatchers.Main.immediate) { runtime.requestAssistance("Check this page", true, emptyList()) }
            val request = checkNotNull(runtime.assistance.request.value)
            val state = runtime.state.value
            old.onReceiveValue(snapshot("Do not observe user-controlled content"))
            assertEquals(state, runtime.state.value)
            assertEquals("CONFLICT", BrowserReadTool(runtime).execute(empty, context).code)
            runtime.assistance.respond(request.id, UserAssistanceAnswer("completed"))
            handoff.await()
            val result = BrowserReadTool(runtime).execute(buildJsonObject { put("wait_seconds", 0) }, context)
            assertEquals(JsonPrimitive(false), result.data!!.jsonObject["snapshot_available"])
            assertNotSame(old, shadowOf(page).lastEvaluatedJavascriptCallback)
        }
    }

    @Test fun `user cancellation and turn closure release pending observations`() = runBlocking {
        withBrowser { runtime, page ->
            val read = async(Dispatchers.Main.immediate) { BrowserReadTool(runtime).execute(empty, context) }
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            read.cancel()
            read.join()
            assertTrue(read.isCancelled)
            callback.onReceiveValue(snapshot("Cancelled result"))
            val next = async(Dispatchers.Main.immediate) { BrowserReadTool(runtime).execute(empty, context) }
            assertFalse(next.isCompleted)
            runtime.closeTurn()
            assertTrue(next.isCancelled)
            assertFalse(runtime.state.value.active)
        }
    }

    @Test fun `invalid waits are rejected before creating a page`() = runBlocking {
        val runtime = AgentBrowserRuntime(ApplicationProvider.getApplicationContext<Application>())
        for (value in listOf("-1", "\"30\"", "1.5", "true", "null", "9223372036854775807")) {
            val result = BrowserReadTool(runtime).execute(Json.parseToJsonElement("""{"wait_seconds":$value}""").jsonObject, context)
            assertEquals("INVALID_ARGS", result.code)
            assertFalse(runtime.state.value.active)
        }
    }

    private suspend fun withBrowser(block: suspend (AgentBrowserRuntime, WebView) -> Unit) {
        val runtime = AgentBrowserRuntime(ApplicationProvider.getApplicationContext<Application>())
        runtime.beginTurn()
        try {
            block(runtime, runtime.ensureWebView())
        } finally {
            runtime.closeTurn()
        }
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun snapshot(text: String, loading: Boolean = false): String = JsonPrimitive(buildJsonObject {
        put("ok", true)
        put("next_ref", 1)
        put("loading", loading)
        put("blocks", buildJsonArray {
            add(buildJsonObject { put("kind", "paragraph"); put("text", text) })
        })
    }.toString()).toString()

    @Implements(WebView::class)
    class FocusableWebViewShadow : ShadowWebView() {
        @Implementation
        protected fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean = true
    }
}
