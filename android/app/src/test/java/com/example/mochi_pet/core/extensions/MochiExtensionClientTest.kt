package com.example.mochi_pet.core.extensions

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_extension.MochiExtensionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MochiExtensionClientTest {
    @Test
    fun `host requests its extension signature permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            MochiExtensionProtocol.BIND_PERMISSION in
                packageInfo.requestedPermissions.orEmpty(),
        )
    }

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
