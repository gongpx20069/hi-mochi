package com.example.mochi_pet.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStandbyTest {
    @Test
    fun `standby requires idle enabled Focus presentation`() {
        assertTrue(
            isFocusStandbyEligible(
                focusMode = true,
                homePresentation = true,
                enabled = true,
                pipelineActive = false,
                voiceListening = false,
                browserActive = false,
            ),
        )
        assertFalse(
            isFocusStandbyEligible(
                focusMode = true,
                homePresentation = true,
                enabled = true,
                pipelineActive = true,
                voiceListening = false,
                browserActive = false,
            ),
        )
        assertFalse(
            isFocusStandbyEligible(
                focusMode = true,
                homePresentation = true,
                enabled = false,
                pipelineActive = false,
                voiceListening = false,
                browserActive = false,
            ),
        )
    }

    @Test
    fun `standby drift cycles through bounded positions`() {
        val offsets = (0L..7L).map(::focusStandbyOffset)

        assertEquals(offsets.take(4), offsets.drop(4))
        assertTrue(offsets.all { it.xDp in -10..10 })
        assertTrue(offsets.all { it.yDp in -10..10 })
    }
}
