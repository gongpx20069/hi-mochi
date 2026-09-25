package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
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
class TermuxUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test
    fun `card has no separate execution authorization and provider toggle acts directly`() {
        val actions = mutableListOf<TermuxUiAction>()
        compose.setContent {
            MaterialTheme {
                TermuxProviderCard(
                    ExtensionProviderSummary(installed = true, trusted = true, connected = true, enabled = true),
                    disabled = false, onAction = actions::add,
                )
            }
        }
        compose.onNodeWithText("Connection settings").performClick()
        compose.onNodeWithText("Background Shell authorization").assertDoesNotExist()
        compose.onNodeWithText("Allow Termux command?").assertDoesNotExist()
        compose.onNodeWithText("Enabled commands run automatically for conversations, Scheduled Agents, and Subagents. Output may go to your model Provider.").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].performClick()
        assertEquals(listOf(TermuxUiAction.Enable(false)), actions)
    }

    @Test
    fun `disconnect confirmation remains independent of tool expansion`() {
        val actions = mutableListOf<TermuxUiAction>()
        compose.setContent {
            MaterialTheme {
                TermuxProviderCard(
                    ExtensionProviderSummary(installed = true, trusted = true, connected = true),
                    disabled = false, onAction = actions::add,
                )
            }
        }
        compose.onNodeWithText("Connection settings").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Disconnect Termux?").assertExists()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(actions.isEmpty())
    }
}
