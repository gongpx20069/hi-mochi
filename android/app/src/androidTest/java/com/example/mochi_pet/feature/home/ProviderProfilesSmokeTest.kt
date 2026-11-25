package com.example.mochi_pet.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_ui.MochiTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
    private var originalOrientation: Int? = null

    private fun usePortraitLayout() {
        compose.runOnUiThread {
            val activity = compose.activity
            originalOrientation = activity.requestedOrientation
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {
            val activity = compose.activity
            activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                activity.window.decorView.height > activity.window.decorView.width
        }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.waitForIdle()
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
            }
        }
        compose.waitForIdle()
    }

    @After fun restoreTestWindowState() {
        compose.runOnUiThread {
            compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            originalOrientation?.let { compose.activity.requestedOrientation = it }
        }
    }

    @Test fun holdGestureFinalizesAndCancelsWithoutRecordingOrSending() {
        val state = mutableStateOf(ConversationUiState(draft = "Existing draft", quickEntry = true))
        var started = 0
        var finished = 0
        var cancelled = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MochiTheme {
                    ConversationSurface(
                        state = state.value, providerReady = true, voiceState = VoiceRuntimeState(),
                        onShowHistory = { state.value = state.value.copy(quickEntry = false) },
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
        assertTrue(state.value.quickEntry)
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
        hideKeyboard()
        compose.onNodeWithTag("conversation-layout").performTouchInput { swipeDown() }
        assertEquals(false, state.value.quickEntry)
    }

    @Test fun homeBrowserShortcutReturnsToBothModesWithoutChangingTheDraft() {
        compose.onNode(hasText("Mochi") and hasClickAction()).performClick()
        compose.onNode(hasText(localizeUiText("Chat")) and hasClickAction()).performClick()
        compose.onNodeWithTag("conversation-input").performTextInput("Browser shortcut draft")
        hideKeyboard()
        compose.onNodeWithTag("mochi-browse").performClick()
        compose.onNodeWithTag("browser-search").assertIsDisplayed()
        compose.onNodeWithTag("browser-management-close").performClick()
        compose.onNodeWithTag("conversation-input").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("conversation-input").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.contains("Browser shortcut draft"))
        compose.onNodeWithTag("conversation-input").performTextClearance()
        hideKeyboard()
        compose.onNode(hasText(localizeUiText("Companion")) and hasClickAction()).performClick()
        compose.onNodeWithTag("mochi-browse").performClick()
        compose.onNodeWithTag("browser-management").assertIsDisplayed()
        compose.onNodeWithTag("browser-management-close").performClick()
        compose.onNode(hasText(localizeUiText("Companion")) and hasClickAction()).assertIsSelected()
    }

    @Test fun compactComposerAndCapabilityPagesRemainReachableWithoutSending() {
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val app = compose.activity.application as MochiApplication
        val before = runBlocking(Dispatchers.IO) { app.providerSettingsRepository.loadProfiles() }
        val wakeEnabled = app.wakeRuntime.shouldBeEnabled
        if (compose.onAllNodesWithText(localizeUiText("Settings")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(localizeUiText("Settings")).performClick()
        }
        compose.onNode(hasText("Mochi") and hasClickAction()).performClick()
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
        val muted = runBlocking(Dispatchers.IO) { app.agentSettingsRepository.load().chatMuted }
        val speaker = compose.onNodeWithContentDescription(localizeUiText(
            if (muted) "Unmute chat replies" else "Mute chat replies",
        )).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(speaker.bottom <= compose.onNodeWithTag("mochi-modes").getUnclippedBoundsInRoot().bottom)
        assertTrue(speaker.bottom < composer.getUnclippedBoundsInRoot().top)
        val input = compose.onNodeWithTag("conversation-input")
        input.performTextInput("Synthetic local draft. ".repeat(20))
        assertEquals(height, composer.getUnclippedBoundsInRoot().height)
        val send = compose.onNodeWithContentDescription(localizeUiText("Send"))
        send.assertIsDisplayed()
        assertTrue(send.getUnclippedBoundsInRoot().width >= 48.dp)
        compose.onNodeWithContentDescription(localizeUiText("Speak")).assertDoesNotExist()
        input.performTextClearance()
        send.assertIsNotEnabled()
        hideKeyboard()
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

    @Test fun agendaSwipesReplaceDifferentContentsWithoutMovingTheCalendar() {
        usePortraitLayout()
        val today = java.time.LocalDate.of(2026, 11, 30)
        val selected = mutableStateOf(today)
        val base = java.time.Instant.parse("2026-11-30T08:00:00Z")
        val event = com.example.mochi_pet.core.model.CalendarEvent("fixture", "Synthetic agenda event", null, base,
            null, false, java.time.ZoneOffset.UTC, null, null, null, base, base)
        val actions = PlannerUiActions(
            saveUser = { _, _, _, _ -> null }, completeUser = {}, deleteUser = { _, _, _ -> },
            saveTask = { _, _ -> }, results = { _, _ -> emptyList() },
            result = { error("Unexpected result request") }, showResults = {}, changes = kotlinx.coroutines.flow.emptyFlow(),
        )
        compose.runOnUiThread { compose.activity.setContent { MochiTheme {
            PlannerAgenda(selected.value, today, false, PlannerSurfaceState(date = selected.value,
                events = if (selected.value == today) listOf(event) else emptyList()),
                { selected.value = it }, {}, {}, actions, {}, {}, {}, { _, _ -> })
        } } }
        val filters = compose.onNodeWithTag("agenda-filters")
        val before = filters.getUnclippedBoundsInRoot()
        compose.onNodeWithText("Synthetic agenda event").assertIsDisplayed()
        compose.onNodeWithTag("planner-agenda").performTouchInput { swipeLeft() }
        assertEquals(today.plusDays(1), selected.value)
        compose.onNodeWithText(localizeUiText("No arrangements for this day")).assertIsDisplayed()
        assertEquals(before, filters.getUnclippedBoundsInRoot())
        compose.onNodeWithTag("planner-agenda").performTouchInput { swipeRight() }
        assertEquals(today, selected.value)
        compose.onNodeWithText("Synthetic agenda event").assertIsDisplayed()
        assertEquals(before, filters.getUnclippedBoundsInRoot())
    }

    @Test fun externalReturnCentersIdleChatButPauseAndInternalNavigationDoNot() {
        usePortraitLayout()
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        if (compose.onAllNodesWithText(localizeUiText("Settings")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(localizeUiText("Settings")).performClick()
        }
        compose.onNode(hasText("Mochi") and hasClickAction()).performClick()
        compose.onNode(hasText(localizeUiText("Chat")) and hasClickAction()).performClick()
        fun historyAvailable() = compose.onAllNodes(hasTestTag("dictation-input")).fetchSemanticsNodes()
            .singleOrNull()?.config?.get(SemanticsActions.CustomActions)
            ?.any { it.label == localizeUiText("Show conversation history") } == true
        val model = compose.runOnUiThread {
            androidx.lifecycle.ViewModelProvider(compose.activity)[MochiHomeViewModel::class.java]
        }
        fun awaitCentralEntry() {
            try {
                compose.waitUntil(10_000) { historyAvailable() }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                val conversation = model.conversationState.value
                val updateDialog = compose.onAllNodesWithText(localizeUiText("Download APK")).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(localizeUiText("Open release")).fetchSemanticsNodes().isNotEmpty()
                throw AssertionError(
                    "Central entry unavailable: surface=${model.surface.value.javaClass.simpleName}, " +
                        "quick=${conversation.quickEntry}, sending=${conversation.isSending}, dictation=${conversation.dictation}, " +
                        "pipeline=${model.pipelineState.value.stage}, listening=${model.voiceState.value.isListening}, " +
                        "updateDialog=$updateDialog",
                    error,
                )
            }
        }
        assertEquals(false, historyAvailable())
        val bottom = compose.onNodeWithTag("conversation-composer").getUnclippedBoundsInRoot().top
        val initialLayout = compose.onNodeWithTag("conversation-layout").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("conversation-input").performTextInput("Synthetic return draft")
        hideKeyboard()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertEquals(false, historyAvailable())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        awaitCentralEntry()
        val entry = compose.onNodeWithTag("conversation-composer").getUnclippedBoundsInRoot().top
        val entryLayout = compose.onNodeWithTag("conversation-layout").getUnclippedBoundsInRoot()
        assertTrue("Composer top: normal=$bottom, entry=$entry; layouts: normal=$initialLayout, entry=$entryLayout",
            entry < bottom)
        compose.activityRule.scenario.recreate()
        assertTrue(historyAvailable())
        compose.onNodeWithTag("conversation-layout").performTouchInput { swipeDown() }
        assertEquals(false, historyAvailable())
        assertEquals("Synthetic return draft", compose.onNodeWithTag("conversation-input").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.activityRule.scenario.recreate()
        assertEquals(false, historyAvailable())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        awaitCentralEntry()
        compose.onNodeWithText(localizeUiText("Tasks")).performClick()
        compose.onNodeWithText(localizeUiText("Close")).performClick()
        assertEquals(false, historyAvailable())
        compose.onNodeWithTag("conversation-input").performTextClearance()
        hideKeyboard()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithText(localizeUiText("Type, hold to talk, swipe down for history")).assertIsDisplayed()
        compose.onNodeWithTag("conversation-entry-logo").assertIsDisplayed()
        compose.onNodeWithTag("conversation-history-hint").assertDoesNotExist()
        compose.onNodeWithTag("conversation-layout").performTouchInput { swipeDown() }
        compose.onNodeWithText(localizeUiText("Type or hold to dictate")).assertIsDisplayed()
    }

    @Test fun chatMuteSurvivesActivityRecreationWithoutChangingOtherPreferences() {
        val app = compose.activity.application as MochiApplication
        val repository = app.agentSettingsRepository
        val before = runBlocking(Dispatchers.IO) { repository.load() }
        fun openChat() {
            if (compose.onAllNodesWithText(localizeUiText("Settings")).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText(localizeUiText("Settings")).performClick()
            }
            compose.onNode(hasText("Mochi") and hasClickAction()).performClick()
            compose.onNode(hasText(localizeUiText("Chat")) and hasClickAction()).performClick()
        }
        try {
            openChat()
            val label = localizeUiText(if (before.chatMuted) "Unmute chat replies" else "Mute chat replies")
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(label).performClick()
            compose.waitUntil(10_000) {
                runBlocking(Dispatchers.IO) { repository.load().chatMuted != before.chatMuted }
            }
            compose.activityRule.scenario.recreate()
            openChat()
            val restoredLabel = localizeUiText(if (before.chatMuted) "Mute chat replies" else "Unmute chat replies")
            compose.waitUntil(10_000) {
                compose.onNodeWithContentDescription(restoredLabel).isDisplayed()
            }
            assertEquals(before.copy(chatMuted = !before.chatMuted, companionMode = false), runBlocking(Dispatchers.IO) { repository.load() })
        } finally {
            runBlocking(Dispatchers.IO) {
                repository.setChatMuted(before.chatMuted)
                repository.setCompanionMode(before.companionMode)
            }
            compose.activityRule.scenario.recreate()
        }
    }
}
