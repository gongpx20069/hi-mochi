package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import com.example.mochi_pet.core.extensions.TermuxApprovalChoice
import com.example.mochi_pet.core.extensions.TermuxApprovalRequest
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `dialog displays exact command and approval nonce`() {
        val actions = mutableListOf<TermuxUiAction>()
        val request = TermuxApprovalRequest("nonce", "termux_exec", buildJsonObject { put("command", "echo hello") })
        compose.setContent { MaterialTheme { TermuxApprovalDialog(request, actions::add) {} } }
        compose.onNodeWithText("termux_exec\n${request.arguments}").assertExists()
        compose.onNodeWithText("Execute once").performClick()
        assertEquals(listOf(TermuxUiAction.Approve("nonce", TermuxApprovalChoice.ONCE)), actions)
    }

    @Test
    fun `voice authorization requires a complete explicit phrase`() {
        assertEquals(TermuxApprovalChoice.ONCE, termuxVoiceChoice("执行一次。"))
        assertEquals(TermuxApprovalChoice.THIS_RUN, termuxVoiceChoice("allow this task"))
        assertEquals(TermuxApprovalChoice.DENY, termuxVoiceChoice("取消"))
        assertNull(termuxVoiceChoice("不要执行一次"))
        assertNull(termuxVoiceChoice("say execute once"))
    }

    @Test
    fun `background switch requires explicit confirmation and cancel grants nothing`() {
        val actions = mutableListOf<TermuxUiAction>()
        compose.setContent {
            MaterialTheme {
                TermuxProviderCard(
                    ExtensionProviderSummary(installed = true, trusted = true, connected = true, enabled = true),
                    backgroundEnabled = false, disabled = false, onAction = actions::add,
                )
            }
        }
        val toggle = compose.onNodeWithContentDescription("Background Shell authorization")
        toggle.assertIsOff().performClick()
        compose.onNodeWithText("Allow background Shell execution?").assertExists()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(actions.isEmpty())
        toggle.assertIsOff().performClick()
        compose.onNodeWithText("Authorize background Shell").performClick()
        assertEquals(listOf(TermuxUiAction.EnableBackground(true)), actions)
    }

    @Test
    fun `background authorization can be revoked when connection is unavailable`() {
        val actions = mutableListOf<TermuxUiAction>()
        compose.setContent {
            MaterialTheme {
                TermuxProviderCard(
                    ExtensionProviderSummary(),
                    backgroundEnabled = true, disabled = false, onAction = actions::add,
                )
            }
        }
        compose.onNodeWithContentDescription("Background Shell authorization").assertIsOn().performClick()
        assertEquals(listOf(TermuxUiAction.EnableBackground(false)), actions)
        compose.onNodeWithText("Allow background Shell execution?").assertDoesNotExist()
    }

    @Test
    fun `background authorization cannot be enabled before provider is ready`() {
        compose.setContent {
            MaterialTheme {
                TermuxProviderCard(
                    ExtensionProviderSummary(),
                    backgroundEnabled = false, disabled = false, onAction = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Background Shell authorization").assertIsOff().assertIsNotEnabled()
    }
}
