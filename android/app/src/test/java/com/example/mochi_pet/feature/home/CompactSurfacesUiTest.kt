package com.example.mochi_pet.feature.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.assertIsOn
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.MochiNavigationIntent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_ui.MochiTheme
import java.io.File
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
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactSurfacesUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    private val state = mutableStateOf(ConversationUiState())
    private val voice = mutableStateOf(VoiceRuntimeState(recognitionAvailable = true))
    private val ready = mutableStateOf(true)
    private val muted = mutableStateOf(false)
    private val sent = mutableListOf<String>()
    private var started = 0
    private var stopped = 0
    private var cancelled = 0
    private var dictationCancelled = 0

    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `composer stays one row for long drafts and both send actions clear once`() {
        renderConversation()
        val composer = compose.onNodeWithTag("conversation-composer")
        val input = compose.onNodeWithTag("conversation-input")
        val originalHeight = composer.getUnclippedBoundsInRoot().height
        assertTrue(originalHeight <= 60.dp)
        compose.onNodeWithTag("conversation-header").assertDoesNotExist()
        compose.onNodeWithText("Type or hold to dictate").assertIsDisplayed()
        val longDraft = "A long message that stays on one line. ".repeat(20)
        input.performTextInput(longDraft)
        assertEquals(originalHeight, composer.getUnclippedBoundsInRoot().height)
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals("", input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        input.performTextInput("Keyboard message")
        input.performImeAction()
        input.performImeAction()
        assertEquals(listOf(longDraft, "Keyboard message"), sent)
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        captureFixture("conversation-composer", "compact-conversation.png")
    }

    @Test fun `central entry reveals history with a moving composer without recreating the text field`() {
        state.value = state.value.copy(quickEntry = true, draft = "Keep draft", messages = listOf(
            ConversationMessage("previous", ConversationRole.ASSISTANT, "Previous conversation", java.time.Instant.EPOCH),
        ))
        renderConversation()
        compose.onNodeWithText("Previous conversation").assertDoesNotExist()
        val composer = compose.onNodeWithTag("conversation-composer")
        val layout = compose.onNodeWithTag("conversation-layout")
        val before = composer.getUnclippedBoundsInRoot()
        val logo = compose.onNodeWithTag("conversation-entry-logo").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(logo.bottom < before.top)
        assertTrue(before.bottom < layout.getUnclippedBoundsInRoot().bottom - 100.dp)
        val inputId = compose.onNodeWithTag("conversation-input").fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        val showHistory = compose.onNodeWithTag("dictation-input").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Show conversation history" }
        compose.runOnUiThread { assertTrue(showHistory.action()) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(140)
        compose.waitForIdle()
        val middle = composer.getUnclippedBoundsInRoot().top
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val after = composer.getUnclippedBoundsInRoot().top
        assertTrue("Composer positions: ${before.top}, $middle, $after; quick=${state.value.quickEntry}",
            middle > before.top && middle < after)
        compose.onNodeWithText("Previous conversation").assertIsDisplayed()
        compose.onNodeWithTag("conversation-history-hint").assertDoesNotExist()
        compose.onNodeWithTag("conversation-entry-logo").assertDoesNotExist()
        assertEquals(inputId, compose.onNodeWithTag("conversation-input").fetchSemanticsNode().id)
        assertEquals("Keep draft", state.value.draft)
        assertTrue(sent.isEmpty())
    }

    @Test fun `central entry sends explicitly and long press still only transcribes`() {
        state.value = state.value.copy(quickEntry = true, draft = "Draft")
        renderConversation()
        compose.onNodeWithTag("dictation-input").performTouchInput { longClick() }
        assertEquals(1, started)
        assertEquals(1, stopped)
        assertTrue(state.value.quickEntry)
        assertTrue(sent.isEmpty())
        compose.onNodeWithContentDescription("Cancel dictation").performClick()
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("Draft"), sent)
        assertEquals(false, state.value.quickEntry)
        compose.runOnIdle { state.value = state.value.copy(quickEntry = true) }
        compose.onNodeWithText("Type, hold to talk, swipe down for history").assertIsDisplayed()
        compose.onNodeWithTag("conversation-history-hint").assertDoesNotExist()
        compose.onNodeWithTag("conversation-layout").performTouchInput { swipeDown() }
        assertEquals(false, state.value.quickEntry)
        compose.onNodeWithText("Type or hold to dictate").assertIsDisplayed()
        compose.onNodeWithText("Type, hold to talk, swipe down for history").assertDoesNotExist()
        assertEquals(listOf("Draft"), sent)
    }

    @Test fun `moving before a long press does not start recording`() {
        state.value = state.value.copy(quickEntry = true)
        renderConversation()
        compose.onNodeWithTag("dictation-input").performTouchInput {
            down(center)
            advanceEventTime(100)
            moveTo(center + Offset(0f, 20f))
            advanceEventTime(700)
            up()
        }
        assertEquals(0, started)
        assertTrue(sent.isEmpty())
    }

    @Test fun `connection block separates connecting from management at large font scale`() {
        val connected = mutableStateOf(false)
        var connects = 0
        var disconnects = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { MochiTheme {
                ToolConnectionRow(
                    hasConnection = connected.value, connectLabel = "Connect Feishu",
                    detail = if (connected.value) "Connected" else "Authorization required",
                    enabled = true, tag = "test-connection",
                    onConnect = { connects++; connected.value = true },
                ) { close ->
                    CapabilityAction({ close(); disconnects++; connected.value = false }) { Text("Disconnect") }
                }
            } }
        }
        compose.onNodeWithText("Disconnect").assertDoesNotExist()
        compose.onNodeWithText("Connect Feishu").performClick()
        assertEquals(1, connects)
        compose.onNodeWithTag("test-connection").performClick()
        compose.onNodeWithText("Disconnect").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(0, disconnects)
        compose.onNodeWithTag("test-connection").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals(1, disconnects)
        compose.onNodeWithText("Connect Feishu").assertIsDisplayed()
    }

    @Test fun `holding an existing draft records and release finalizes without sending`() {
        renderConversation()
        compose.onNodeWithTag("conversation-input").performTextInput("Keep draft")
        compose.onNodeWithTag("dictation-input").performTouchInput { longClick() }
        assertEquals(1, started)
        assertEquals(1, stopped)
        assertEquals("Keep draft", state.value.draft)
        compose.onNodeWithTag("conversation-input").assertIsNotEnabled()
        compose.onNodeWithText("Transcribing...").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speak").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel dictation").performClick()
        assertEquals(1, dictationCancelled)
        compose.runOnIdle {
            state.value = state.value.copy(isSending = true)
        }
        compose.onNodeWithContentDescription("Cancel").assertIsEnabled().performClick()
        assertEquals(1, cancelled)
        assertTrue(sent.isEmpty())
    }

    @Test fun `moving outside the held field cancels and preserves the draft`() {
        state.value = state.value.copy(draft = "Existing text")
        renderConversation()
        compose.onNodeWithTag("dictation-input").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(-30f, center.y))
            up()
        }
        assertEquals(1, started)
        assertEquals(1, dictationCancelled)
        assertEquals(0, stopped)
        assertEquals("Existing text", state.value.draft)
        assertTrue(sent.isEmpty())
    }

    @Test fun `unconfigured provider blocks sending without losing the editable draft`() {
        ready.value = false
        renderConversation()
        compose.onNodeWithTag("conversation-input").performTextInput("Keep this draft")
        compose.onNodeWithTag("conversation-input").performImeAction()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        assertTrue(sent.isEmpty())
        compose.runOnIdle {
            ready.value = true
        }
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("Keep this draft"), sent)
    }

    @Test fun `Chinese large text keeps hint and send target reachable on narrow screens`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        renderConversation(fontScale = 1.5f)
        compose.onNodeWithText("点击输入，长按说话").assertIsDisplayed()
        compose.onNodeWithTag("conversation-input").performTextInput("这是一条界面测试消息")
        val send = compose.onNodeWithContentDescription("发送").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(send.width >= 48.dp && send.height >= 48.dp)
        assertTrue(compose.onNodeWithTag("conversation-input").getUnclippedBoundsInRoot().width >= 100.dp)
        captureFixture("conversation-composer", "compact-conversation-zh-large.png")
    }

    @Test fun `chat mute is a separate accessible action and never sends the draft`() {
        renderConversation()
        compose.onNodeWithTag("conversation-input").performTextInput("Keep draft")
        val toggle = compose.onNodeWithContentDescription("Mute chat replies")
        assertTrue(toggle.getUnclippedBoundsInRoot().width >= 48.dp)
        val speaker = toggle.getUnclippedBoundsInRoot()
        val modes = compose.onNodeWithTag("mochi-modes").getUnclippedBoundsInRoot()
        assertTrue(speaker.top >= modes.top && speaker.bottom <= modes.bottom)
        assertTrue(speaker.bottom < compose.onNodeWithTag("conversation-composer").getUnclippedBoundsInRoot().top)
        toggle.performClick()
        assertTrue(muted.value)
        compose.onNodeWithContentDescription("Unmute chat replies").performClick()
        assertEquals(false, muted.value)
        assertEquals("Keep draft", state.value.draft)
        assertTrue(sent.isEmpty())
    }

    @Test fun `compact Skill tabs retain selection and explore update actions`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        var popular = 0
        var updates = 0
        renderSkills(onLoadPopular = { popular++ }, onCheckUpdates = { updates++ })
        val installed = compose.onNodeWithText(localizeUiText("Installed"))
        installed.assertIsSelected()
        assertTrue(installed.getUnclippedBoundsInRoot().height >= 48.dp)
        assertTrue(installed.getUnclippedBoundsInRoot().width < 140.dp)
        val updateBounds = compose.onNodeWithText(localizeUiText("Check for updates")).getUnclippedBoundsInRoot()
        val tabBounds = installed.getUnclippedBoundsInRoot()
        assertTrue(updateBounds.top < tabBounds.bottom && updateBounds.bottom > tabBounds.top)
        compose.onNodeWithText(localizeUiText("Check for updates")).performClick()
        assertEquals(1, updates)
        compose.onNodeWithText(localizeUiText("Explore")).performClick().assertIsSelected()
        compose.onNodeWithText(localizeUiText("Search skills.sh")).assertIsDisplayed()
        assertEquals(1, popular)
        installed.performClick().assertIsSelected()
        compose.onNodeWithText(localizeUiText("Check for updates")).assertIsDisplayed()
    }

    @Test fun `compact controls preserve full touch targets and disabled behavior`() {
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var actions = 0
        compose.setContent { MochiTheme { Column {
            CapabilitySwitch(checked.value, { checked.value = it }, Modifier.testTag("switch"), enabled.value)
            CapabilityAction({ actions++ }, Modifier.testTag("action"), enabled.value) { Text("View") }
        } } }
        val toggle = compose.onNodeWithTag("switch")
        val action = compose.onNodeWithTag("action")
        listOf(toggle, action).forEach {
            val bounds = it.getUnclippedBoundsInRoot()
            assertTrue(bounds.width >= 48.dp && bounds.height >= 48.dp)
            it.performTouchInput { click(Offset(2f, center.y)) }
        }
        toggle.assertIsOn()
        assertEquals(1, actions)
        compose.runOnIdle { enabled.value = false }
        toggle.assertIsNotEnabled().performTouchInput { click() }
        action.assertIsNotEnabled().performTouchInput { click() }
        toggle.assertIsOn()
        assertEquals(1, actions)
    }

    @Test fun `mode group wraps at large text while Chat mute stays separate and Companion has no mute action`() {
        val companion = mutableStateOf(false)
        compose.setContent { MochiTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MochiModeSwitch(
                    companion.value, { companion.value = it == MochiNavigationIntent.ShowFace },
                    muted.value, true, { muted.value = it },
                )
            }
        } }
        val chat = compose.onNodeWithText("Chat").assertIsDisplayed().assertIsSelected()
        val companionTab = compose.onNodeWithText("Companion").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("mochi-modes").getUnclippedBoundsInRoot()
        val speaker = compose.onNodeWithContentDescription("Mute chat replies").assertIsDisplayed()
        listOf(chat, companionTab).forEach {
            val tab = it.getUnclippedBoundsInRoot()
            assertTrue(tab.height >= 48.dp && tab.width >= 48.dp)
            assertTrue(tab.right <= speaker.getUnclippedBoundsInRoot().left)
        }
        speaker.performClick()
        companionTab.performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Unmute chat replies").assertDoesNotExist()
        assertEquals(bounds, compose.onNodeWithTag("mochi-modes").getUnclippedBoundsInRoot())
        chat.performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Unmute chat replies").assertIsDisplayed()
        assertTrue(muted.value)
    }

    @Test fun `global header keeps the same compact identity across destinations`() {
        val surface = mutableStateOf<MochiSurface>(MochiSurface.Conversation)
        compose.setContent { MochiTheme {
            MochiTopBar(surface.value, {}, {})
        } }
        val header = compose.onNodeWithTag("mochi-header")
        val bounds = header.getUnclippedBoundsInRoot()
        listOf(MochiSurface.Face, MochiSurface.Today, MochiSurface.Skills, MochiSurface.Tools).forEach {
            compose.runOnIdle { surface.value = it }
            compose.onNodeWithText("Mochi").assertIsDisplayed()
            assertEquals(bounds, header.getUnclippedBoundsInRoot())
        }
        compose.onNodeWithText("Chat").assertDoesNotExist()
        compose.onNodeWithText("Companion").assertDoesNotExist()
    }

    @Test fun `long Skill action wraps below tabs at large font without hiding controls`() {
        var updates = 0
        renderSkills(fontScale = 1.5f, onCheckUpdates = { updates++ })
        val installed = compose.onNodeWithText("Installed").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithText("Explore").assertIsDisplayed()
        val update = compose.onNodeWithText("Check for updates").assertIsDisplayed()
        assertTrue(update.getUnclippedBoundsInRoot().top >= installed.bottom)
        update.performClick()
        assertEquals(1, updates)
    }

    private fun renderSkills(
        fontScale: Float = 1f,
        onLoadPopular: () -> Unit = {},
        onCheckUpdates: () -> Unit = {},
    ) {
        compose.setContent {
            MochiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    SkillsSurface(
                        state = SkillsUiState(isLoading = false),
                        onSearch = {}, onLoadPopular = onLoadPopular, onPreview = {}, onClearPreview = {},
                        onInstall = {}, onEdit = { _, _ -> }, onSetEnabled = { _, _ -> }, onDelete = {},
                        onCheckUpdates = onCheckUpdates, onApplyUpdate = {},
                    )
                }
            }
        }
    }

    private fun renderConversation(fontScale: Float = 1f) {
        compose.setContent {
            MochiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column {
                        MochiModeSwitch(false, {}, muted.value, true, { muted.value = it })
                        ConversationSurface(
                            state = state.value, providerReady = ready.value, voiceState = voice.value, onSend = sent::add,
                            onShowHistory = { state.value = state.value.copy(quickEntry = false) },
                            onCancel = { cancelled++ },
                            onDraftChange = { state.value = state.value.copy(draft = it) },
                            onStartDictation = { started++; state.value = state.value.copy(dictation = DictationPhase.RECORDING) },
                            onFinishDictation = { stopped++; state.value = state.value.copy(dictation = DictationPhase.TRANSCRIBING) },
                            onCancelDictation = { dictationCancelled++; state.value = state.value.copy(dictation = DictationPhase.IDLE) },
                            onOpenSpeechSettings = {}, onCardAction = { _, _ -> }, onRemoteTask = {},
                        )
                    }
                }
            }
        }
    }

    private fun captureFixture(tag: String, name: String) {
        val root = compose.onNodeWithTag(tag).fetchSemanticsNode().root as ViewRootForTest
        val bitmap = Bitmap.createBitmap(root.view.width, root.view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
