package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.core.settings.ProviderPreset
import com.example.mochi_pet.core.settings.ProviderProfileInput
import com.example.mochi_pet.core.settings.ProviderProfileSummary
import com.example.mochi_pet.core.settings.ProviderProfilesSummary
import com.example.mochi_pet.core.settings.ProviderSettingsSummary
import com.example.mochi_pet.core.settings.ProviderShareSelection
import com.example.mochi_pet.core.wake.WakeRuntimeState
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderProfilesUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @After fun restore() { Locale.setDefault(locale) }
    private val first = profile("first", "First")
    private val second = profile("second", "Second")

    @Test fun `OpenAI endpoint is filled and selecting Kimi replaces it before saving`() {
        Locale.setDefault(Locale.ENGLISH)
        var saved: ProviderProfileInput? = null
        compose.setContent {
            MaterialTheme {
                ProviderConnectionDialog(null, ProviderSettingsUiState(isLoading = false), {}, { saved = it })
            }
        }
        compose.onNodeWithText("API endpoint").performScrollTo().assertTextContains("https://api.openai.com/v1")
        compose.onNode(hasText("OpenAI") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Kimi").performScrollTo().performClick()
        compose.onNodeWithText("API endpoint").performScrollTo().assertTextContains("https://api.moonshot.cn/v1")
        compose.onNodeWithText("Model name").performScrollTo().performTextInput("fixture-model")
        compose.onNodeWithText("API key").performScrollTo().performTextInput("synthetic-key")
        compose.onNodeWithText("Save connection").performClick()
        assertEquals(ProviderPreset.KIMI, saved!!.preset)
        assertEquals("https://api.moonshot.cn/v1", saved!!.settings.endpoint)
    }

    @Test fun `share import is first and all Settings parts remain reachable at large font`() {
        Locale.setDefault(Locale.ENGLISH)
        render(fontScale = 1.5f)
        compose.onNodeWithTag("settings-parts").performScrollToIndex(0)
        compose.onNodeWithTag("settings-part-Share and import").assertIsDisplayed()
        for (title in listOf("AI connections", "Speech and wake", "Conversation and persona", "Display and language")) {
            compose.onNodeWithTag("settings-parts").performScrollToNode(hasTestTag("settings-part-$title"))
            compose.onNodeWithTag("settings-part-$title").assertIsDisplayed()
        }
    }

    @Test fun `share selection starts with active profile and permits multiple saved accounts`() {
        Locale.setDefault(Locale.ENGLISH)
        var selection: ProviderShareSelection? = null
        render(share = { selection = it })
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasText("Share Providers"))
        compose.onNodeWithText("Share Providers").performClick()
        compose.onNode(hasText("Second") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Share selected").performClick()
        assertEquals(setOf("first", "second"), selection!!.llmProfileIds)
    }

    @Test fun `Chinese Settings shows localized section heading first`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        render()
        compose.onNodeWithText("分享与导入").assertIsDisplayed()
        compose.onNodeWithTag("settings-parts").performScrollToNode(hasTestTag("settings-part-AI connections"))
        compose.onNodeWithText("AI 连接").assertIsDisplayed()
    }

    private fun render(fontScale: Float = 1f, share: (ProviderShareSelection) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    ProviderSettingsSurface(
                        state = ProviderSettingsUiState(summary = first.settings, isLoading = false,
                            profiles = ProviderProfilesSummary(listOf(first, second), first.id)),
                        speechState = SpeechSettingsUiState(isLoading = false),
                        providerShareState = ProviderShareUiState(), toolsState = ToolsUiState(),
                        agentSettingsState = AgentSettingsUiState(), personaState = PersonaUiState(),
                        wakeState = WakeRuntimeState(), wakeFeedback = null,
                        onEnableWake = {}, onDisableWake = {}, onProviderAction = {}, onSaveSpeech = {},
                        onCreateProviderShareLink = share, onReceiveProviderShareLink = {},
                        onSetRecentConversationTurns = {}, onSetFocusStandby = { _, _ -> },
                        onSavePersona = { _, _, _ -> },
                    )
                }
            }
        }
    }

    private fun profile(id: String, name: String) = ProviderProfileSummary(
        id, name, ProviderPreset.OPENAI,
        ProviderSettingsSummary(providerType = ProviderPreset.OPENAI.protocol,
            endpoint = ProviderPreset.OPENAI.endpoint, model = "fixture-model", hasApiKey = true),
    )
}
