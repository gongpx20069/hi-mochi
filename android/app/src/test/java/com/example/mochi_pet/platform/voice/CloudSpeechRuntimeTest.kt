package com.example.mochi_pet.platform.voice

import android.os.Looper
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.SpeechRecognizer
import android.os.Bundle
import org.robolectric.shadows.ShadowSpeechRecognizer
import com.example.mochi_pet.R
import com.example.mochi_pet.core.settings.AppLanguage
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
import org.robolectric.shadows.ShadowTextToSpeech

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

    @Test fun `finishing before speech settings load completes never starts capture`() {
        val config = CompletableDeferred<SpeechRuntimeConfig>()
        val voice = runtime(recognitionConfig = { config.await() })
        var empty = 0
        voice.startListening({ error("No audio was captured") }, { empty++ }, manualEndpoint = true)
        idle()
        voice.finishListening()
        idle()
        config.complete(SpeechRuntimeConfig.System())
        idle()
        assertEquals(1, empty)
        assertEquals(false, voice.state.value.isListening)
    }

    @Test fun `manual system finish delivers final transcript but cancellation discards it`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val voice = runtime(recognitionConfig = { SpeechRuntimeConfig.System() })
        val words = mutableListOf<String>()
        voice.startListening(words::add, {}, manualEndpoint = true)
        idle()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        voice.finishListening()
        idle()
        recognizer.triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Dictated text"))
        })
        idle()
        assertEquals(listOf("Dictated text"), words)
        voice.startListening(words::add, {}, manualEndpoint = true)
        idle()
        val cancelledRecognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        voice.stopListening()
        idle()
        voice.startListening(words::add, {}, manualEndpoint = true)
        idle()
        cancelledRecognizer.triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Cancelled text"))
        })
        idle()
        assertEquals(listOf("Dictated text"), words)
        assertTrue(voice.state.value.isListening)
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("New text"))
        })
        idle()
        assertEquals(listOf("Dictated text", "New text"), words)
    }

    @Test fun `unresponsive system finalization ends with an explicit timeout`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val voice = runtime(recognitionConfig = { SpeechRuntimeConfig.System() })
        var empty = 0
        voice.startListening({ error("No result was delivered") }, { empty++ }, manualEndpoint = true)
        idle()
        voice.finishListening()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21))
        assertEquals(1, empty)
        assertEquals(false, voice.state.value.isListening)
        assertEquals("Speech recognition timed out. Please try again.", voice.state.value.errorMessage)
    }

    @Test
    fun `reply speaks link labels before splitting cloud synthesis requests`() {
        val spoken = mutableListOf<String>()
        val results = mutableListOf<SpeechPlaybackResult>()
        val voice = runtime(synthesizer = SpeechSynthesizer { _, text, _ ->
            spoken += text
            byteArrayOf(0, 0)
        })
        val destination = "https://example.com/" + "path".repeat(100)
        voice.speak("See [the guide]($destination) and https://example.org.") { results += it }
        idle()
        assertEquals(listOf("See the guide and https://example.org."), spoken)
        assertEquals(listOf(SpeechPlaybackResult.COMPLETED), results)
    }

    @Test
    fun `system speech receives link labels without changing bare URLs`() {
        val locale = AppLanguage.resolveContentLocale()
        ShadowTextToSpeech.addLanguageAvailability(locale)
        ShadowTextToSpeech.addVoice(Voice("offline", locale, 300, 300, false, emptySet()))
        val voice = runtime(synthesisEnabled = false)
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        voice.speak("See [the guide](https://example.com) or https://example.org.")
        idle()
        assertEquals("See the guide or https://example.org.", tts.lastSpokenText)
    }

    @Test fun `preview uses the selected inactive account without switching normal speech`() {
        val configs = mutableListOf<SpeechRuntimeConfig.IFlytek>()
        val voice = runtime(synthesizer = SpeechSynthesizer { config, _, _ ->
            configs += config as SpeechRuntimeConfig.IFlytek
            byteArrayOf(0, 0)
        })
        voice.speak("preview", SpeechPurpose.PREVIEW, "x4_yezi", "inactive")
        idle()
        voice.speak("normal reply")
        idle()
        assertEquals(listOf("other-key", "test-key"), configs.map { it.apiKey })
        assertEquals("x4_yezi", configs.first().voice)
    }

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
    @Config(qualifiers = "zh-rCN")
    fun `Chinese wake acknowledgement plays the shared cue without a working TTS engine`() {
        val drained = CompletableDeferred<Unit>()
        val played = mutableListOf<ByteArray>()
        val results = mutableListOf<SpeechPlaybackResult>()
        val voice = runtime(
            synthesizer = SpeechSynthesizer { _, _, _ -> error("Wake must remain local") },
            player = PcmSpeechPlayer { played += it; drained.await() },
        )
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        voice.speak("嗯？", SpeechPurpose.WAKE_ACKNOWLEDGEMENT) { results += it }
        idle()
        val expected = RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.wake_ack).use { it.readBytes() }
        assertTrue(expected.contentEquals(played.single()))
        val durationSeconds = played.single().size.toDouble() / (SYNTHESIS_SAMPLE_RATE * 2)
        assertTrue(durationSeconds in 0.3..0.5)
        assertEquals(0, played.single().size % 2)
        assertTrue(played.single().any { it != 0.toByte() })
        assertTrue(results.isEmpty())
        drained.complete(Unit)
        idle()
        assertEquals(listOf(SpeechPlaybackResult.COMPLETED), results)
        assertEquals(null, tts.lastSpokenText)
        assertEquals(0, settingsReads)
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `English wake uses the same cue and cancellation suppresses its completion`() {
        var released = false
        val played = mutableListOf<ByteArray>()
        val results = mutableListOf<SpeechPlaybackResult>()
        val voice = runtime(player = PcmSpeechPlayer {
            played += it
            try { awaitCancellation() } finally { released = true }
        })
        voice.speak("Yes?", SpeechPurpose.WAKE_ACKNOWLEDGEMENT) { results += it }
        idle()
        val expected = RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.wake_ack).use { it.readBytes() }
        assertTrue(expected.contentEquals(played.single()))
        assertTrue(played.single().isNotEmpty())
        voice.stopSpeaking()
        idle()
        assertTrue(released)
        assertTrue(results.isEmpty())
        assertEquals(0, settingsReads)
    }

    @Test
    fun `bundled cue playback failure is explicit without suggesting TTS setup`() {
        val results = mutableListOf<SpeechPlaybackResult>()
        val voice = runtime(player = PcmSpeechPlayer { throw SpeechSynthesisException(SynthesisFailure.PLAYBACK) })
        voice.speak("Yes?", SpeechPurpose.WAKE_ACKNOWLEDGEMENT) { results += it }
        idle()
        assertEquals(listOf(SpeechPlaybackResult.FAILED), results)
        assertEquals(SynthesisFailure.PLAYBACK.message, voice.state.value.errorMessage)
        assertEquals(false, voice.state.value.offerSpeechSettings)
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
        recognitionConfig: suspend () -> SpeechRuntimeConfig = { SpeechRuntimeConfig.IFlytek("test-app", "test-key", "test-secret") },
    ): AndroidVoiceRuntime = AndroidVoiceRuntime(
        context = RuntimeEnvironment.getApplication(),
        speechSettingsRepository = object : SpeechSettingsRepository by com.example.mochi_pet.core.settings.DataStoreSpeechSettingsRepository(
            com.example.mochi_pet.core.settings.SpeechPreferencesDataStore(),
            com.example.mochi_pet.core.settings.SpeechFakeCipher(),
        ) {
            override suspend fun loadSummary() = SpeechSettingsSummary(synthesisEnabled = synthesisEnabled)
            override suspend fun save(input: SpeechSettingsInput) = error("Not used")
            override suspend fun loadRuntimeConfig(): SpeechRuntimeConfig {
                settingsReads += 1
                return recognitionConfig()
            }
            override suspend fun loadSynthesisConfig(): SpeechRuntimeConfig =
                if (synthesisEnabled) loadRuntimeConfig() else SpeechRuntimeConfig.System()
            override suspend fun loadProfileRuntimeConfig(id: String): SpeechRuntimeConfig {
                check(id == "inactive")
                return SpeechRuntimeConfig.IFlytek("other-app", "other-key", "other-secret")
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
