package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.voice.IFLYTEK_BASIC_VOICES
import com.example.mochi_pet.core.voice.SpeechVoice
import com.example.mochi_pet.core.voice.VoiceRuntimeState
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
class SpeechVoicePickerTest {
    @get:Rule
    val compose = createComposeRule()
    private val originalLocale = Locale.getDefault()

    @Before
    fun setLocale() { Locale.setDefault(Locale.ENGLISH) }

    @After
    fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test
    fun `unsaved connection disables preview and preserves unknown custom ID until confirmed`() {
        val selected = mutableStateOf("existing_custom_voice")
        val changes = mutableListOf<String>()
        var previews = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                    SpeechVoicePicker(
                        SpeechProvider.IFLYTEK, selected.value, SpeechVoiceUiState(),
                        VoiceRuntimeState(), false,
                        onChoose = { changes += it; selected.value = it },
                        onLoad = {}, onPreview = { previews += 1 }, onStop = {},
                    )
                }
            }
        }
        compose.onNodeWithText("existing_custom_voice").assertExists()
        compose.onNodeWithText("Preview voice").assertIsNotEnabled()
        compose.onNodeWithText("Voice").performClick()
        compose.onNodeWithText("Custom voice ID").performScrollTo().performClick()
        compose.onNodeWithText("Synthesis voice ID (optional)").performScrollTo()
            .performTextReplacement("x4_yezi")
        assertTrue(changes.isEmpty())
        compose.onNodeWithText("Confirm selection").performScrollTo().performClick()
        assertEquals(listOf("x4_yezi"), changes)
        assertEquals(0, previews)
    }

    @Test
    fun `language and localized name filtering leaves other language voices discoverable`() {
        val voices = IFLYTEK_BASIC_VOICES + SpeechVoice("en-US-JennyNeural", "Jenny", "en-US")
        assertEquals(listOf("x4_yezi"), filterSpeechVoices(voices, "叶子", false, Locale.CHINESE).map { it.id })
        assertEquals(listOf("en-US-JennyNeural"), filterSpeechVoices(voices, "", false, Locale.ENGLISH).map { it.id })
        assertEquals(6, filterSpeechVoices(voices, "", true, Locale.ENGLISH).size)
    }
}
