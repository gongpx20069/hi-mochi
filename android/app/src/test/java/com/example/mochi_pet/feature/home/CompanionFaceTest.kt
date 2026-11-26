package com.example.mochi_pet.feature.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeKeyword
import com.example.mochi_pet.core.wake.WakeRuntimeState
import com.example.mochi_pet.ui.theme.MochiTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionFaceTest {
    @get:Rule val compose = createComposeRule()
    private val originalLocale = Locale.getDefault()
    private val wake = mutableStateOf(WakeRuntimeState(WakeCaptureStatus.LISTENING, keyword = WakeKeyword.CHINESE))
    private val pipeline = mutableStateOf(ChatPipelineUiState())
    private val focused = mutableStateOf(false)

    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun `Chinese invitation overlaps crown not eyes and keeps only the fullscreen action`() {
        render(Locale.CHINESE)
        compose.onNodeWithText("叫我 老莫老莫").assertIsDisplayed()
        compose.onNodeWithText("与 Mochi 对话").assertDoesNotExist()
        compose.onNodeWithText("查看今天").assertDoesNotExist()
        compose.onNodeWithText("很高兴见到你").assertDoesNotExist()
        checkGeometry()
        saveFixture("companion-native-portrait.png")
        compose.onNodeWithText("全屏显示").performClick()
        compose.onNodeWithTag("companion-fullscreen").assertDoesNotExist()
        compose.onNodeWithText("叫我 老莫老莫").assertIsDisplayed()
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        checkGeometry()
        saveFixture("companion-native-fullscreen.png")
        compose.onNodeWithText("退出全屏").performClick()
        compose.onNodeWithText("全屏显示").assertIsDisplayed()
    }

    @Test fun `invitation uses resolved keyword rather than UI language or manual override`() {
        render()
        compose.onNodeWithText("Say \u201c老莫老莫\u201d").assertIsDisplayed()
        compose.runOnIdle {
            wake.value = wake.value.copy(keyword = WakeKeyword.ENGLISH, keywordOverride = WakeKeyword.CHINESE)
        }
        compose.onNodeWithText("Say \u201cHi Mochi\u201d").assertIsDisplayed()
        compose.onNodeWithText("Say \u201c老莫老莫\u201d").assertDoesNotExist()
    }

    @Test fun `wake bubble disappears on detection and all active stages retain original pipeline feedback`() {
        render(Locale.CHINESE)
        saveFixture("companion-stage-idle.png")
        val idleCharacter = compose.onNodeWithTag("companion-character").fetchSemanticsNode().boundsInRoot
        for (status in listOf(WakeCaptureStatus.PAUSED, WakeCaptureStatus.DISABLED, WakeCaptureStatus.STARTING, WakeCaptureStatus.ERROR)) {
            compose.runOnIdle { wake.value = wake.value.copy(status = status, errorMessage = "Synthetic failure") }
            compose.onNodeWithTag("companion-bubble").assertDoesNotExist()
            compose.onNodeWithText("叫我 老莫老莫").assertDoesNotExist()
            compose.onNodeWithText("Synthetic failure").assertDoesNotExist()
            val character = compose.onNodeWithTag("companion-character").fetchSemanticsNode().boundsInRoot
            assertTrue("Hiding the bubble must not move the character", kotlin.math.abs(character.center.y - idleCharacter.center.y) < 2f)
        }
        compose.runOnIdle { wake.value = wake.value.copy(status = WakeCaptureStatus.LISTENING, errorMessage = null) }
        for (stage in ChatPipelineStage.entries.filter { it != ChatPipelineStage.IDLE }) {
            compose.runOnIdle { pipeline.value = ChatPipelineUiState(stage) }
            compose.onNodeWithText(localizeUiText(stage.displayText())).assertIsDisplayed()
            compose.onNodeWithTag("chat-pipeline-track").assertIsDisplayed()
            compose.onNodeWithTag("companion-bubble").assertDoesNotExist()
            compose.onNodeWithTag("companion-fullscreen").assertIsDisplayed()
            saveFixture("companion-stage-${stage.name.lowercase()}.png")
        }
        compose.runOnIdle { pipeline.value = ChatPipelineUiState(ChatPipelineStage.LISTENING, "Synthetic partial transcript") }
        compose.onNodeWithText(localizeUiText("Listening")).assertIsDisplayed()
        compose.onNodeWithText("Synthetic partial transcript").assertIsDisplayed()
        compose.runOnIdle { pipeline.value = ChatPipelineUiState() }
        compose.onNodeWithText("叫我 老莫老莫").assertIsDisplayed()
        compose.onNodeWithTag("chat-pipeline").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `narrow screens wrap enlarged text without obscuring eyes or hiding fullscreen`() {
        render(fontScale = 2f)
        checkGeometry()
        compose.onNodeWithText("Full screen").assertIsDisplayed()
        saveFixture("companion-native-large-font.png")
    }

    @Test
    @Config(qualifiers = "w568dp-h320dp-land")
    fun `landscape keeps bubble and entry reachable with large text`() {
        render(fontScale = 2f)
        compose.onNodeWithTag("companion-bubble-text").performScrollTo().assertIsDisplayed()
        checkTextLayout()
        compose.onNodeWithText("Full screen").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("companion-bubble-text").performScrollTo().assertIsDisplayed()
        checkTextLayout()
        saveFixture("companion-native-landscape.png")
        compose.runOnIdle { pipeline.value = ChatPipelineUiState(ChatPipelineStage.SPEAKING, "Synthetic reply detail") }
        compose.onNodeWithTag("chat-pipeline-track").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("companion-bubble").assertDoesNotExist()
        compose.onNodeWithTag("companion-character").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exit full screen").performScrollTo().assertIsDisplayed()
    }

    private fun render(locale: Locale = Locale.ENGLISH, fontScale: Float = 1f) {
        Locale.setDefault(locale)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MochiTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1C1B1E))) {
                        if (focused.value) {
                            CompanionFocusFrame(pipeline.value, { focused.value = false }) {
                                MochiFace(pipeline.value, wake.value, true, {})
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                ChatPipelineIndicator(pipeline.value)
                                Box(Modifier.weight(1f)) {
                                    MochiFace(pipeline.value, wake.value, false, { focused.value = true })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkGeometry() {
        val face = compose.onNodeWithTag("companion-face").fetchSemanticsNode().boundsInRoot
        val bubble = compose.onNodeWithTag("companion-bubble").fetchSemanticsNode().boundsInRoot
        val character = compose.onNodeWithTag("companion-character").fetchSemanticsNode().boundsInRoot
        assertTrue("Bubble must overlap the crown", bubble.bottom > character.top + character.height * 31f / 240f)
        assertTrue("Bubble must not obscure the eyes", bubble.bottom < character.top + character.height * 80f / 240f)
        for (bounds in listOf(bubble, character)) {
            assertTrue("Content must fit", bounds.left >= face.left && bounds.right <= face.right &&
                bounds.top >= face.top && bounds.bottom <= face.bottom)
        }
        checkTextLayout()
    }

    private fun checkTextLayout() {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("companion-bubble-text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The complete phrase must remain readable", layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
    }

    private fun saveFixture(name: String) {
        val node = compose.onNodeWithTag("companion-face").fetchSemanticsNode()
        val view = requireNotNull(node.root as? ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/$name")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
