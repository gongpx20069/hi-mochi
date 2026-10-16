package com.example.mochi_pet.feature.home

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.browser.BrowserMode
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserExperienceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearScreenFlag() {
        compose.runOnUiThread { compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun websiteManagementIsReachableWithoutOpeningAToolGroup() {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.onAllNodesWithText(localizeUiText("Tools")).onFirst().performClick()
        compose.onNodeWithTag("tools-list").performScrollToNode(hasText(localizeUiText("Websites and logins")))
        compose.onNodeWithText(localizeUiText("Websites and logins")).performClick()
        compose.onNodeWithTag("browser-management").assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Close")).assertIsDisplayed()
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("browserScreenshot") == "true") {
            val image = compose.onNodeWithTag("browser-management").captureToImage().asAndroidBitmap()
            java.io.File(compose.activity.getExternalFilesDir(null), "browser-management-smoke.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithTag("browser-sites-list").performScrollToNode(hasText(localizeUiText("Website host, for example example.com")))
        compose.onNodeWithText(localizeUiText("Website host, for example example.com")).assertIsDisplayed()
        compose.onNodeWithTag("browser-sites-list").performScrollToNode(hasText(localizeUiText("Open website / manage login")))
        compose.onAllNodesWithText(localizeUiText("Open website / manage login")).onFirst().assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Close")).performClick()
        compose.onNodeWithTag("tools-list").assertIsDisplayed()
    }

    @Test fun desktopHandoffResumesAndCookieSurvivesANewTurnThenSiteDeletion() = runBlocking {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val runtime = (compose.activity.application as MochiApplication).agentBrowserRuntime
        runtime.beginTurn()
        try {
            runtime.ensureWebView()
            val view = displayedWebView()
            fixture(view)
            runtime.navigate("reload", null, "mobile")
            assertEquals(BrowserMode.MOBILE, runtime.state.value.mode)
            assertTrue(script(view, "navigator.userAgent").contains("Mobile"))
            runtime.navigate("reload", null, "desktop")
            assertEquals(BrowserMode.DESKTOP, runtime.state.value.mode)
            assertFalse(script(view, "navigator.userAgent").contains("Mobile"))
            withTimeout(10_000) {
                while (script(view, "window.innerWidth").toInt() < 980) delay(100)
            }
            val waiting = async(Dispatchers.Default) {
                runtime.requestAssistance("Browser fixture: complete the simulated login, then continue.", true, emptyList())
            }
            withTimeout(5_000) { runtime.assistance.request.filterNotNull().first() }
            compose.onNodeWithText(localizeUiText("Mochi needs your help")).assertIsDisplayed()
            try {
                runtime.ensureWebView()
                fail("Snapshots must be paused")
            } catch (_: IllegalStateException) { }
            script(view, "document.cookie='mochi_fixture_session=present; max-age=600; path=/; Secure'; 'ok'")
            compose.onNodeWithText(localizeUiText("Continue")).performClick()
            assertEquals("completed", withTimeout(5_000) { waiting.await() }.outcome)
            assertFalse(runtime.state.value.userControl)
            assertNotNull(runtime.read()["snapshot_id"])
            try {
                runtime.navigate("reload", null, "mobile")
                fail("Mode changes must not replay a form after handoff")
            } catch (_: IllegalArgumentException) { }
        } finally {
            runtime.closeTurn()
        }
        runtime.beginTurn()
        try {
            runtime.read()
            val view = displayedWebView()
            fixture(view)
            assertTrue(script(view, "document.cookie").contains("mochi_fixture_session=present"))
        } finally {
            runtime.closeTurn()
        }
        assertTrue("Device WebView must support complete per-site data deletion", runtime.canClearSiteData())
        runtime.clearData("mochi-browser-fixture.example")
        assertFalse(withContext(Dispatchers.Main) {
            CookieManager.getInstance().getCookie(BASE).orEmpty().contains("mochi_fixture_session")
        })
    }

    private fun displayedWebView(): WebView {
        compose.waitForIdle()
        fun find(view: android.view.View): WebView? {
            if (view is WebView) return view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        var result: WebView? = null
        compose.runOnUiThread { result = find(compose.activity.window.decorView) }
        return requireNotNull(result) { "Live browser card must contain its WebView" }
    }

    private suspend fun fixture(view: WebView) {
        withContext(Dispatchers.Main) {
            val original = view.webViewClient
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(HTML.toByteArray()))
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) =
                    original.onPageStarted(view, url, favicon)
                override fun onPageFinished(view: WebView, url: String) = original.onPageFinished(view, url)
            }
            view.loadUrl(BASE)
        }
        withTimeout(10_000) {
            while (!script(view, "document.body?.innerText || ''").contains("Browser fixture")) delay(100)
        }
    }

    private suspend fun script(view: WebView, source: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(source) { value ->
                if (continuation.isActive) continuation.resume(
                    kotlinx.serialization.json.Json.parseToJsonElement(value).jsonPrimitive.content,
                )
            }
        }
    }

    private companion object {
        const val BASE = "https://mochi-browser-fixture.example/"
        const val HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><h1>Browser fixture</h1><input aria-label="Fixture field"><button>Fixture button</button></body></html>"""
    }
}
