package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.agentlink.AGENTLINK_TOOLS
import com.example.mochi_pet.core.agentlink.AgentLinkChatLink
import com.example.mochi_pet.core.agentlink.AgentLinkState
import com.example.mochi_pet.core.agentlink.AgentLinkUiAction
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
class AgentLinkProviderCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val originalLocale = Locale.getDefault()

    @Before
    fun setLanguage() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLanguage() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `tools start collapsed and expanded selection survives restoration`() {
        val actions = mutableListOf<AgentLinkUiAction>()
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AgentLinkProviderCard(
                        state = AgentLinkState(
                            installed = true,
                            authorized = true,
                            enabled = true,
                            status = "connected",
                        ),
                        loading = false,
                        onAction = actions::add,
                    )
                }
            }
        }

        compose.onNodeWithText("agentlink_workspace").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithText("Show tools (3)").performClick()
        compose.onNodeWithText("AgentLink Workspaces").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(4)
        compose.onAllNodes(isToggleable())[1].assertIsOn().performClick()
        assertEquals(
            listOf(AgentLinkUiAction.EnableTool("agentlink_workspace", false)),
            actions,
        )
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Hide tools (3)").assertIsDisplayed()
        compose.onNodeWithText("Hide tools (3)").performClick()
        compose.onNodeWithText("agentlink_workspace").assertDoesNotExist()
    }

    @Test
    fun `narrow card preserves authorization and linked chat actions`() {
        val actions = mutableListOf<AgentLinkUiAction>()
        val link = AgentLinkChatLink("machine", "chat", "Test chat")
        val loading = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.width(240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AgentLinkProviderCard(
                        state = AgentLinkState(
                            installed = true,
                            authorized = true,
                            status = "connected",
                            links = listOf(link),
                        ),
                        loading = loading.value,
                        onAction = actions::add,
                    )
                }
            }
        }

        val connect = compose.onNodeWithText("Connect/Open AgentLink")
        val refresh = compose.onNodeWithText("Refresh")
        connect.assertIsDisplayed().performClick()
        refresh.assertIsDisplayed().performClick()
        assertTrue(
            "Refresh should wrap below Connect on a 240dp card: " +
                "${connect.fetchSemanticsNode().boundsInRoot}, " +
                "${refresh.fetchSemanticsNode().boundsInRoot}",
            refresh.fetchSemanticsNode().boundsInRoot.top >=
                connect.fetchSemanticsNode().boundsInRoot.bottom,
        )
        compose.onNodeWithText("Connection settings").performScrollTo().performClick()
        compose.onNodeWithText("Manage access").performScrollTo().performClick()
        compose.onNodeWithText("Revoke").performScrollTo().performClick()
        compose.onNodeWithText("Linked remote chats · refresh via tools").performScrollTo().performClick()
        compose.onNodeWithText("Test chat").performScrollTo().performClick()
        assertEquals(
            listOf(
                AgentLinkUiAction.Connect,
                AgentLinkUiAction.Refresh,
                AgentLinkUiAction.Manager,
                AgentLinkUiAction.Revoke,
                AgentLinkUiAction.OpenChat(link),
            ),
            actions,
        )
        compose.runOnIdle { loading.value = true }
        compose.onNodeWithText("Test chat").assertIsNotEnabled()
        compose.onNodeWithText("Show tools (3)").performScrollTo().performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(4)
        repeat(4) { compose.onAllNodes(isToggleable())[it].assertIsNotEnabled() }
    }

    @Test
    fun `disconnected card keeps setup available without a provider switch`() {
        compose.setContent {
            MaterialTheme {
                AgentLinkProviderCard(
                    state = AgentLinkState(),
                    loading = false,
                    onAction = {},
                )
            }
        }

        compose.onNodeWithText("Connect/Open AgentLink").assertIsNotEnabled()
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        compose.onNodeWithText("Show tools (3)").assertIsDisplayed()
        compose.onNodeWithText("Manage access").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun `tool labels cover the contract and follow shared localization`() {
        assertEquals(
            AGENTLINK_TOOLS,
            AGENTLINK_TOOL_DESCRIPTORS.map { it.name }.toSet(),
        )
        for (tool in AGENTLINK_TOOL_DESCRIPTORS) {
            assertNotEquals(tool.displayName, localizeUiText(tool.displayName, "zh"))
            assertNotEquals(tool.description, localizeUiText(tool.description, "zh"))
        }
        assertEquals(
            "连接/打开 AgentLink",
            localizeUiText("Connect/Open AgentLink", "zh"),
        )
    }
}
