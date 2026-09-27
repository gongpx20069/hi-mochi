package com.example.mochi_pet.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class FocusStandbyTest {
    @Test
    fun `standby always uses zero padded twenty four hour time without seconds`() {
        assertEquals("00:05", focusStandbyTime(LocalTime.of(0, 5, 59)))
        assertEquals("09:07", focusStandbyTime(LocalTime.of(9, 7)))
        assertEquals("13:42", focusStandbyTime(LocalTime.of(13, 42)))
        assertEquals("23:59", focusStandbyTime(LocalTime.of(23, 59)))
    }

    @Test
    fun `Focus mode control toggles full screen on and off`() {
        assertTrue(focusModeAfterToggle(false))
        assertFalse(focusModeAfterToggle(true))
    }

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
