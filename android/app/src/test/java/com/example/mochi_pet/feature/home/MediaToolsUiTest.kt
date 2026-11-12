package com.example.mochi_pet.feature.home

import android.app.Application
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import com.example.mochi_pet.core.media.MEDIA_TOOL_NAMES
import com.example.mochi_pet.core.tools.BUILT_IN_TOOLS
import com.example.mochi_pet.core.tools.BuiltInToolSummary
import com.example.mochi_pet.core.tools.MediaProviderSummary
import com.example.mochi_pet.core.tools.AppControlProviderSummary
import com.example.mochi_pet.core.apps.APP_TOOL_NAMES
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_ui.MochiTheme
import com.example.mochi_pet.platform.media.SelectableApp
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaToolsUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    private val summary = MediaProviderSummary(tools = BUILT_IN_TOOLS.filter { it.name in MEDIA_TOOL_NAMES }
        .map { BuiltInToolSummary(it.name, it.displayName, it.description, true) })
    private val appSummary = AppControlProviderSummary(tools = BUILT_IN_TOOLS.filter { it.name in APP_TOOL_NAMES }
        .map { BuiltInToolSummary(it.name, it.displayName, it.description, true) })
    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `background permission entry explains manual setup and only opens Mochi app settings after confirmation`() {
        val application = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(application)
        compose.setContent {
            MochiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppControlProviderCard(appSummary.copy(enabled = false), false, {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("app-background-permission").performScrollTo().performClick()
        compose.onNodeWithText("Grant it manually if your system offers it. Mochi cannot reliably read this permission's status.")
            .performScrollTo().assertIsDisplayed()
        assertNull(shadow.nextStartedActivity)
        compose.onNodeWithText("Cancel").performClick()
        assertNull(shadow.nextStartedActivity)
        compose.onNodeWithTag("app-background-permission").performClick()
        compose.onNodeWithText("Open settings").performClick()
        val intent = shadow.nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${application.packageName}", intent.data.toString())
        compose.onNodeWithText("Open Mochi while another app is in the foreground").assertIsDisplayed()
    }

    @Test fun `browser capability detail exposes login preparation without unrelated tools`() {
        var managedBrowser = false
        compose.setContent {
            MochiTheme {
                ToolCapabilityDetail(
                    detailGroup = "browser",
                    state = ToolsUiState(catalog = ToolCatalogSummary(media = summary, appControl = appSummary), isLoading = false),
                    onSetBuiltInEnabled = { _, _ -> }, onConnectNotion = {}, onDisconnectNotion = {},
                    onFeishuAction = {}, onOpenTencentDocsTokenPage = {}, onConfigureTencentDocs = {},
                    onDisconnectTencentDocs = {}, onOpenAmapConsole = {}, onConfigureAmap = { _, _ -> },
                    onDisconnectAmap = {}, onSetAmapEnabled = {}, onSetAgentBrowserEnabled = {},
                    onSetMediaEnabled = {}, onManageBrowser = { managedBrowser = true }, onAgentLinkAction = {},
                    onInstallMijia = {}, onConfigureMijia = {}, onDisconnectMijia = {},
                    onSetMijiaEnabled = {}, onSetMijiaToolEnabled = { _, _ -> },
                    onTermuxAction = {}, onWebdavAction = {}, onAddServer = {},
                    restActions = RestApiActions({}, { _, _, _ -> error("Not used") }),
                    onRemoveServer = {}, onSetServerEnabled = { _, _ -> }, onSetToolEnabled = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Agent Browser").assertIsDisplayed()
        compose.onNodeWithTag("browser-websites-settings").performClick()
        assertTrue(managedBrowser)
        compose.onNodeWithText("App Control").assertDoesNotExist()
        compose.onNodeWithText("Media Control").assertDoesNotExist()
        compose.onNodeWithText("Amap Maps").assertDoesNotExist()
        compose.onNodeWithText("Mochi Built-ins").assertDoesNotExist()
    }

    @Test fun `disabled group preserves child choices and exposes authorization disclosure`() {
        compose.setContent {
            MochiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MediaProviderCard(summary.copy(enabled = false), false, {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("media-player-access").assertIsDisplayed()
        compose.onNodeWithTag("media-app-assistance").assertDoesNotExist()
        compose.onNodeWithText("Control playback, request songs and adjust media volume.").assertIsDisplayed()
        captureFixture("media-player-access", "media-control-card.png")
        val disclosure = "Notification access is a broad Android permission. Mochi uses media sessions only, not message notifications. Track metadata may be sent to your model Provider."
        compose.onNodeWithText(disclosure).assertDoesNotExist()
        compose.onNodeWithTag("media-player-access").performClick()
        compose.onNodeWithText(disclosure).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(disclosure).assertDoesNotExist()
        compose.onNodeWithText("Show tools (4)").performScrollTo().performClick()
        compose.onNodeWithText("media_read").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("media_play_from_search").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("media_control").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("media_volume").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[2].assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[3].assertIsNotEnabled()
    }

    @Test fun `app collection has its own scope assistance and five tools without player permission`() {
        compose.setContent {
            MochiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppControlProviderCard(appSummary.copy(enabled = false), false, {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("media-player-access").assertDoesNotExist()
        compose.onNodeWithTag("media-app-selection").assertIsDisplayed()
        captureFixture("media-app-selection", "app-control-card.png")
        compose.onNodeWithTag("media-app-selection").performClick()
        compose.onNodeWithTag("media-app-picker").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("media-app-assistance").performClick()
        compose.onNodeWithText("Only your selected apps").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Show tools (5)").performScrollTo().performClick()
        APP_TOOL_NAMES.forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onAllNodes(isToggleable())[1].assertIsNotEnabled()
    }

    @Test fun `app sheet keeps selection across search and saves an explicit empty scope`() {
        var saved: Set<String>? = null
        compose.setContent {
            MochiTheme {
                MediaAppSelectionSheet(
                    listOf(SelectableApp("fixture.music", "Music", true), SelectableApp("fixture.reader", "Reader", false)),
                    setOf("fixture.music"), {}, { saved = it },
                )
            }
        }
        compose.onNodeWithTag("media-app-save").assertIsDisplayed()
        captureFixture("media-app-picker", "media-app-picker.png")
        compose.onNodeWithTag("media-app-search").performTextInput("Reader")
        compose.onNodeWithTag("allow-app-fixture.reader").performClick()
        compose.onNodeWithTag("media-app-search").performTextClearance()
        compose.onNodeWithText("Selected apps: 2").assertIsDisplayed()
        compose.onNodeWithTag("allow-app-fixture.music").performClick()
        compose.onNodeWithTag("allow-app-fixture.reader").performClick()
        compose.onNodeWithTag("media-app-save").performClick()
        assertEquals(emptySet<String>(), saved)
    }

    @Test fun `dismissing the app sheet does not save edited selection`() {
        var saved: Set<String>? = null
        var dismissed = false
        compose.setContent {
            MochiTheme {
                MediaAppSelectionSheet(listOf(SelectableApp("fixture.music", "Music", true)),
                    setOf("fixture.music"), { dismissed = true }, { saved = it })
            }
        }
        compose.onNodeWithTag("allow-app-fixture.music").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertNull(saved)
    }

    private fun captureFixture(tag: String, name: String) {
        val root = compose.onNodeWithTag(tag).fetchSemanticsNode().root as ViewRootForTest
        val bitmap = Bitmap.createBitmap(root.view.width, root.view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/$name")
        file.parentFile.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
