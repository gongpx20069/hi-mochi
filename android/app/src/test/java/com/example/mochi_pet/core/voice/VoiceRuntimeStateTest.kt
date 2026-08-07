package com.example.mochi_pet.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRuntimeStateTest {
    @Test
    fun `starting and stopping clears transient transcript`() {
        val listening = reduceVoiceRuntimeState(
            VoiceRuntimeState(
                recognitionAvailable = true,
                errorMessage = "old error",
            ),
            VoiceRuntimeEvent.ListeningStarted,
        )
        val partial = reduceVoiceRuntimeState(
            listening,
            VoiceRuntimeEvent.PartialTranscript("tomorrow"),
        )
        val stopped = reduceVoiceRuntimeState(
            partial,
            VoiceRuntimeEvent.ListeningStopped,
        )

        assertTrue(listening.isListening)
        assertNull(listening.errorMessage)
        assertEquals("tomorrow", partial.partialTranscript)
        assertFalse(stopped.isListening)
        assertEquals("", stopped.partialTranscript)
    }

    @Test
    fun `failure ends listening and exposes bounded safe message`() {
        val failed = reduceVoiceRuntimeState(
            VoiceRuntimeState(isListening = true),
            VoiceRuntimeEvent.Failed("No speech was detected"),
        )

        assertFalse(failed.isListening)
        assertEquals("No speech was detected", failed.errorMessage)
    }

    @Test
    fun `speech failure can recommend optional provider settings`() {
        val failed = reduceVoiceRuntimeState(
            VoiceRuntimeState(isListening = true),
            VoiceRuntimeEvent.Failed(
                message = "Android did not recognize that",
                offerSpeechSettings = true,
            ),
        )

        assertTrue(failed.offerSpeechSettings)
    }
}
