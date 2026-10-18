package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.mochi_pet.core.browser.BrowserCredentialSummary
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
                    Text("Saved accounts")
                    CredentialRow(BrowserCredentialSummary("fixture", "v1", "https://example.com/", "demo@mochi.example"),
                        {}, {}, {})
                    CredentialRow(BrowserCredentialSummary("sms-fixture", "v1", "https://sms.example/", "+12025550123",
                        com.example.mochi_pet.core.browser.BrowserLoginKind.SMS), {}, {}, {})
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
        compose.onNodeWithText(localizeUiText("SMS login")).assertIsDisplayed()
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
        compose.onNodeWithText(localizeUiText("Accounts")).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        captureFixture("browser-navigation", "browser-navigation-fixture.png")
        val label = layouts.single()
        assertEquals("Accounts", label.layoutInput.text.text)
        assertEquals(1, label.lineCount)
        assertEquals("Accounts".length, label.getLineEnd(0, visibleEnd = true))
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
