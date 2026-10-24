package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.mochi_pet.core.browser.BrowserCredentialSummary
import com.example.mochi_pet.core.browser.BrowserLoginKind
import com.example.mochi_pet.core.browser.BrowserWebsite
import com.example.mochi_pet.ui.theme.MochiTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserCredentialsUiTest {
    @get:Rule val compose = createComposeRule()
    private val originalLocale = Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun `handoff prioritizes the page and requires an explicit return`() {
        Locale.setDefault(Locale.ENGLISH)
        var returned = false
        var stopped = false
        var accounts = false
        val menu = mutableStateOf(false)
        compose.setContent {
            MochiTheme {
                BrowserHandoffLayout("https://login.example.com", "Complete sign-in on this page.", false, null,
                    menu.value, { menu.value = it }, {}, { stopped = true }, { accounts = true }, true,
                    { returned = true }) { Text("Synthetic login page") }
            }
        }
        val height = compose.onNodeWithTag("browser-handoff").fetchSemanticsNode().boundsInRoot.height
        val page = compose.onNodeWithTag("handoff-page").fetchSemanticsNode().boundsInRoot
        assertTrue("The web page must retain most of the screen", page.height > height * 0.55f)
        compose.onNodeWithText(localizeUiText("Stop Agent")).assertDoesNotExist()
        compose.onNodeWithTag("handoff-accounts").performClick()
        assertTrue(accounts)
        assertFalse(returned)
        captureFixture("browser-handoff", "browser-handoff-fixture.png")
        compose.onNodeWithTag("handoff-details").performClick()
        compose.onNodeWithText(localizeUiText("Return control when you are ready. Mochi will check the page before continuing."))
            .assertIsDisplayed()
        assertFalse(returned || stopped)
    }

    @Test fun `handoff return and stop remain reachable with large text on a narrow screen`() {
        Locale.setDefault(Locale.ENGLISH)
        var returned = false
        var stopped = false
        val menu = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MochiTheme {
                    BrowserHandoffLayout("https://login.example.com", "Complete sign-in on this page. ".repeat(30),
                        false, null, menu.value, { menu.value = it }, {}, { stopped = true }, {}, true,
                        { returned = true }) { Text("Synthetic page") }
                }
            }
        }
        compose.onNodeWithTag("handoff-return").assertIsDisplayed().performClick()
        assertTrue(returned)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Accounts").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Large-text account label must not split mid-word", 1, layouts.single().lineCount)
        compose.onNodeWithTag("handoff-menu").performClick()
        compose.onNodeWithText(localizeUiText("Stop Agent")).assertIsDisplayed().performClick()
        assertTrue(stopped)
        captureFixture("browser-handoff", "browser-handoff-large-text-fixture.png")
    }

    @Test fun `question choices do not require an input field or a second confirmation`() {
        var answer: String? = null
        compose.setContent {
            MochiTheme { AssistanceQuestionSheet("Pick a day.", listOf("Today", "Tomorrow"), null, {}, { answer = it }) }
        }
        compose.onNodeWithTag("assistance-answer").assertDoesNotExist()
        compose.onNodeWithText(localizeUiText("Tomorrow")).performClick()
        assertEquals("Tomorrow", answer)
    }

    @Test fun `question offers a custom answer without submitting blanks and supports cancellation`() {
        var answer: String? = null
        var cancelled = false
        compose.setContent {
            MochiTheme {
                AssistanceQuestionSheet("Pick a day.", listOf("Today"), null, { cancelled = true }, { answer = it })
            }
        }
        compose.onNodeWithText(localizeUiText("Write another answer")).performClick()
        compose.onNodeWithTag("assistance-send").assertIsNotEnabled()
        compose.onNodeWithTag("assistance-answer").performTextInput("Friday")
        compose.onNodeWithTag("assistance-send").performClick()
        assertEquals("Friday", answer)
        compose.onNodeWithText(localizeUiText("Cancel")).performClick()
        assertTrue(cancelled)
    }

    @Test fun `search stays centered below the logo with separate accounts and top right close`() {
        var closed = false
        compose.setContent {
            MochiTheme {
                BrowserManagementLayout(
                    onClose = { closed = true }, closeEnabled = true,
                    search = {
                        OutlinedTextField("", {}, placeholder = { Text("Search Bing or enter a website") },
                            modifier = Modifier.fillMaxWidth().testTag("browser-search"), singleLine = true)
                    },
                ) {
                    Text("Websites")
                    BrowserWebsiteList(listOf(fixtureWebsite()), {}, Modifier.weight(1f))
                }
            }
        }
        val page = compose.onNodeWithTag("browser-management")
        val bounds = page.fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("browser-search").fetchSemanticsNode().boundsInRoot
        val logo = compose.onNodeWithTag("browser-logo").fetchSemanticsNode().boundsInRoot
        val accounts = compose.onNodeWithTag("browser-accounts-panel").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("browser-management-close").fetchSemanticsNode().boundsInRoot
        assertTrue(search.center.y / bounds.height in 0.4f..0.6f)
        assertTrue(logo.bottom < search.top)
        assertTrue(accounts.top > search.bottom)
        assertTrue(close.center.x > bounds.center.x && close.bottom < logo.top)
        compose.onNodeWithText("example.com").assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Cookies")).assertIsDisplayed()
        captureFixture("browser-management", "browser-accounts-fixture.png")
        compose.onNodeWithTag("browser-management-close").performClick()
        assertTrue(closed)
    }

    @Test fun `compact navigation keeps account label on one line and respects history availability`() {
        Locale.setDefault(Locale.ENGLISH)
        var back = false
        var home = false
        var accounts = false
        compose.setContent {
            MochiTheme {
                BrowserNavigationBar(true, false, { back = true }, { fail("Forward must be disabled") },
                    { home = true }, { accounts = true })
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(localizeUiText("Sites")).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        captureFixture("browser-navigation", "browser-navigation-fixture.png")
        val label = layouts.single()
        assertEquals("Sites", label.layoutInput.text.text)
        assertEquals(1, label.lineCount)
        assertEquals("Sites".length, label.getLineEnd(0, visibleEnd = true))
        assertTrue("Account label must fit without clipping", label.getLineLeft(0) >= 0f &&
            label.getLineRight(0) <= label.size.width && label.getLineBottom(0) <= label.size.height)
        compose.onNodeWithTag("browser-forward").assertIsNotEnabled()
        compose.onNodeWithTag("browser-back").performClick()
        compose.onNodeWithTag("browser-home").performClick()
        compose.onNodeWithTag("browser-accounts").performClick()
        assertTrue(back && home && accounts)
    }

    @Test fun `full left swipe stops halfway and exposes explicit edit and delete actions`() {
        var edited = false
        var deleted = false
        compose.setContent {
            MochiTheme {
                CredentialRow(BrowserCredentialSummary("fixture", "v1", "https://example.com/", "fixture-user"),
                    {}, { edited = true }, { deleted = true })
            }
        }
        val row = compose.onNodeWithTag("credential-row-fixture")
        val width = row.fetchSemanticsNode().boundsInRoot.width
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val content = compose.onNodeWithTag("credential-content-fixture").fetchSemanticsNode()
        assertEquals(width / 2f, content.boundsInRoot.width, 1f)
        assertFalse(deleted)
        compose.onNodeWithText(localizeUiText("Edit")).performClick()
        compose.onNodeWithText(localizeUiText("Delete")).performClick()
        assertTrue(edited)
        assertTrue(deleted)
    }

    @Test fun `cookie swipe keeps half the row with only an explicit delete action`() {
        var deleted = false
        compose.setContent { MochiTheme { WebsiteDataRow("example.com", { deleted = true }) } }
        val row = compose.onNodeWithTag("site-data-row-example.com")
        val width = row.fetchSemanticsNode().boundsInRoot.width
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val content = compose.onNodeWithTag("site-data-content-example.com")
        assertEquals(width / 2f, content.fetchSemanticsNode().boundsInRoot.width, 1f)
        content.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(width / 2f, content.fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.onNodeWithText(localizeUiText("Edit")).assertDoesNotExist()
        assertFalse(deleted)
        compose.onNodeWithText(localizeUiText("Delete")).performClick()
        assertTrue(deleted)
    }

    @Test fun `website detail list distinguishes website data and both account kinds`() {
        compose.setContent {
            MochiTheme {
                Surface {
                    BrowserWebsiteDetails(fixtureWebsite(), {}, {}, {}, {}, {}, {}, true,
                        Modifier.fillMaxSize().padding(horizontal = 20.dp))
                }
            }
        }
        compose.onNodeWithText(localizeUiText("Cookies / site data")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Password account")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("SMS login")).performScrollTo().assertIsDisplayed()
        captureFixture("browser-website-details", "browser-website-details-fixture.png")
    }

    private fun fixtureWebsite() = BrowserWebsite(
        "example.com", "https://www.example.com/",
        listOf(
            BrowserCredentialSummary("fixture", "v1", "https://www.example.com/", "demo@mochi.example"),
            BrowserCredentialSummary("sms-fixture", "v1", "https://login.example.com/", "+12025550123", BrowserLoginKind.SMS),
        ), true,
    )

    private fun captureFixture(tag: String, name: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val view = requireNotNull(node.root as? ViewRootForTest).view.rootView
        val bounds = node.boundsInRoot
        val image = android.graphics.Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = android.graphics.Canvas(image)
            canvas.translate(-bounds.left, -bounds.top)
            view.draw(canvas)
        }
        java.io.File("build/reports/$name").apply { checkNotNull(parentFile).mkdirs() }.outputStream().use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
