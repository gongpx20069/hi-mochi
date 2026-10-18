package com.example.mochi_pet.feature.home

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.browser.BrowserMode
import com.example.mochi_pet.core.browser.BrowserLoginKind
import com.example.mochi_pet.core.browser.BrowserLogin
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
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("browserScreenshot") == "true") {
            val image = compose.onNodeWithTag("browser-management").captureToImage().asAndroidBitmap()
            java.io.File(compose.activity.getExternalFilesDir(null), "browser-management-smoke.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithTag("browser-search").assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Search Bing or enter a website")).assertIsDisplayed()
        compose.onNodeWithTag("browser-logo").assertIsDisplayed()
        compose.onNodeWithTag("browser-accounts-panel").assertIsDisplayed()
        compose.onNodeWithTag("browser-management-close").performClick()
        compose.onNodeWithTag("tools-list").assertIsDisplayed()
    }

    @Test fun desktopHandoffResumesAndCookieSurvivesANewTurnThenSiteDeletion() = runBlocking<Unit> {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val app = compose.activity.application as MochiApplication
        val runtime = app.agentBrowserRuntime
        runtime.beginTurn()
        try {
            val created = runtime.ensureWebView()
            val view = displayedWebView(expected = created)
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
            script(view, "document.querySelector('[name=username]').value='mochi-fixture-test'; document.querySelector('[type=password]').value='synthetic-browser-password'; document.querySelector('form').requestSubmit(); 'ok'")
            withTimeout(5_000) { runtime.credentialCapture.pending.filterNotNull().first() }
            compose.onNodeWithText(localizeUiText("Save this account and password?")).assertIsDisplayed()
            compose.onNodeWithText(localizeUiText("Save")).performClick()
            val saved = withTimeout(5_000) {
                app.browserCredentialVault.summaries.first { entries -> entries.any { it.origin == BASE && it.username == "mochi-fixture-test" } }
                    .single { it.origin == BASE && it.username == "mochi-fixture-test" }
            }
            assertEquals("synthetic-browser-password", app.browserCredentialVault.load(saved).password)
            script(view, "document.querySelector('[name=username]').value=''; document.querySelector('[type=password]').value=''; 'ok'")
            runtime.fillUserCredential(app.browserCredentialVault.load(saved))
            assertEquals("mochi-fixture-test", script(view, "document.querySelector('[name=username]').value"))
            assertEquals("synthetic-browser-password", script(view, "document.querySelector('[type=password]').value"))
            assertEquals("1", script(view, "window.fixtureSubmits"))
            withTimeout(5_000) { while (runtime.credentialCapture.pending.value != null) delay(10) }
            script(view, "document.body.innerHTML=${kotlinx.serialization.json.JsonPrimitive(SMS_HTML)}; 'ok'")
            tapFixtureButton(view, "sms-login")
            val smsRequest = withTimeout(5_000) { runtime.credentialCapture.pending.filterNotNull().first() }
            assertEquals(BrowserLoginKind.SMS, smsRequest.login.kind)
            assertEquals("", smsRequest.login.password)
            compose.onNodeWithText(localizeUiText("Save SMS login account?")).assertIsDisplayed()
            compose.onNodeWithText(localizeUiText("Save")).performClick()
            val sms = withTimeout(5_000) {
                app.browserCredentialVault.summaries.first { entries ->
                    entries.any { it.origin == BASE && it.username == SMS_PHONE && it.kind == BrowserLoginKind.SMS }
                }.single { it.origin == BASE && it.username == SMS_PHONE && it.kind == BrowserLoginKind.SMS }
            }
            assertEquals("", app.browserCredentialVault.load(sms).password)
            script(view, "document.getElementById('sms-phone').value=''; 'ok'")
            runtime.fillUserCredential(app.browserCredentialVault.load(sms))
            assertEquals(SMS_PHONE, script(view, "document.getElementById('sms-phone').value"))
            assertEquals("975310", script(view, "document.getElementById('sms-code').value"))
            assertEquals("1", script(view, "window.smsSubmits"))
            app.browserCredentialVault.delete(sms)
            compose.onNodeWithText(localizeUiText("Continue")).performClick()
            assertEquals("completed", withTimeout(5_000) { waiting.await() }.outcome)
            assertFalse(runtime.state.value.userControl)
            val snapshot = runtime.read()
            assertNotNull(snapshot["snapshot_id"])
            assertFalse(snapshot.toString().contains("synthetic-browser-password"))
            assertFalse(snapshot.toString().contains("975310"))
            app.browserCredentialVault.delete(saved)
            assertTrue(script(view, "document.cookie").contains("mochi_fixture_session=present"))
            try {
                runtime.navigate("reload", null, "mobile")
                fail("Mode changes must not replay a form after handoff")
            } catch (_: IllegalArgumentException) { }
        } finally {
            runtime.closeTurn()
            app.browserCredentialVault.summaries.first().filter {
                it.origin == BASE && it.username in setOf("mochi-fixture-test", SMS_PHONE)
            }
                .forEach { app.browserCredentialVault.delete(it) }
        }
        runtime.beginTurn()
        try {
            val view = runtime.ensureWebView()
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

    @Test fun searchingOpensFullScreenManualBrowserAndHomePreservesThePage() = runBlocking<Unit> {
        mark("manual test starting")
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val runtime = (compose.activity.application as MochiApplication).agentBrowserRuntime
        compose.onAllNodesWithText(localizeUiText("Tools")).onFirst().performClick()
        compose.onNodeWithTag("tools-list").performScrollToNode(hasText(localizeUiText("Websites and logins")))
        compose.onNodeWithText(localizeUiText("Websites and logins")).performClick()
        compose.onNodeWithTag("browser-search").performTextInput("mochi browser fixture")
        compose.onNodeWithTag("browser-search").performImeAction()
        mark("search submitted")
        compose.waitUntil(10_000) { runtime.state.value.manual && runtime.state.value.url.contains("bing.com") }
        compose.onNodeWithTag("manual-browser").assertIsDisplayed()
        assertTrue(runtime.state.value.userControl)
        val session = runtime.state.value.sessionId
        val root = requireNotNull(compose.onNodeWithTag("manual-browser").fetchSemanticsNode().root as? ViewRootForTest).view.rootView
        val view = displayedWebView(root)
        fixture(view)
        mark("manual fixture loaded")
        script(view, "document.cookie='mochi_fixture_session=present; max-age=600; path=/; Secure'; 'ok'")
        script(view, "document.querySelector('[name=username]').value='retained-fixture'; 'ok'")
        tapFixtureButton(view, "fixture-next")
        mark("navigation tapped")
        try {
            compose.waitUntil(5_000) { runtime.state.value.canGoBack }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            val history = withContext(Dispatchers.Main) {
                val entries = view.copyBackForwardList()
                "Native history: back=${view.canGoBack()}, size=${entries.size}, index=${entries.currentIndex}"
            }
            throw AssertionError(history, error)
        }
        compose.onNodeWithTag("browser-back").performClick()
        withTimeout(5_000) { while (script(view, "location.pathname") != "/") delay(100) }
        mark("back complete")
        compose.waitUntil(5_000) { runtime.state.value.canGoForward }
        compose.onNodeWithTag("browser-forward").performClick()
        withTimeout(5_000) { while (script(view, "location.pathname") != "/second") delay(100) }
        mark("forward complete")
        compose.onNodeWithTag("browser-accounts").performClick()
        compose.onNodeWithTag("browser-websites-popup").assertIsDisplayed()
        mark("accounts popup visible")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("browser-home").performClick()
        compose.onNodeWithTag("browser-management").assertIsDisplayed()
        assertTrue(runtime.state.value.manual)
        assertEquals(session, runtime.state.value.sessionId)
        compose.onNodeWithTag("browser-resume").performClick()
        compose.onNodeWithTag("manual-browser").assertIsDisplayed()
        displayedWebView(
            requireNotNull(compose.onNodeWithTag("manual-browser").fetchSemanticsNode().root as? ViewRootForTest).view.rootView,
            expected = view,
        )
        mark("original page reattached")
        assertEquals(session, runtime.state.value.sessionId)
        assertEquals("retained-fixture", script(view, "document.querySelector('[name=username]').value"))
        compose.onNodeWithTag("browser-accounts").performClick()
        val popupWebsite = hasTestTag("browser-website-mochi-browser-fixture.example") and
            hasAnyAncestor(hasTestTag("browser-websites-popup"))
        compose.waitUntil(10_000) {
            compose.onAllNodes(popupWebsite).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(popupWebsite).performClick()
        compose.onNodeWithTag("browser-website-details").assertIsDisplayed()
        compose.onNodeWithTag("site-data-row-mochi-browser-fixture.example").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("site-data-delete-mochi-browser-fixture.example").performClick()
        compose.onNodeWithText(localizeUiText("The current browser page will close before deletion.")).assertIsDisplayed()
        compose.onNodeWithTag("browser-delete-site-confirm").performClick()
        compose.waitUntil(5_000) { !runtime.state.value.manual }
        compose.onNodeWithTag("browser-management").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(localizeUiText("Website data cleared. Saved accounts were kept.")).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(withContext(Dispatchers.Main) {
            CookieManager.getInstance().getCookie(BASE).orEmpty().contains("mochi_fixture_session")
        })
        compose.onNodeWithTag("browser-management-close").performClick()
    }

    @Test fun websiteGroupsKeepCredentialsSeparateFromDomainScopedDataDeletion() = runBlocking<Unit> {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val app = compose.activity.application as MochiApplication
        val runtime = app.agentBrowserRuntime
        val settings = checkNotNull(runtime.settings)
        val vault = app.browserCredentialVault
        val domain = "mochi-browser-fixture.example"
        val child = "https://login.$domain/"
        val otherDomain = "other-mochi-browser-fixture.example"
        val other = "https://$otherDomain/"
        val username = "grouped-fixture-user"
        val phone = "+12025550124"
        try {
            for (url in listOf(BASE, child, other)) {
                withContext(Dispatchers.Main) {
                    withTimeout(5_000) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            CookieManager.getInstance().setCookie(url, "mochi_group_fixture=present; max-age=600; path=/; Secure") {
                                if (continuation.isActive) {
                                    if (it) continuation.resume(Unit)
                                    else continuation.resumeWith(Result.failure(AssertionError("Fixture Cookie was rejected")))
                                }
                            }
                        }
                    }
                }
            }
            for (host in listOf(domain, "login.$domain", otherDomain)) settings.visited(host, BrowserMode.MOBILE)
            vault.save(BrowserLogin(BASE, username, "synthetic-group-password"))
            vault.save(BrowserLogin(child, phone, kind = BrowserLoginKind.SMS))
            val entries = vault.summaries.first()
            val password = entries.single { it.origin == BASE && it.username == username }
            val sms = entries.single { it.origin == child && it.username == phone }
            compose.onAllNodesWithText(localizeUiText("Tools")).onFirst().performClick()
            compose.onNodeWithTag("tools-list").performScrollToNode(hasText(localizeUiText("Websites and logins")))
            compose.onNodeWithText(localizeUiText("Websites and logins")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("browser-website-$domain").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("browser-website-$domain").performClick()
            compose.onNodeWithTag("browser-website-details").assertIsDisplayed()
            val row = compose.onNodeWithTag("credential-row-${password.id}").performScrollTo()
            val width = row.fetchSemanticsNode().boundsInRoot.width
            row.performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertEquals(width / 2f, compose.onNodeWithTag("credential-content-${password.id}").fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.onNodeWithTag("credential-edit-${password.id}").assertIsDisplayed()
            compose.onNodeWithTag("credential-delete-${password.id}").performClick()
            compose.onNodeWithTag("browser-delete-account-confirm").performClick()
            withTimeout(5_000) { vault.summaries.first { records -> records.none { it.id == password.id } } }
            assertTrue(withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(BASE).orEmpty().contains("mochi_group_fixture") })
            val dataRow = compose.onNodeWithTag("site-data-row-$domain").performScrollTo()
            val dataWidth = dataRow.fetchSemanticsNode().boundsInRoot.width
            dataRow.performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertEquals(dataWidth / 2f, compose.onNodeWithTag("site-data-content-$domain").fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.onNodeWithTag("site-data-delete-$domain").performClick()
            compose.onNodeWithTag("browser-delete-site-confirm").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("site-data-row-$domain").fetchSemanticsNodes().isEmpty() }
            assertEquals(phone, vault.load(sms).username)
            withContext(Dispatchers.Main) {
                assertFalse(CookieManager.getInstance().getCookie(BASE).orEmpty().contains("mochi_group_fixture"))
                assertFalse(CookieManager.getInstance().getCookie(child).orEmpty().contains("mochi_group_fixture"))
                assertTrue(CookieManager.getInstance().getCookie(other).orEmpty().contains("mochi_group_fixture"))
            }
            compose.onNodeWithTag("credential-row-${sms.id}").performScrollTo().assertIsDisplayed()
        } finally {
            vault.summaries.first().filter {
                (it.origin == BASE && it.username == username) || (it.origin == child && it.username == phone)
            }.forEach { vault.delete(it) }
            runtime.clearData(domain)
            runtime.clearData(otherDomain)
        }
    }

    private fun displayedWebView(root: android.view.View = compose.activity.window.decorView, expected: WebView? = null): WebView {
        fun find(view: android.view.View): WebView? {
            if (view is WebView) return view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        var result: WebView? = null
        compose.waitUntil(5_000) {
            compose.runOnUiThread { result = find(root) }
            result != null && (expected == null || result === expected)
        }
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
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
                    original.doUpdateVisitedHistory(view, url, isReload)
            }
            view.loadUrl(BASE)
        }
        withTimeout(10_000) {
            while (!script(view, "document.body?.innerText || ''").contains("Browser fixture")) delay(100)
        }
    }

    private suspend fun script(view: WebView, source: String): String = withTimeout(10_000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(source) { value ->
                    if (continuation.isActive) continuation.resume(
                        kotlinx.serialization.json.Json.parseToJsonElement(value).jsonPrimitive.content,
                    )
                }
            }
        }
    }

    private fun mark(stage: String) { android.util.Log.i("TestRunner", "Browser fixture: $stage") }

    private suspend fun tapFixtureButton(view: WebView, id: String) {
        // Chromium skips script-created history without a genuine touch gesture.
        val point = script(view, """(() => {
            const rect = document.getElementById(${kotlinx.serialization.json.JsonPrimitive(id)}).getBoundingClientRect();
            return [rect.left + rect.width / 2, rect.top + rect.height / 2, innerWidth].join(',');
        })()""").split(',').map(String::toFloat)
        compose.runOnUiThread {
            val scale = view.width / point[2]
            val now = android.os.SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                val event = android.view.MotionEvent.obtain(now, now + action * 50L, action, point[0] * scale, point[1] * scale, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    private companion object {
        const val BASE = "https://mochi-browser-fixture.example/"
        const val SMS_PHONE = "+12025550123"
        const val SMS_HTML = """<h1>Browser fixture</h1><section><input id="sms-phone" type="tel" autocomplete="tel" value="+12025550123"><input id="sms-code" type="password" autocomplete="one-time-code" value="975310"><button id="sms-login" type="button" onclick="window.smsSubmits=(window.smsSubmits||0)+1">登录</button></section><div id="echo">synthetic-browser-password</div>"""
        const val HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><h1>Browser fixture</h1><form onsubmit="event.preventDefault(); window.fixtureSubmits=(window.fixtureSubmits||0)+1; document.getElementById('echo').textContent=this.elements.password.value"><input name="username" autocomplete="username"><input name="password" type="password"><button type="submit">Sign in fixture</button></form><div id="echo"></div><button id="fixture-next" onclick="history.pushState({}, '', '/second')">Next fixture page</button></body></html>"""
    }
}
