package com.example.mochi_pet.core.extensions

import com.example.mochi_extension.MochiExtensionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MochiExtensionClientTest {
    @Test
    fun `trusted installed extension exposes configuration before binding`() {
        assertEquals(
            ExtensionActivityTarget(
                packageName = MochiExtensionProtocol.MIJIA_PACKAGE,
                className =
                    MochiExtensionProtocol.MIJIA_CONFIGURATION_ACTIVITY,
            ),
            MijiaExtensionSnapshot(
                installed = true,
                trusted = true,
            ).configurationTarget,
        )
    }

    @Test
    fun `untrusted extension does not expose configuration`() {
        assertNull(
            MijiaExtensionSnapshot(
                installed = true,
                trusted = false,
            ).configurationTarget,
        )
    }
}
