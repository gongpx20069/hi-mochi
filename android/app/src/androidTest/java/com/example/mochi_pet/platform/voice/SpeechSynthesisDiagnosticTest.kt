package com.example.mochi_pet.platform.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.settings.AppLanguage
import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechSynthesisDiagnosticTest {
    @Test
    fun synthesizeWithSavedProvider(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(
            "Requires explicit opt-in: calls the saved provider and consumes synthesis quota",
            InstrumentationRegistry.getArguments().getString("mochiSpeechDiagnostic") == "true",
        )
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MochiApplication
        val config = application.speechSettingsRepository.loadSynthesisConfig()
        assertTrue("Cloud synthesis must be enabled", config != SpeechRuntimeConfig.System)
        val provider = config.synthesisProviderName()
        val extended = InstrumentationRegistry.getArguments()
            .getString("mochiSpeechDiagnosticExtended") == "true"
        val testText = if (extended) {
            "\u4f60\u597d\uff0c\u8fd9\u662f\u4e00\u6bb5\u8bed\u97f3\u5408\u6210\u6d4b\u8bd5\u6587\u672c\u3002".repeat(8)
        } else {
            "\u4f60\u597d\u3002"
        }
        Log.i("MochiSpeech", "synthesis_diagnostic_started provider=$provider extended=$extended")
        val audio = try {
            CloudSpeechSynthesizer().synthesize(
                config,
                testText,
                AppLanguage.resolveContentLocale(),
            )
        } catch (error: SpeechSynthesisException) {
            val diagnostic = "synthesis_diagnostic_failed provider=$provider ${error.safeDiagnostic()}"
            Log.w("MochiSpeech", diagnostic)
            throw AssertionError(diagnostic)
        } catch (_: IOException) {
            throw AssertionError("synthesis_diagnostic_failed provider=$provider failure=NETWORK")
        }
        assertTrue("Expected nonempty PCM16 audio", audio.isNotEmpty() && audio.size % 2 == 0)
        Log.i(
            "MochiSpeech",
            "synthesis_diagnostic_completed provider=$provider audioBytes=${audio.size}",
        )
        if (
            InstrumentationRegistry.getArguments()
                .getString("mochiSpeechDiagnosticPlayback") == "true"
        ) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val focus = AndroidAudioFocusCoordinator(application)
            instrumentation.runOnMainSync {
                assertTrue("Speech audio focus unavailable", focus.requestSpeechFocus {})
            }
            try {
                AndroidPcmSpeechPlayer().play(audio)
                Log.i("MochiSpeech", "synthesis_diagnostic_playback_completed usage=media")
            } finally {
                instrumentation.runOnMainSync { focus.abandon() }
            }
        }
    }
}
