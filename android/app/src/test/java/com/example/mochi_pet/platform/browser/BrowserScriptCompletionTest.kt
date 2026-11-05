package com.example.mochi_pet.platform.browser

import android.app.Application
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.browser.BrowserNavigateTool
import com.example.mochi_pet.core.browser.BrowserReadTool
import com.example.mochi_pet.core.model.MochiSurface
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserScriptCompletionTest {
    private val context = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
    private val snapshot = JsonPrimitive("""{"ok":true,"blocks":[],"next_ref":1}""").toString()

    @Test fun `navigation times out without a script callback and ignores its late result`() = runBlocking {
        val runtime = AgentBrowserRuntime(ApplicationProvider.getApplicationContext<Application>())
        runtime.beginTurn()
        val page = runtime.ensureWebView()
        val navigation = async(Dispatchers.Main.immediate) {
            BrowserNavigateTool(runtime).execute(buildJsonObject { put("operation", "reload") }, context)
        }
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
            assertFalse("Slow pages must have the full 30-second response window", navigation.isCompleted)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue("A missing WebView callback must not leave the Tool suspended", navigation.isCompleted)
            val result = navigation.await()
            assertEquals("error", result.status)
            assertEquals("TIMEOUT", result.code)
            assertNull(runtime.state.value.action)
            assertFalse(runtime.state.value.loading)
            assertNotNull(runtime.state.value.error)
            assertEquals(1, shadowOf(page).reloadInvocations)
            val timedOut = runtime.state.value
            callback.onReceiveValue(snapshot)
            assertEquals(timedOut, runtime.state.value)

            val recovery = async(Dispatchers.Main.immediate) {
                BrowserReadTool(runtime).execute(JsonObject(emptyMap()), context)
            }
            try {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(26))
                assertFalse(recovery.isCompleted)
                shadowOf(page).lastEvaluatedJavascriptCallback.onReceiveValue(snapshot)
                assertEquals("ok", recovery.await().status)
                assertNull(runtime.state.value.error)
            } finally {
                recovery.cancel()
            }
        } finally {
            navigation.cancel()
            runtime.closeTurn()
        }
    }

    @Test fun `renderer termination fails the pending read without waiting for a callback`() = runBlocking {
        val runtime = AgentBrowserRuntime(ApplicationProvider.getApplicationContext<Application>())
        runtime.beginTurn()
        val page = runtime.ensureWebView()
        val read = async(Dispatchers.Main.immediate) {
            BrowserReadTool(runtime).execute(JsonObject(emptyMap()), context)
        }
        try {
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            shadowOf(page).webViewClient.onRenderProcessGone(page, object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            })
            assertTrue("Renderer termination must finish the pending Tool", read.isCompleted)
            assertEquals("error", read.await().status)
            assertEquals("Browser renderer stopped", read.await().message)
            assertNull(runtime.state.value.action)
            assertFalse(runtime.state.value.active)
            assertTrue(shadowOf(page).wasDestroyCalled())
            callback.onReceiveValue(snapshot)
            assertFalse(runtime.state.value.active)
            assertNotSame(page, runtime.ensureWebView())
        } finally {
            read.cancel()
            runtime.closeTurn()
        }
    }

    @Test fun `closing a turn releases pending scripts and rejects callbacks from the old page`() = runBlocking {
        val runtime = AgentBrowserRuntime(ApplicationProvider.getApplicationContext<Application>())
        runtime.beginTurn()
        val page = runtime.ensureWebView()
        val read = async(Dispatchers.Main.immediate) {
            BrowserReadTool(runtime).execute(JsonObject(emptyMap()), context)
        }
        try {
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            runtime.closeTurn()
            assertTrue("Closing the page must release the pending Tool", read.isCompleted)
            assertEquals("error", read.await().status)
            assertFalse(runtime.state.value.active)
            runtime.beginTurn()
            val replacement = runtime.ensureWebView()
            val current = runtime.state.value
            callback.onReceiveValue(snapshot)
            assertEquals(current, runtime.state.value)
            assertNotSame(page, replacement)
        } finally {
            read.cancel()
            runtime.closeTurn()
        }
    }

    @Test fun `caller cancellation stays cancelled and a late script cannot update the page`() = runBlocking {
        val runtime = AgentBrowserRuntime(ApplicationProvider.getApplicationContext<Application>())
        runtime.beginTurn()
        val page = runtime.ensureWebView()
        val read = async(Dispatchers.Main.immediate) {
            BrowserReadTool(runtime).execute(JsonObject(emptyMap()), context)
        }
        try {
            val callback = checkNotNull(shadowOf(page).lastEvaluatedJavascriptCallback)
            read.cancel()
            read.join()
            assertTrue(read.isCancelled)
            val cancelled = runtime.state.value
            callback.onReceiveValue(snapshot)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            assertEquals(cancelled, runtime.state.value)
        } finally {
            read.cancel()
            runtime.closeTurn()
        }
    }
}
