package com.example.mochi_pet.feature.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
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
    private val sent = mutableListOf<String>()
    private var started = 0
    private var stopped = 0
    private var cancelled = 0

    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `composer stays one row for long drafts and both send actions clear once`() {
        renderConversation()
        val composer = compose.onNodeWithTag("conversation-composer")
        val input = compose.onNodeWithTag("conversation-input")
        val originalHeight = composer.getUnclippedBoundsInRoot().height
        assertTrue(originalHeight <= 60.dp)
        assertTrue(compose.onNodeWithTag("conversation-header").getUnclippedBoundsInRoot().height <= 36.dp)
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

    @Test fun `voice stop and agent cancellation keep their separate accessible actions`() {
        renderConversation()
        compose.onNodeWithContentDescription("Speak").performClick()
        assertEquals(1, started)
        compose.runOnIdle { voice.value = voice.value.copy(isListening = true, partialTranscript = "Fixture speech") }
        compose.onNodeWithTag("conversation-input").assertIsNotEnabled()
        compose.onNodeWithText("Fixture speech").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop listening").performClick()
        assertEquals(1, stopped)
        compose.runOnIdle {
            voice.value = voice.value.copy(isListening = false, partialTranscript = "")
            state.value = state.value.copy(isSending = true)
        }
        compose.onNodeWithContentDescription("Speak").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cancel").assertIsEnabled().performClick()
        assertEquals(1, cancelled)
        assertTrue(sent.isEmpty())
    }

    @Test fun `unconfigured provider and unavailable microphone cannot dispatch actions`() {
        ready.value = false
        renderConversation()
        compose.onNodeWithTag("conversation-input").performTextInput("Keep this draft")
        compose.onNodeWithTag("conversation-input").performImeAction()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Speak").assertIsNotEnabled()
        assertTrue(sent.isEmpty())
        compose.runOnIdle {
            ready.value = true
            voice.value = voice.value.copy(recognitionAvailable = false)
        }
        compose.onNodeWithContentDescription("Speak").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("Keep this draft"), sent)
    }

    @Test fun `Chinese large text keeps both icon targets reachable on narrow screens`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        renderConversation(fontScale = 1.5f)
        compose.onNodeWithTag("conversation-input").performTextInput("这是一条界面测试消息")
        val mic = compose.onNodeWithContentDescription("说话").assertIsDisplayed().getUnclippedBoundsInRoot()
        val send = compose.onNodeWithContentDescription("发送").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(mic.width >= 48.dp && mic.height >= 48.dp)
        assertTrue(send.width >= 48.dp && send.height >= 48.dp)
        assertTrue(mic.right <= send.left)
        assertTrue(compose.onNodeWithTag("conversation-input").getUnclippedBoundsInRoot().width >= 100.dp)
        captureFixture("conversation-composer", "compact-conversation-zh-large.png")
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
                    ConversationSurface(
                        state.value, ready.value, voice.value, sent::add,
                        { cancelled++ }, { started++ }, { stopped++ }, {}, { _, _ -> }, {},
                    )
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
