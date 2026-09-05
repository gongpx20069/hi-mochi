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
    fun `camera event image wait is bounded to fifteen seconds`() {
        assertEquals(
            14_000L,
            extensionToolTimeoutMillis(
                "mijia_get_latest_camera_event_image",
            ),
        )
        assertEquals(
            60_000L,
            extensionToolTimeoutMillis("mijia_list_devices"),
        )
        assertEquals(
            15_000L,
            extensionHostTimeoutMillis(
                extensionToolTimeoutMillis(
                    "mijia_get_latest_camera_event_image",
                ),
            ),
        )
    }

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
