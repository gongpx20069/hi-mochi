package com.example.mochi_pet.platform.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedSpeechCaptureTest {
    @Test
    fun `completed sherpa speech segment completes the utterance`() {
        assertEquals(
            SpeechEndpoint.COMPLETE,
            resolveSpeechEndpoint(
                speechStarted = true,
                segmentComplete = true,
                totalChunks = 20,
                noSpeechChunkLimit = 100,
                maximumChunkLimit = 200,
            ),
        )
    }

    @Test
    fun `active sherpa speech continues recording`() {
        assertEquals(
            SpeechEndpoint.CONTINUE,
            resolveSpeechEndpoint(
                speechStarted = true,
                segmentComplete = false,
                totalChunks = 20,
                noSpeechChunkLimit = 100,
                maximumChunkLimit = 200,
            ),
        )
    }

    @Test
    fun `silence eventually reports no speech`() {
        assertEquals(
            SpeechEndpoint.NO_SPEECH,
            resolveSpeechEndpoint(
                speechStarted = false,
                segmentComplete = false,
                totalChunks = 100,
                noSpeechChunkLimit = 100,
                maximumChunkLimit = 200,
            ),
        )
    }

    @Test
    fun `active speech is bounded by maximum duration`() {
        assertEquals(
            SpeechEndpoint.COMPLETE,
            resolveSpeechEndpoint(
                speechStarted = true,
                segmentComplete = false,
                totalChunks = 200,
                noSpeechChunkLimit = 100,
                maximumChunkLimit = 200,
            ),
        )
    }

    @Test
    fun `recorded retries use bounded backoff`() {
        assertEquals(800L, cloudRetryDelayMillis(1))
        assertEquals(2_000L, cloudRetryDelayMillis(2))
    }

    @Test
    fun `three second no speech timeout uses capture chunk cadence`() {
        assertEquals(150, secondsToChunkCount(3.0f))
    }
}
