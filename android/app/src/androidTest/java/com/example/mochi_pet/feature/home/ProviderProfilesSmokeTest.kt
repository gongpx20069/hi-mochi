package com.example.mochi_pet.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_ui.MochiTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mochi_pet.MainActivity
import com.example.mochi_pet.MochiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderProfilesSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clearTestScreenFlag() {
        compose.runOnUiThread { compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun holdGestureFinalizesAndCancelsWithoutRecordingOrSending() {
        val state = mutableStateOf(ConversationUiState(draft = "Existing draft"))
        var started = 0
        var finished = 0
        var cancelled = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MochiTheme {
                    ConversationSurface(
                        state = state.value, providerReady = true, voiceState = VoiceRuntimeState(),
                        onSend = { error("Gesture must not send") }, onCancel = {},
                        onDraftChange = { state.value = state.value.copy(draft = it) },
                        onStartDictation = { started++; state.value = state.value.copy(dictation = DictationPhase.RECORDING) },
                        onFinishDictation = { finished++; state.value = state.value.copy(dictation = DictationPhase.IDLE) },
                        onCancelDictation = { cancelled++; state.value = state.value.copy(dictation = DictationPhase.IDLE) },
                        onOpenSpeechSettings = {}, onCardAction = { _, _ -> }, onRemoteTask = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("dictation-input").performTouchInput { longClick() }
        assertEquals(1, started)
        assertEquals(1, finished)
        compose.onNodeWithTag("dictation-input").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(-30f, center.y))
            up()
        }
        assertEquals(2, started)
        assertEquals(1, cancelled)
        assertEquals("Existing draft", state.value.draft)
        compose.onNodeWithTag("conversation-input").assertIsEnabled().performTextInput(" typed")
        assertTrue(state.value.draft.contains("typed"))
    }

    @Test fun compactComposerAndCapabilityPagesRemainReachableWithoutSending() {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val app = compose.activity.application as MochiApplication
        val before = runBlocking(Dispatchers.IO) { app.providerSettingsRepository.loadProfiles() }
        val wakeEnabled = app.wakeRuntime.shouldBeEnabled
        compose.onNode(hasText(localizeUiText("Chat")) and hasClickAction()).performClick()
        compose.waitUntil(10_000) {
            app.wakeRuntime.state.value.status in setOf(
                com.example.mochi_pet.core.wake.WakeCaptureStatus.PAUSED,
                com.example.mochi_pet.core.wake.WakeCaptureStatus.DISABLED,
                com.example.mochi_pet.core.wake.WakeCaptureStatus.ERROR,
            )
        }
        assertEquals(wakeEnabled, app.wakeRuntime.shouldBeEnabled)
        val composer = compose.onNodeWithTag("conversation-composer")
        val height = composer.getUnclippedBoundsInRoot().height
        val input = compose.onNodeWithTag("conversation-input")
        input.performTextInput("Synthetic local draft. ".repeat(20))
        assertEquals(height, composer.getUnclippedBoundsInRoot().height)
        val send = compose.onNodeWithContentDescription(localizeUiText("Send"))
        send.assertIsDisplayed()
        assertTrue(send.getUnclippedBoundsInRoot().width >= 48.dp)
        compose.onNodeWithContentDescription(localizeUiText("Speak")).assertDoesNotExist()
        input.performTextClearance()
        send.assertIsNotEnabled()
        Espresso.closeSoftKeyboard()
        compose.onNode(hasText(localizeUiText("Skills")) and hasClickAction()).performClick()
        compose.onNodeWithText(localizeUiText("Installed")).assertIsSelected()
        compose.onNodeWithText(localizeUiText("Explore")).assertIsDisplayed()
        compose.onNode(hasText(localizeUiText("Tools")) and hasClickAction()).performClick()
        compose.onNodeWithText(localizeUiText("Choose what Mochi may call")).assertIsDisplayed()
        compose.onNode(hasText("Mochi") and hasClickAction()).performClick()
        compose.onNodeWithText(localizeUiText("Chat")).assertIsSelected()
        compose.onNodeWithTag("conversation-input").assertIsDisplayed()
        assertEquals(before, runBlocking(Dispatchers.IO) { app.providerSettingsRepository.loadProfiles() })
    }

    @Test fun savedConnectionsRemainReadableAndSettingsStartsWithSharing() {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val app = compose.activity.application as MochiApplication
        val repo = app.providerSettingsRepository
        val speech = app.speechSettingsRepository
        val speechBefore = runBlocking(Dispatchers.IO) {
            speech.loadProfiles().also { catalog ->
                if (catalog.active.settings.isReady) {
                    val config = speech.loadRuntimeConfig()
                    assertTrue("Stored speech credentials must decrypt", when (config) {
                        is com.example.mochi_pet.core.settings.SpeechRuntimeConfig.System -> true
                        is com.example.mochi_pet.core.settings.SpeechRuntimeConfig.IFlytek ->
                            config.apiKey.isNotBlank() && config.apiSecret.isNotBlank()
                        is com.example.mochi_pet.core.settings.SpeechRuntimeConfig.Azure -> config.apiKey.isNotBlank()
                    })
                }
            }
        }
        val before = runBlocking(Dispatchers.IO) {
            repo.loadProfiles().also { catalog ->
                if (catalog.active?.settings?.isReady == true) {
                    assertTrue("Stored active credential must decrypt", repo.loadRuntimeConfig().apiKey.isNotBlank())
                }
            }
        }
        if (compose.onAllNodesWithText(localizeUiText("Settings")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(localizeUiText("Settings")).performClick()
        }
        compose.onNodeWithTag("settings-part-Share and import").assertIsDisplayed()
        compose.onNodeWithTag("settings-parts")
            .performScrollToNode(hasTestTag("settings-part-AI connections"))
        compose.onNodeWithText(localizeUiText("AI connections")).assertIsDisplayed()
        compose.onNodeWithTag("settings-parts")
            .performScrollToNode(hasTestTag("settings-part-Speech and wake"))
        val editSpeech = hasText(localizeUiText("Edit")) and
            hasAnyAncestor(hasTestTag("speech-profile-${speechBefore.activeId}"))
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(editSpeech and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settings-parts").performScrollToNode(editSpeech)
        compose.onNode(editSpeech).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onNodeWithText(localizeUiText("Edit speech connection")).isDisplayed()
        }
        compose.onNodeWithText(localizeUiText("Edit speech connection")).assertIsDisplayed()
        compose.onNodeWithText(localizeUiText("Cancel")).performClick()
        assertEquals(before, runBlocking(Dispatchers.IO) { repo.loadProfiles() })
        assertEquals(speechBefore, runBlocking(Dispatchers.IO) { speech.loadProfiles() })
    }
}
