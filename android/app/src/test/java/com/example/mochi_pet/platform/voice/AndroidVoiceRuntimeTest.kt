package com.example.mochi_pet.platform.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidVoiceRuntimeTest {
    @Test
    fun `Xiaomi recognition requests its local system engine`() {
        assertEquals(
            mapOf(
                "useLocal" to true,
                "scene" to "default",
            ),
            deviceRecognitionExtras("Xiaomi"),
        )
    }

    @Test
    fun `other manufacturers use their default recognition contract`() {
        assertEquals(emptyMap<String, Any>(), deviceRecognitionExtras("Samsung"))
    }

    @Test
    fun `final recognition result wins over partial transcript`() {
        assertEquals(
            "final words",
            preferredRecognitionTranscript(
                finalCandidates = listOf("final words"),
                latestPartialTranscript = "partial words",
            ),
        )
    }

    @Test
    fun `partial transcript survives an empty final result`() {
        assertEquals(
            "a long partial statement",
            preferredRecognitionTranscript(
                finalCandidates = emptyList(),
                latestPartialTranscript = "a long partial statement",
            ),
        )
    }
}
