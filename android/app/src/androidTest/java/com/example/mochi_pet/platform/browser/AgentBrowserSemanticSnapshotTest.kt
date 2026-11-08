package com.example.mochi_pet.platform.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private suspend fun AgentBrowserRuntime.snapshot(): JsonObject {
        while (true) {
            // Finish the pending observation without opening another long-poll window.
            val result = read(waitSeconds = 0)
            if (result.getValue("snapshot_fresh").jsonPrimitive.boolean) return result
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
