package com.example.mochi_pet.platform.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.mochi_pet.core.browser.UserAssistanceAnswer
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class AgentBrowserSemanticSnapshotTest {
    @Test
    fun customSelectionHintsTrackTogglesWithoutInventingSemanticState() = fixture { runtime, page ->
        script(page, """
            document.body.innerHTML = '<style>[role=button]{padding:8px}</style>' +
              '<div role="button" id="choice" class="isSelected--fixture private-class-marker">Scoped choice</div>' +
              '<div role="button" class="option--selected">BEM choice</div>' +
              '<div role="button" class="selected" data-selected="false">Data choice</div>' +
              '<div role="button" data-state="checked">Data toggle</div>' +
              '<div class="isSelected--container"><button>Unrelated child</button></div>' +
              '<div role="button" class="unselected not-selected selectedish" style="color:red">Unknown choice</div>';
            document.getElementById('choice').onclick = e => e.currentTarget.classList.toggle('isSelected--fixture');
        """.trimIndent())
        val before = runtime.snapshot()
        val controls = before.getValue("interactive_elements").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
        for (name in listOf("Scoped choice", "BEM choice")) {
            val control = controls.getValue(name)
            assertTrue(control.getValue("states").jsonArray.isEmpty())
            val hint = control.getValue("state_hints").jsonArray.single().jsonObject
            assertEquals("selected", hint.getValue("state").jsonPrimitive.content)
            assertEquals("class", hint.getValue("source").jsonPrimitive.content)
        }
        val data = controls.getValue("Data choice").getValue("state_hints").jsonArray.single().jsonObject
        assertEquals("selected=false", data.getValue("state").jsonPrimitive.content)
        assertEquals("data-selected", data.getValue("source").jsonPrimitive.content)
        assertEquals("checked", controls.getValue("Data toggle").getValue("state_hints")
            .jsonArray.single().jsonObject.getValue("state").jsonPrimitive.content)
        for (name in listOf("Unrelated child", "Unknown choice")) assertFalse(controls.getValue(name).containsKey("state_hints"))
        assertFalse(before.toString().contains("private-class-marker"))
        val choice = ref(before, "Scoped choice")
        runtime.click(choice, waitSeconds = 0)
        val after = runtime.snapshot()
        assertEquals(choice, ref(after, "Scoped choice"))
        assertFalse(after.getValue("interactive_elements").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "Scoped choice" }.containsKey("state_hints"))
    }

    @Test
    fun nativeAndAriaSelectionStatesOverrideClassHintsAndPreserveFalseAndMixed() = fixture { runtime, page ->
        script(page, """
            document.body.innerHTML = '<input id="native" type="checkbox" aria-label="Native toggle" aria-checked="false" checked>' +
              '<input id="mixed" type="checkbox" aria-label="Mixed native toggle">' +
              '<button aria-pressed="false" class="isPressed--fixture">Pressed toggle</button>' +
              '<div role="checkbox" aria-checked="mixed">Mixed ARIA toggle</div>' +
              '<div role="option" aria-selected="false" class="selected">Unselected option</div>';
            document.getElementById('mixed').indeterminate = true;
        """.trimIndent())
        val controls = runtime.snapshot().getValue("interactive_elements").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
        for ((name, state) in mapOf(
            "Native toggle" to "checked",
            "Mixed native toggle" to "checked=mixed",
            "Pressed toggle" to "pressed=false",
            "Mixed ARIA toggle" to "checked=mixed",
            "Unselected option" to "selected=false",
        )) {
            assertEquals(listOf(state), controls.getValue(name).getValue("states").jsonArray.map { it.jsonPrimitive.content })
            assertFalse(controls.getValue(name).containsKey("state_hints"))
        }
        script(page, "document.getElementById('native').checked=false")
        val unchecked = runtime.snapshot().getValue("interactive_elements").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "Native toggle" }
        assertEquals("checked=false", unchecked.getValue("states").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun visibleFrameMetadataExplainsOpaqueContentWithoutReadingIt() = fixture { runtime, page ->
        withContext(Dispatchers.Main) {
            page.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/html", "UTF-8", "<p>private-frame-content</p>".byteInputStream())
            }
        }
        script(page, """
            document.body.innerHTML = '<h1>Frame host</h1>' +
              '<iframe src="https://example.com/embedded?code=private-frame-token#fragment"></iframe>' +
              '<iframe style="display:none" src="https://example.com/hidden-frame"></iframe>' +
              '<iframe srcdoc="<p>private-srcdoc-content</p>" src="https://example.com/ignored-source"></iframe>';
        """.trimIndent())
        val result = runtime.snapshot()
        val frames = result.getValue("embedded_frames").jsonArray.map { it.jsonObject }
        assertEquals(listOf("https://example.com/embedded", ""), frames.map { it.getValue("url").jsonPrimitive.content })
        assertTrue(frames.all { it.getValue("bounds").jsonArray.size == 4 })
        assertFalse(result.getValue("truncation").jsonObject.getValue("embedded_frames").jsonPrimitive.boolean)
        for (privateText in listOf("private-frame-token", "private-frame-content", "private-srcdoc-content", "hidden-frame", "ignored-source")) {
            assertFalse(privateText, result.toString().contains(privateText))
        }
    }

    @Test
    fun readsUnmarkedStatisticsAndKeepsReferencesBoundToNodes() = fixture { runtime, page ->
        val initial = runtime.snapshot()
        assertEquals("mochi-semantic-v3", initial["format"]?.jsonPrimitive?.content)
        val markdown = initial.getValue("markdown").jsonPrimitive.content
        assertTrue(markdown, markdown.contains("点赞（Q）: 364"))
        assertTrue(markdown, markdown.contains("收藏（E）: 951"))
        assertTrue(markdown, markdown.contains("Unmarked visible description"))
        assertTrue(markdown, markdown.contains("# Fixture video"))
        assertTrue(markdown, markdown.contains("const n = 1;\nn++;"))
        assertTrue(markdown, markdown.contains("| Cell one | Cell two |"))
        assertEquals(100, initial.getValue("interactive_elements").jsonArray.size)
        assertEquals("true", initial.getValue("truncation").jsonObject.getValue("interactive_elements").jsonPrimitive.content)
        val like = ref(initial, "点赞（Q）")
        assertEquals(like, ref(runtime.snapshot(), "点赞（Q）"))
        runtime.click(like, waitSeconds = 0)
        val clicked = runtime.snapshot()
        assertTrue(clicked.getValue("markdown").jsonPrimitive.content.contains("点赞（Q）: 365"))
        assertEquals(like, ref(clicked, "点赞（Q）"))

        script(page, "document.querySelector('.video-like').replaceWith(document.querySelector('.video-like').cloneNode(true))")
        val replacement = ref(runtime.snapshot(), "点赞（Q）")
        assertFalse(like == replacement)
        assertRejected { runtime.click(like, waitSeconds = 0); runtime.snapshot() }
        runtime.scroll("down", "half_page", null, waitSeconds = 0)
        assertEquals("mochi-semantic-v3", runtime.snapshot()["format"]?.jsonPrimitive?.content)
    }

    @Test
    fun typingActivatesShadowCommentEditorAndRevealsPublishControl() = fixture { runtime, page ->
        installCommentEditor(page)
        val editor = ref(runtime.snapshot(), "Comment fixture")
        runtime.input("type", editor, "Synthetic comment, never submitted", null, null, true, waitSeconds = 0)
        val result = runtime.snapshot()
        assertCommentPublishVisible(page, result)
    }

    @Test
    fun clickingActivatesShadowReplyEditorWithoutPublishing() = fixture { runtime, page ->
        installCommentEditor(page)
        runtime.click(ref(runtime.snapshot(), "Reply fixture"), waitSeconds = 0)
        val result = runtime.snapshot()
        assertCommentPublishVisible(page, result)
    }

    @Test
    fun followsLinkAncestorsAndBaseTargetsInOneWindow() = fixture { runtime, page ->
        val navigation = interceptNavigation(page)
        script(page, """
            document.body.innerHTML = '<style>a{display:block;padding:12px}span[role]{display:inline-block;padding:4px}</style>' +
              '<a id="direct" href="https://example.com/direct?item=fixture" target="_blank">Direct link</a>' +
              '<a id="nested" href="https://example.com/nested" target="product-window"><span role="button">Nested title</span></a>' +
              '<a id="inherited" href="https://example.com/inherited"><span role="button">Inherited title</span></a>' +
              '<a id="shadow" href="https://example.com/shadow" target="_BLANK"><span id="shadow-host"></span></a>' +
              '<a id="slotted" href="https://example.com/slotted" target="_blank"><span id="slot-host"><span slot="label" role="button">Slotted title</span></span></a>';
            const base = document.createElement('base');
            base.target = '_blank';
            document.head.append(base);
            document.getElementById('shadow-host').attachShadow({mode:'open'}).innerHTML =
              '<span role="button" style="display:inline-block;padding:4px">Shadow title</span>';
            document.getElementById('slot-host').attachShadow({mode:'open'}).innerHTML = '<slot name="label"></slot>';
            window.fixtureClicks = 0;
            document.querySelectorAll('a').forEach(a => a.addEventListener('click', () => {
              window.fixtureClicks++;
              a.dataset.targetDuringClick = a.getAttribute('target');
            }));
        """.trimIndent())
        val cases = listOf(
            Triple("direct", "Direct link", "\"_blank\""),
            Triple("nested", "Nested title", "\"product-window\""),
            Triple("inherited", "Inherited title", "null"),
            Triple("shadow", "Shadow title", "\"_BLANK\""),
            Triple("slotted", "Slotted title", "\"_blank\""),
        )
        cases.forEachIndexed { index, (id, label, originalTarget) ->
            val target = runtime.snapshot().getValue("interactive_elements").jsonArray.map { it.jsonObject }
                .first { it["name"]?.jsonPrimitive?.content == label &&
                    it["role"]?.jsonPrimitive?.content == if (id == "direct") "link" else "button" }
            assertEquals(if (id == "direct") "a" else "span", target.getValue("tag").jsonPrimitive.content)
            runtime.click(target.getValue("ref").jsonPrimitive.content, waitSeconds = 0)
            runtime.snapshot()
            assertEquals("Exactly one navigation for $label", index + 1, navigation.size)
            assertEquals("https://example.com/$id" + if (id == "direct") "?item=fixture" else "", navigation.last())
            assertEquals((index + 1).toString(), script(page, "window.fixtureClicks"))
            assertEquals("\"_self\"", script(page, "document.getElementById('$id').dataset.targetDuringClick"))
            assertEquals(originalTarget, script(page, "document.getElementById('$id').getAttribute('target')"))
        }
    }

    @Test
    fun cancelledLinksAndIndependentControlsDoNotForceNavigation() = fixture { runtime, page ->
        val navigation = interceptNavigation(page)
        script(page, """
            document.body.innerHTML = '<style>a,div[role]{display:block;padding:12px}</style>' +
              '<a id="cancelled" href="https://example.com/cancelled" target="_blank">Cancelled link</a>' +
              '<a id="outer" href="https://example.com/outer" target="_blank"><button id="save">Save locally</button></a>' +
              '<div role="button" aria-label="Unlinked container">Container <a href="https://example.com/child">Child link</a></div>';
            window.fixtureClicks = 0;
            document.body.addEventListener('click', () => { window.fixtureClicks++; });
            document.getElementById('cancelled').addEventListener('click', e => e.preventDefault());
            document.getElementById('save').addEventListener('click', e => { e.preventDefault(); e.target.textContent = 'Saved locally'; });
        """.trimIndent())
        for (label in listOf("Cancelled link", "Save locally", "Unlinked container")) {
            runtime.click(ref(runtime.snapshot(), label), waitSeconds = 0)
            runtime.snapshot()
        }
        assertTrue("No forced navigation after cancellation or container clicks", navigation.isEmpty())
        assertEquals("3", script(page, "window.fixtureClicks"))
        assertEquals("\"Saved locally\"", script(page, "document.getElementById('save').textContent"))
        assertEquals("\"_blank\"", script(page, "document.getElementById('cancelled').getAttribute('target')"))
        assertEquals("\"_blank\"", script(page, "document.getElementById('outer').getAttribute('target')"))
    }

    @Test
    fun URLFallbackTitlesAreSanitizedInSnapshotsAndNativeState() = fixture { runtime, page ->
        script(page, "document.title = 'https://example.com/login?code=synthetic-navigation-token#fragment'")
        val snapshot = runtime.snapshot()
        assertEquals("https://example.com/login", snapshot.getValue("title").jsonPrimitive.content)
        assertEquals("https://example.com/login", runtime.state.value.title)
        assertFalse(snapshot.toString().contains("synthetic-navigation-token"))
    }

    private suspend fun interceptNavigation(page: WebView): MutableList<String> {
        val navigation = mutableListOf<String>()
        withContext(Dispatchers.Main) {
            page.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame) navigation += request.url.toString()
                    return true
                }
            }
        }
        return navigation
    }

    @Test
    fun formTargetsRespectSubmitterOverridesValidationAndCancellation() = fixture { runtime, page ->
        val navigation = interceptNavigation(page)
        script(page, """
            document.body.innerHTML = '<form id="search" action="https://example.com/search" target="_blank">' +
              '<input name="q" value="fixture" required aria-label="Search terms">' +
              '<button id="nested-submit"><span role="button">Submit nested title</span></button></form>' +
              '<button id="external" form="search" formtarget="results">Submit outside form</button>' +
              '<form action="https://example.com/inherited"><button>Submit inherited target</button></form>' +
              '<form action="https://example.com/invalid" target="_blank"><input required><button>Invalid submit</button></form>';
            const base = document.createElement('base'); base.target = '_blank'; document.head.append(base);
            window.fixtureSubmits = 0;
            document.addEventListener('submit', () => { window.fixtureSubmits++; });
        """.trimIndent())
        for (label in listOf("Submit nested title", "Submit outside form", "Submit inherited target")) {
            runtime.click(ref(runtime.snapshot(), label), waitSeconds = 0)
            runtime.snapshot()
        }
        assertEquals(listOf("https://example.com/search?q=fixture", "https://example.com/search?q=fixture",
            "https://example.com/inherited?"), navigation)
        assertEquals("3", script(page, "window.fixtureSubmits"))
        assertEquals("null", script(page, "document.getElementById('nested-submit').getAttribute('formtarget')"))
        assertEquals("\"results\"", script(page, "document.getElementById('external').getAttribute('formtarget')"))
        assertEquals("\"_blank\"", script(page, "document.getElementById('search').target"))
        runtime.click(ref(runtime.snapshot(), "Invalid submit"), waitSeconds = 0)
        runtime.snapshot()
        assertEquals(3, navigation.size)
        script(page, "document.getElementById('search').addEventListener('submit', e => e.preventDefault())")
        runtime.click(ref(runtime.snapshot(), "Submit outside form"), waitSeconds = 0)
        runtime.snapshot()
        assertEquals(3, navigation.size)
        assertEquals("4", script(page, "window.fixtureSubmits"))
    }

    @Test
    fun enterUsesCurrentWindowAndHonorsCancelledKeyEvents() = fixture { runtime, page ->
        val navigation = interceptNavigation(page)
        script(page, """
            document.body.innerHTML = '<form id="search" action="https://example.com/search" target="_blank">' +
              '<input name="q" value="fixture" aria-label="Search terms"></form>';
            window.fixtureSubmits = 0;
            document.addEventListener('submit', () => { window.fixtureSubmits++; });
        """.trimIndent())
        val input = ref(runtime.snapshot(), "Search terms")
        runtime.input("key", input, null, null, "Enter", false, waitSeconds = 0)
        runtime.snapshot()
        assertEquals(listOf("https://example.com/search?q=fixture"), navigation)
        assertEquals("\"_blank\"", script(page, "document.getElementById('search').target"))
        script(page, "document.querySelector('input').addEventListener('keydown', e => e.preventDefault())")
        runtime.input("key", input, null, null, "Enter", false, waitSeconds = 0)
        runtime.snapshot()
        assertEquals(1, navigation.size)
        assertEquals("1", script(page, "window.fixtureSubmits"))
    }

    @Test
    fun linkNavigationReplacesTheDocumentAndInvalidatesOldReferences() = fixture { runtime, page ->
        script(page, """
            document.body.innerHTML = '<a href="https://example.com/fixture-next" target="_blank"><span role="button">Open next document</span></a>';
        """.trimIndent())
        val oldRef = ref(runtime.snapshot(), "Open next document")
        withContext(Dispatchers.Main) {
            val delegate = page.webViewClient
            page.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse(
                    "text/html", "UTF-8",
                    "<html><body><h1>New document</h1><button>New control</button></body></html>".byteInputStream(),
                )
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) =
                    delegate.onPageStarted(view, url, favicon)
                override fun onPageFinished(view: WebView, url: String) = delegate.onPageFinished(view, url)
            }
        }
        runtime.click(oldRef, waitSeconds = 0)
        withTimeout(10_000) {
            var result = runtime.snapshot()
            while (!result.getValue("markdown").jsonPrimitive.content.contains("New document")) {
                result = runtime.snapshot()
            }
            assertEquals("https://example.com/fixture-next", result.getValue("url").jsonPrimitive.content)
            assertFalse(oldRef == ref(result, "New control"))
        }
        assertRejected { runtime.click(oldRef, waitSeconds = 0); runtime.snapshot() }
    }

    @Test
    fun historyAfterInteractionKeepsNativeCursorAndStillBlocksReloadAndModeChanges() = fixture { runtime, page ->
        val origin = "https://93.184.216.34"
        withContext(Dispatchers.Main) {
            val delegate = page.webViewClient
            page.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    delegate.shouldOverrideUrlLoading(view, request)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse(
                    "text/html", "UTF-8",
                    ("<html><body><h1>${request.url.path}</h1>" +
                        "<a href='$origin/detail' target='_blank'><span role='button'>Open details</span></a>" +
                        "<button onclick=\"this.textContent='Selected'\">Choose SKU</button></body></html>").byteInputStream(),
                )
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) =
                    delegate.onPageStarted(view, url, favicon)
                override fun onPageFinished(view: WebView, url: String) = delegate.onPageFinished(view, url)
            }
        }
        runtime.navigate("goto", "$origin/search", mode = "mobile", waitSeconds = 0)
        runtime.snapshot("$origin/search")
        runtime.click(ref(runtime.snapshot(), "Open details"), waitSeconds = 0)
        runtime.snapshot("$origin/detail")
        runtime.click(ref(runtime.snapshot(), "Choose SKU"), waitSeconds = 0)
        runtime.snapshot()
        val detailIndex = withContext(Dispatchers.Main) { page.copyBackForwardList().currentIndex }
        runtime.navigate("back", null, waitSeconds = 0)
        val back = runtime.snapshot("$origin/search")
        assertEquals("$origin/search", back.getValue("url").jsonPrimitive.content)
        assertEquals(detailIndex - 1, withContext(Dispatchers.Main) { page.copyBackForwardList().currentIndex })
        assertTrue(withContext(Dispatchers.Main) { page.canGoForward() })
        runtime.navigate("forward", null, waitSeconds = 0)
        assertEquals("$origin/detail", runtime.snapshot("$origin/detail").getValue("url").jsonPrimitive.content)
        assertEquals(detailIndex, withContext(Dispatchers.Main) { page.copyBackForwardList().currentIndex })
        for ((operation, mode) in listOf("reload" to "auto", "goto" to "desktop")) {
            var rejected = false
            try {
                runtime.navigate(operation, "$origin/search", mode = mode, waitSeconds = 0)
                runtime.snapshot()
            } catch (error: AgentBrowserException) {
                assertEquals(
                    if (operation == "reload") com.example.mochi_pet.core.agent.tool.ToolErrorReason.BROWSER_RELOAD_BLOCKED
                    else com.example.mochi_pet.core.agent.tool.ToolErrorReason.BROWSER_MODE_LOCKED,
                    error.reason,
                )
                rejected = true
            }
            assertTrue("Interaction must still block $operation / $mode", rejected)
        }
    }

    @Test
    fun inputRejectsControlsThatBecomeHiddenDisabledOrCovered() = fixture { runtime, page ->
        script(page, """
            document.body.innerHTML = '<div id="group"><input aria-label="Editable fixture" value="unchanged"></div>' +
              '<select aria-label="Select fixture"><option value="a">First</option><option value="b">Second</option></select>' +
              '<button id="disabled-parent"><span role="button">Nested disabled control</span></button>';
        """.trimIndent())
        val initial = runtime.snapshot()
        val input = ref(initial, "Editable fixture")
        val select = ref(initial, "Select fixture")
        val nested = ref(initial, "Nested disabled control")
        script(page, "document.getElementById('group').hidden = true")
        assertRejected { runtime.input("type", input, "must not enter", null, null, true, waitSeconds = 0); runtime.snapshot() }
        script(page, "document.getElementById('group').hidden = false; document.getElementById('group').setAttribute('aria-disabled','true')")
        assertRejected { runtime.input("type", input, "must not enter", null, null, true, waitSeconds = 0); runtime.snapshot() }
        script(page, """
            document.getElementById('group').removeAttribute('aria-disabled');
            document.getElementById('disabled-parent').disabled = true;
        """.trimIndent())
        assertRejected { runtime.click(nested, waitSeconds = 0); runtime.snapshot() }
        script(page, """
            const cover = document.createElement('div');
            cover.style.cssText = 'position:fixed;inset:0;z-index:99999;background:white';
            document.body.append(cover);
        """.trimIndent())
        assertRejected { runtime.input("type", input, "must not enter", null, null, true, waitSeconds = 0); runtime.snapshot() }
        assertRejected { runtime.input("select", select, null, "b", null, true, waitSeconds = 0); runtime.snapshot() }
        assertEquals("\"unchanged\"", script(page, "document.querySelector('input').value"))
        assertEquals("\"a\"", script(page, "document.querySelector('select').value"))
    }

    @Test
    fun nestedScrollingUsesTheContainerViewport() = fixture { runtime, page ->
        script(page, """
            document.body.innerHTML = '<div id="list" aria-label="Scroll fixture" style="height:120px;overflow:auto">' +
              '<div style="height:1200px">Scrollable content</div></div>';
        """.trimIndent())
        val list = ref(runtime.snapshot(), "Scroll fixture")
        for ((amount, expected) in listOf("half_page" to 60, "start" to 0, "page" to 108, "end" to 1080)) {
            runtime.scroll("down", amount, list, waitSeconds = 0)
            runtime.snapshot()
            assertEquals(expected.toString(), script(page, "Math.round(document.getElementById('list').scrollTop)"))
        }
        assertEquals("0", script(page, "Math.round(document.scrollingElement.scrollTop)"))
    }

    @Test
    fun snapshotWithoutADocumentRootDoesNotFail() = fixture { runtime, page ->
        script(page, "document.documentElement.remove()")
        val result = runtime.snapshot()
        assertTrue(result.getValue("interactive_elements").jsonArray.isEmpty())
        assertEquals("_No readable text in the current viewport._", result.getValue("markdown").jsonPrimitive.content)
        assertTrue(result.getValue("viewport").jsonObject.getValue("document_height").jsonPrimitive.content.toInt() > 0)
    }

    @Test
    fun handoffKeepsDelayedScriptsAndFramesFunctional() = fixture { runtime, page ->
        for (frame in listOf(false, true)) coroutineScope {
            val requested = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            try {
                withContext(Dispatchers.Main) {
                    val original = page.webViewClient
                    val target = if (frame) "parent" else "window"
                    val handler = """
                        $target.document.getElementById('handoff-probe').onclick = () => {
                          $target.document.getElementById('handoff-probe').textContent = 'Handoff action completed';
                        };
                        $target.document.getElementById('handoff-probe').textContent = 'Handoff action ready';
                    """.trimIndent()
                    val resource = if (frame) "<iframe src='/handoff-resource'></iframe>"
                        else "<script src='/handoff-resource'></script>"
                    page.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) =
                            original.onPageStarted(view, url, favicon)
                        override fun onPageFinished(view: WebView, url: String) =
                            original.onPageFinished(view, url)
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val body = when (request.url.path) {
                                "/handoff" -> "<!doctype html><button id='handoff-probe'>Handoff action waiting</button>$resource"
                                "/handoff-resource" -> {
                                    requested.complete(Unit)
                                    check(release.await(30, TimeUnit.SECONDS)) { "Handoff fixture resource was not released" }
                                    if (frame) "<!doctype html><script>$handler</script>" else handler
                                }
                                else -> ""
                            }
                            val mime = if (!frame && request.url.path == "/handoff-resource") "application/javascript" else "text/html"
                            return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(body.toByteArray()))
                        }
                    }
                    page.loadUrl("https://93.184.215.14/handoff")
                }
                withTimeout(10_000) { requested.await() }
                withTimeout(5_000) {
                    while (script(page, "document.getElementById('handoff-probe')?.textContent") != "\"Handoff action waiting\"") delay(50)
                }
                val oldRef = ref(runtime.snapshot(), "Handoff action waiting")
                val handoff = async { runtime.requestAssistance("Use the synthetic page", true, emptyList()) }
                val request = withTimeout(5_000) { runtime.assistance.request.filterNotNull().first() }
                assertTrue(runtime.state.value.userControl)
                release.countDown()
                withTimeout(10_000) {
                    while (script(page, "document.getElementById('handoff-probe').textContent") != "\"Handoff action ready\"") delay(50)
                }
                script(page, "document.getElementById('handoff-probe').click()")
                val outcome = if (frame) "cancelled" else "completed"
                runtime.assistance.respond(request.id, UserAssistanceAnswer(outcome))
                assertEquals(outcome, handoff.await().outcome)
                assertFalse(runtime.state.value.userControl)
                assertRejected { runtime.click(oldRef, waitSeconds = 0); runtime.snapshot() }
                assertTrue(runtime.snapshot().getValue("markdown").jsonPrimitive.content.contains("Handoff action completed"))
                assertEquals("\"/handoff\"", script(page, "location.pathname"))
            } finally {
                release.countDown()
                runtime.assistance.request.value?.let {
                    runtime.assistance.respond(it.id, UserAssistanceAnswer("cancelled"))
                }
            }
        }
    }

    private suspend fun installCommentEditor(page: WebView) {
        script(page, """
            document.body.innerHTML = '<div id="comments"></div>';
            const comments = document.getElementById('comments').attachShadow({mode:'open'});
            comments.innerHTML = '<div id="box"></div>';
            const box = comments.getElementById('box').attachShadow({mode:'open'});
            box.innerHTML = '<button id="reply">Reply fixture</button><div contenteditable="true" aria-label="Comment fixture"></div><button id="publish" hidden>Publish fixture</button>';
            const editor = box.querySelector('[contenteditable]');
            const publish = box.getElementById('publish');
            window.fixturePublishes = 0;
            publish.addEventListener('click', () => { window.fixturePublishes++; });
            box.getElementById('reply').addEventListener('click', () => { editor.focus(); });
            editor.addEventListener('focus', () => { publish.hidden = false; });
            editor.addEventListener('input', () => { publish.disabled = !editor.textContent.trim(); });
        """.trimIndent())
    }

    private suspend fun assertCommentPublishVisible(page: WebView, result: JsonObject) {
        assertEquals("The page itself must reveal the publish control", "false",
            script(page, "document.getElementById('comments').shadowRoot.getElementById('box').shadowRoot.getElementById('publish').hidden"))
        assertTrue("The revealed publish control must be available in the snapshot",
            result.getValue("interactive_elements").jsonArray.any {
                it.jsonObject["name"]?.jsonPrimitive?.content == "Publish fixture"
            })
        assertEquals("Editing must not publish", "0", script(page, "window.fixturePublishes"))
    }

    @Test
    fun exposesSafeControlsWithoutEditableValuesAndRejectsCoveredClicks() = fixture { runtime, page ->
        val initial = runtime.snapshot()
        for (secret in listOf("fixture-password", "fixture-account", "fixture-editor", "fixture-hidden", "fixture-script")) {
            assertFalse(secret, initial.toString().contains(secret))
        }
        val input = ref(initial, "Search fixture")
        val choices = initial.getValue("interactive_elements").jsonArray.map { it.jsonObject }
            .filter { it["options"] != null }
        assertTrue(choices.flatMap { it.getValue("options").jsonArray }.sumOf { it.jsonPrimitive.content.length } <= 2000)
        assertTrue(choices.any { it["options_truncated"]?.jsonPrimitive?.content == "true" })
        runtime.input(operation = "type", ref = input, text = "new-private-query", value = null, key = null, clear = true, waitSeconds = 0)
        val typed = runtime.snapshot()
        assertEquals("\"new-private-query\"", script(page, "document.querySelector('input[type=text]').value"))
        assertFalse(typed.toString().contains("new-private-query"))
        val editor = ref(runtime.snapshot(), "Editor fixture")
        runtime.input(operation = "type", ref = editor, text = "private editor content", value = null, key = null, clear = true, waitSeconds = 0)
        val edited = runtime.snapshot()
        assertEquals("\"private editor content\"", script(page, "document.querySelector('[contenteditable]').textContent"))
        assertFalse(edited.toString().contains("private editor content"))
        val select = ref(runtime.snapshot(), "Sort fixture")
        runtime.input(operation = "select", ref = select, text = null, value = "Newest first", key = null, clear = true, waitSeconds = 0)
        runtime.snapshot()
        assertEquals("\"newest\"", script(page, "document.querySelector('select').value"))
        val shadow = ref(runtime.snapshot(), "Shadow action")
        runtime.click(shadow, waitSeconds = 0)
        runtime.snapshot()
        assertEquals("\"done\"", script(page, "document.getElementById('shadow').dataset.result"))
        val like = ref(runtime.snapshot(), "点赞（Q）")
        script(page, """
            const cover = document.createElement('div');
            cover.id = 'cover';
            cover.style.cssText = 'position:fixed;inset:0;z-index:99999;background:white';
            document.body.append(cover);
        """.trimIndent())
        assertRejected { runtime.click(like, waitSeconds = 0); runtime.snapshot() }
        script(page, "document.getElementById('cover').remove(); document.querySelector('.video-like').style.opacity='0'")
        assertRejected { runtime.click(like, waitSeconds = 0); runtime.snapshot() }
    }

    private fun fixture(block: suspend (AgentBrowserRuntime, WebView) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val runtime = AgentBrowserRuntime(context)
        runtime.beginTurn()
        try {
            val page = runtime.ensureWebView()
            withContext(Dispatchers.Main) {
                page.layout(0, 0, 1080, 1920)
                page.loadDataWithBaseURL("https://semantic.mochi-browser-fixture.example", HTML, "text/html", "UTF-8", null)
            }
            withTimeout(15_000) {
                while (script(page, "document.body?.dataset.ready") != "\"yes\"") delay(50)
            }
            withTimeout(60_000) {
                block(runtime, page)
            }
        } finally {
            runtime.closeTurn()
        }
        assertFalse(runtime.state.value.active)
    }

    private suspend fun AgentBrowserRuntime.snapshot(expectedUrl: String? = null): JsonObject {
        while (true) {
            // Finish the pending observation without opening another long-poll window.
            val result = read(waitSeconds = 0)
            if (result.getValue("snapshot_fresh").jsonPrimitive.boolean &&
                (expectedUrl == null || result.getValue("url").jsonPrimitive.content == expectedUrl)) return result
            delay(50)
        }
    }

    private fun ref(snapshot: JsonObject, name: String): String =
        snapshot.getValue("interactive_elements").jsonArray.map { it.jsonObject }
            .first { it["name"]?.jsonPrimitive?.contentOrNull?.contains(name) == true }
            .getValue("ref").jsonPrimitive.content

    private suspend fun script(page: WebView, code: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            page.evaluateJavascript(code) { if (continuation.isActive) continuation.resume(it) }
        }
    }

    private suspend fun assertRejected(action: suspend () -> Unit) {
        var rejected = false
        try { action() } catch (_: AgentBrowserException) { rejected = true }
        assertTrue("Unsafe or stale action must fail", rejected)
    }

    private companion object {
        val HTML = """
            <!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { font:14px sans-serif; margin:8px }
              nav { height:32px; overflow:hidden }
              nav a { display:inline-block; width:20px; height:12px; font-size:8px }
              .video-like,.video-fav { cursor:pointer; padding:8px; display:inline-block }
            </style>
            <nav></nav><main>
              <h1><a href="https://example.com">Fixture video</a></h1><div><span>Unmarked visible description</span></div>
              <pre><code><span>const n = 1;</span>
            <span>n++;</span></code></pre>
              <table><tr><td><p>Cell one</p></td><td><p>Cell two</p></td></tr></table>
              <div class="video-like" title="点赞（Q）"><span>364</span></div>
              <div class="video-fav" title="收藏（E）"><span>951</span></div>
              <div><input type=text placeholder="Search fixture" value="fixture-account"></div>
              <input type=password aria-label="Password fixture" value="fixture-password">
              <div contenteditable=plaintext-only title="Editor fixture">fixture-editor</div>
              <select aria-label="Sort fixture"><option value=oldest>Oldest first</option><option value=newest>Newest first</option></select>
              <select id=many aria-label="Many choices"></select>
              <div id=shadow></div><div hidden>fixture-hidden</div>
            </main>
            <script>
              const ignored = 'fixture-script';
              document.getElementById('many').innerHTML = Array.from({length:80}, (_,i) =>
                '<option>Choice '+i+' '+ 'long label '.repeat(10)+'</option>').join('');
              document.querySelector('nav').innerHTML = Array.from({length:200}, (_,i) => '<a href="https://example.com">n'+i+'</a>').join('');
              document.querySelector('.video-like').addEventListener('click', e => {
                document.querySelector('.video-like span').textContent = '365';
              });
              const host = document.getElementById('shadow');
              host.attachShadow({mode:'open'}).innerHTML = '<button>Shadow action</button>';
              host.shadowRoot.querySelector('button').onclick = () => host.dataset.result = 'done';
              document.body.dataset.ready = 'yes';
            </script>
        """.trimIndent()
    }
}
