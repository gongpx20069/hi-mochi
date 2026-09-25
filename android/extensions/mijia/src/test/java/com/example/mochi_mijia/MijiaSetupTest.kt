package com.example.mochi_mijia

import org.junit.Assert.assertEquals
import org.junit.Test

class MijiaSetupTest {
    private fun device(id: String, home: String, room: String, category: MijiaDeviceCategory = MijiaDeviceCategory.LIGHT) =
        MijiaDevice(id, "Lamp $id", "fixture.light", null, true, home, home, room, room, category)

    @Test
    fun `search includes home room and name but never unsupported devices`() {
        val devices = listOf(
            device("b", "Home", "Study"), device("a", "Home", "Bedroom"),
            device("hidden", "Home", "Study", MijiaDeviceCategory.UNKNOWN),
        )
        assertEquals(listOf("a", "b"), visibleMijiaDevices(devices, " HOME ").map { it.id })
        assertEquals(listOf("b"), visibleMijiaDevices(devices, "study").map { it.id })
        assertEquals(listOf("a"), visibleMijiaDevices(devices, "Lamp a").map { it.id })
        assertEquals(emptyList<String>(), visibleMijiaDevices(devices, "unknown room").map { it.id })
    }
}
