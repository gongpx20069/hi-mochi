package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.mochi_pet.core.extensions.TermuxApprovalChoice
import com.example.mochi_pet.core.extensions.TermuxApprovalRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
