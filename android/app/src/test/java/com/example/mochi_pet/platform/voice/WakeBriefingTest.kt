package com.example.mochi_pet.platform.voice

import com.example.mochi_pet.core.voice.VoiceRuntime
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_pet.core.voice.SpeechPurpose
import com.example.mochi_pet.core.voice.SpeechPlaybackResult
import com.example.mochi_pet.core.voice.WakeBriefingResult
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class WakeBriefingTest {
    @Test fun `unsupported runtime listens without speaking or claiming completion`() {
        var listening = false
        val voice = object : VoiceRuntime {
            override val state = MutableStateFlow(VoiceRuntimeState())
            override fun startListening(onFinalTranscript: (String) -> Unit, onNoResult: () -> Unit) { listening = true }
            override fun stopListening() = Unit
            override fun stopSpeaking() = Unit
            override fun speak(text: String, purpose: SpeechPurpose, previewVoiceId: String?, previewProfileId: String?, onCompleted: (SpeechPlaybackResult) -> Unit) {
                error("Unsupported simultaneous input must not play a briefing")
            }
        }
        val results = mutableListOf<WakeBriefingResult>()
        voice.startListeningWithBriefing("Remote task finished.", results::add, {}, {})
        assertTrue(listening)
        assertEquals(listOf(WakeBriefingResult.SKIPPED), results)
    }

    @Test fun `confirmed wake prefix is removed without losing following command or ordinary speech`() {
        assertEquals("继续检查", briefingTranscript("Hi Mochi，继续检查", "", true))
        assertEquals("check the result", briefingTranscript("HI MOCHI, check the result", "", true))
        assertEquals("", briefingTranscript("Hi Mochi", "", true))
        assertEquals("Hi Mochi", briefingTranscript("Hi Mochi", "", false))
        assertEquals("停止它", briefingTranscript("停止它", "任务跑完了", false))
        assertEquals("", briefingTranscript("Task finished!", "Task finished.", false))
        assertEquals("Task finished, show me details", briefingTranscript("Task finished, show me details", "Task finished.", false))
    }
}
