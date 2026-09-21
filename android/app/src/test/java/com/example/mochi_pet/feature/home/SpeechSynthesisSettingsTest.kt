package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.settings.SpeechSettingsInput
import com.example.mochi_pet.core.settings.SpeechSettingsSummary
import com.example.mochi_pet.core.wake.WakeRuntimeState
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
class SpeechSynthesisSettingsTest {
    @get:Rule
    val compose = createComposeRule()
    private val originalLocale = Locale.getDefault()
    private val saved = mutableListOf<SpeechSettingsInput>()

    @Before
    fun setLanguage() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLanguage() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `synthesis opt in and voice save without replacing stored credentials`() {
        show(
            SpeechSettingsSummary(
                provider = SpeechProvider.IFLYTEK,
                iFlytekAppId = "test-app",
                hasIFlytekApiKey = true,
                hasIFlytekApiSecret = true,
            ),
        )
        scrollTo("Also use this provider for speech synthesis")
        val toggle = compose.onNode(
            isToggleable() and hasAnySibling(hasText("Also use this provider for speech synthesis")),
        )
        toggle.assertIsOff().performScrollTo().performClick()
        scrollTo("Voice")
        compose.onNodeWithText("Voice").performScrollTo().performClick()
        compose.onNodeWithText("Xiaoyan").performScrollTo().performClick()
        compose.onNodeWithText("Confirm selection").performScrollTo().performClick()
        scrollTo("Save speech settings")
        compose.onNodeWithText("Save speech settings").performScrollTo().performClick()
        val input = saved.single()
        assertTrue(input.synthesisEnabled)
        assertEquals("x4_xiaoyan", input.iFlytekVoice)
        assertEquals("test-app", input.iFlytekAppId)
        assertEquals("", input.iFlytekApiKeyReplacement)
        assertEquals("", input.iFlytekApiSecretReplacement)
    }

    @Test
    fun `saved Azure synthesis selection and voice are displayed`() {
        show(
            SpeechSettingsSummary(
                provider = SpeechProvider.AZURE,
                azureEndpoint = "https://test.cognitiveservices.azure.com",
                hasAzureApiKey = true,
                synthesisEnabled = true,
                azureVoice = "zh-CN-XiaoxiaoNeural",
            ),
        )
        scrollTo("Also use this provider for speech synthesis")
        compose.onNode(
            isToggleable() and hasAnySibling(hasText("Also use this provider for speech synthesis")),
        ).assertIsOn()
        compose.onNodeWithText("zh-CN-XiaoxiaoNeural").assertExists()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun show(summary: SpeechSettingsSummary) {
        compose.setContent {
            MaterialTheme {
                ProviderSettingsSurface(
                    state = ProviderSettingsUiState(isLoading = false),
                    speechState = SpeechSettingsUiState(summary = summary, isLoading = false),
                    providerShareState = ProviderShareUiState(),
                    toolsState = ToolsUiState(),
                    agentSettingsState = AgentSettingsUiState(),
                    personaState = PersonaUiState(),
                    wakeState = WakeRuntimeState(),
                    wakeFeedback = null,
                    onEnableWake = {},
                    onDisableWake = {},
                    onSave = {},
                    onSaveSpeech = saved::add,
                    onCreateProviderShareLink = {},
                    onReceiveProviderShareLink = {},
                    onSetRecentConversationTurns = {},
                    onSetFocusStandby = { _, _ -> },
                    onSavePersona = { _, _, _ -> },
                )
            }
        }
    }
}
