package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import com.example.mochi_pet.core.media.MEDIA_TOOL_NAMES
import com.example.mochi_pet.core.tools.BUILT_IN_TOOLS
import com.example.mochi_pet.core.tools.BuiltInToolSummary
import com.example.mochi_pet.core.tools.MediaProviderSummary
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_ui.MochiTheme
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaToolsUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    private val summary = MediaProviderSummary(tools = BUILT_IN_TOOLS.filter { it.name in MEDIA_TOOL_NAMES }
        .map { BuiltInToolSummary(it.name, it.displayName, it.description, true) })
    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `media appears between Browser and Amap and outside Mochi builtins`() {
        compose.setContent {
            MochiTheme {
                ToolsSurface(
                    state = ToolsUiState(catalog = ToolCatalogSummary(media = summary), isLoading = false),
                    onSetBuiltInEnabled = { _, _ -> }, onConnectNotion = {}, onDisconnectNotion = {},
                    onFeishuAction = {}, onOpenTencentDocsTokenPage = {}, onConfigureTencentDocs = {},
                    onDisconnectTencentDocs = {}, onOpenAmapConsole = {}, onConfigureAmap = { _, _ -> },
                    onDisconnectAmap = {}, onSetAmapEnabled = {}, onSetAgentBrowserEnabled = {},
                    onSetMediaEnabled = {}, onManageBrowser = {}, onAgentLinkAction = {},
                    onInstallMijia = {}, onConfigureMijia = {}, onDisconnectMijia = {},
                    onSetMijiaEnabled = {}, onSetMijiaToolEnabled = { _, _ -> },
                    onTermuxAction = {}, onWebdavAction = {}, onAddServer = {},
                    restActions = RestApiActions({}, { _, _, _ -> error("Not used") }),
                    onRemoveServer = {}, onSetServerEnabled = { _, _ -> }, onSetToolEnabled = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("tools-list").performScrollToIndex(1)
        compose.onNodeWithText("Agent Browser").assertIsDisplayed()
        compose.onNodeWithTag("tools-list").performScrollToIndex(2)
        compose.onNodeWithText("Media Control").assertIsDisplayed()
        compose.onNodeWithTag("tools-list").performScrollToIndex(3)
        compose.onNodeWithText("Amap Maps").assertIsDisplayed()
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
        compose.onNodeWithText("Notification access required for players").assertIsDisplayed()
        compose.onNodeWithText("Grant notification access").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show tools (3)").performScrollTo().performClick()
        compose.onNodeWithText("media_read").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("media_control").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("media_volume").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[2].assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[3].assertIsNotEnabled()
    }
}
