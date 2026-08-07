package com.example.mochi_pet.core.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRuntimeStateTest {
    @Test
    fun `wake lifecycle preserves trigger while resuming`() {
        val starting = reduceWakeRuntimeState(
            WakeRuntimeState(),
            WakeRuntimeEvent.Starting,
        )
        val paused = reduceWakeRuntimeState(
            starting,
            WakeRuntimeEvent.Paused("wake:HI MOCHI"),
        )
        val listening = reduceWakeRuntimeState(
            paused,
            WakeRuntimeEvent.Listening,
        )

        assertTrue(starting.enabled)
        assertEquals("wake:HI MOCHI", paused.lastTriggerSource)
        assertEquals(WakeCaptureStatus.LISTENING, listening.status)
        assertEquals("wake:HI MOCHI", listening.lastTriggerSource)
        assertNull(listening.errorMessage)
    }

    @Test
    fun `disable clears prior wake errors and triggers`() {
        val disabled = reduceWakeRuntimeState(
            WakeRuntimeState(
                status = WakeCaptureStatus.ERROR,
                lastTriggerSource = "media_button",
                errorMessage = "failed",
            ),
            WakeRuntimeEvent.Disabled,
        )

        assertFalse(disabled.enabled)
        assertNull(disabled.lastTriggerSource)
        assertNull(disabled.errorMessage)
    }
}
