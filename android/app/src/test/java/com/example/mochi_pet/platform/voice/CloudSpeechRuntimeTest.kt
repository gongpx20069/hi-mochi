package com.example.mochi_pet.platform.voice

import android.os.Looper
import android.media.AudioAttributes
import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import com.example.mochi_pet.core.settings.SpeechSettingsInput
import com.example.mochi_pet.core.settings.SpeechSettingsRepository
import com.example.mochi_pet.core.settings.SpeechSettingsSummary
import com.example.mochi_pet.core.voice.SpeechPlaybackResult
import com.example.mochi_pet.core.voice.SpeechPurpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudSpeechRuntimeTest {
    @Test
    fun `speech playback follows media volume rather than hidden assistant stream`() {
        val attributes = speechPlaybackAudioAttributes()
        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, attributes.contentType)
    }

    private var runtime: AndroidVoiceRuntime? = null
    private var settingsReads = 0

    @Test
    fun `preview overrides only invocation voice and bypasses reply synthesis opt out`() {
        val configs = mutableListOf<SpeechRuntimeConfig>()
        val voice = runtime(
            synthesisEnabled = false,
            synthesizer = SpeechSynthesizer { config, _, _ ->
                configs += config
                byteArrayOf(0, 0)
            },
        )
        voice.speak("test", SpeechPurpose.PREVIEW, previewVoiceId = "x4_yezi")
        idle()
        val previewConfig = configs.single() as SpeechRuntimeConfig.IFlytek
        assertEquals("x4_yezi", previewConfig.voice)
        assertEquals("test-key", previewConfig.apiKey)
        voice.speak("normal reply")
        idle()
        assertEquals(1, configs.size)
    }

    @After
    fun tearDown() {
        runtime?.close()
        idle()
    }

    @Test
    fun `cloud completion waits until playback drains`() {
        val drained = CompletableDeferred<Unit>()
        val results = mutableListOf<SpeechPlaybackResult>()
        val voice = runtime(player = PcmSpeechPlayer { drained.await() })
        voice.speak("test") { results += it }
        idle()
        assertTrue(results.isEmpty())
        drained.complete(Unit)
        idle()
        assertEquals(listOf(SpeechPlaybackResult.COMPLETED), results)
    }

    @Test
    fun `cloud failure is visible and cannot masquerade as completion`() {
        val results = mutableListOf<SpeechPlaybackResult>()
        val voice = runtime(
            synthesizer = SpeechSynthesizer { _, _, _ ->
                throw SpeechSynthesisException(SynthesisFailure.AUTHORIZATION)
            },
        )
        voice.speak("test") { results += it }
        idle()
        assertEquals(listOf(SpeechPlaybackResult.FAILED), results)
        assertEquals(SynthesisFailure.AUTHORIZATION.message, voice.state.value.errorMessage)
        assertTrue(voice.state.value.offerSpeechSettings)
    }

    @Test
    fun `wake acknowledgement never reads cloud credentials or synthesizes remotely`() {
        val voice = runtime(
            synthesizer = SpeechSynthesizer { _, _, _ -> error("Wake must remain local") },
        )
        voice.speak("Yes?", SpeechPurpose.WAKE_ACKNOWLEDGEMENT)
        idle()
        assertEquals(0, settingsReads)
    }

    @Test
    fun `interruption releases playback without invoking stale completion`() {
        var released = false
        val results = mutableListOf<SpeechPlaybackResult>()
        val voice = runtime(
            player = PcmSpeechPlayer {
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            },
        )
        voice.speak("test") { results += it }
        idle()
        voice.stopSpeaking()
        idle()
        assertTrue(released)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `queued old completion cannot complete a newer utterance`() {
        val drained = CompletableDeferred<Unit>()
        var playCount = 0
        val results = mutableListOf<String>()
        val voice = runtime(
            player = PcmSpeechPlayer {
                playCount += 1
                if (playCount == 2) drained.await()
            },
        )
        voice.speak("first") { results += "first" }
        voice.speak("second") { results += "second" }
        idle()
        assertTrue(results.isEmpty())
        drained.complete(Unit)
        idle()
        assertEquals(listOf("second"), results)
    }

    @Test
    fun `stopping synthesis cancels its network operation and prevents playback`() {
        var cancelled = false
        var played = false
        val voice = runtime(
            synthesizer = SpeechSynthesizer { _, _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            player = PcmSpeechPlayer { played = true },
        )
        voice.speak("test")
        idle()
        voice.stopSpeaking()
        idle()
        assertTrue(cancelled)
        assertEquals(false, played)
    }

    private fun runtime(
        synthesizer: SpeechSynthesizer = SpeechSynthesizer { _, _, _ -> byteArrayOf(0, 0) },
        player: PcmSpeechPlayer = PcmSpeechPlayer {},
        synthesisEnabled: Boolean = true,
    ): AndroidVoiceRuntime = AndroidVoiceRuntime(
        context = RuntimeEnvironment.getApplication(),
        speechSettingsRepository = object : SpeechSettingsRepository {
            override suspend fun loadSummary() = SpeechSettingsSummary(synthesisEnabled = synthesisEnabled)
            override suspend fun save(input: SpeechSettingsInput) = error("Not used")
            override suspend fun loadRuntimeConfig(): SpeechRuntimeConfig {
                settingsReads += 1
                return SpeechRuntimeConfig.IFlytek("test-app", "test-key", "test-secret")
            }
        },
        synthesizer = synthesizer,
        pcmPlayer = player,
        ioDispatcher = Dispatchers.Unconfined,
    ).also {
        runtime = it
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
