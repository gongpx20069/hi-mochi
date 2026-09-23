package com.example.mochi_pet.core.extensions

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_extension.MochiExtensionProtocol
import com.example.mochi_extension.ExtensionToolDefinition
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
    fun `Mi Home adapter rejects background scopes before binding`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = AndroidMochiExtensionClient(context, TrustedExtension.MIJIA)
        val definition = ExtensionToolDefinition("mijia_list_devices", "", """{"type":"object"}""", "read", true)
        listOf(ExtensionToolScope.SCHEDULED, ExtensionToolScope.SUBAGENT).forEach { scope ->
            val result = client.agentTool(definition, scope).execute(
                JsonObject(emptyMap()), ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face),
            )
            assertEquals("PERMISSION_DENIED", result.code)
            assertEquals("This extension is only available to the foreground Main Agent.", result.message)
        }
    }

    @Test
    fun `Termux configuration is explicit and cannot resolve to Mi Home`() {
        assertEquals(
            ExtensionActivityTarget(MochiExtensionProtocol.TERMUX_PACKAGE, MochiExtensionProtocol.TERMUX_CONFIGURATION_ACTIVITY),
            MochiExtensionSnapshot(installed = true, trusted = true, identity = TrustedExtension.TERMUX).configurationTarget,
        )
        assertNull(MochiExtensionSnapshot(installed = true, identity = TrustedExtension.TERMUX).configurationTarget)
    }

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
            MochiExtensionSnapshot(
                installed = true,
                trusted = true,
            ).configurationTarget,
        )
    }

    @Test
    fun `untrusted extension does not expose configuration`() {
        assertNull(
            MochiExtensionSnapshot(
                installed = true,
                trusted = false,
            ).configurationTarget,
        )
    }
}
