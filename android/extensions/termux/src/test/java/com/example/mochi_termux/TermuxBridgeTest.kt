package com.example.mochi_termux

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TermuxBridgeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun installFakeTermux() {
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.termux"
                name = "com.termux.app.TermuxActivity"
                applicationInfo = ApplicationInfo().apply { packageName = "com.termux" }
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.termux"), resolve,
        )
        shadowOf(app).grantPermissions(TERMUX_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun complete(intent: Intent, code: Int = 0, output: String = "MOCHI_READY_V1\n") {
        val callback = intent.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
        callback.send(app, 0, Intent().putExtra("result", Bundle().apply {
            putInt("err", -1)
            putInt("exitCode", code)
            putString("stdout", output)
        }))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `connection proves explicit background callback before enabling tools`() = runTest {
        val bridge = TermuxBridge(app)
        assertFalse(bridge.connected())
        val connection = async { bridge.connect() }
        runCurrent()
        val intent = shadowOf(app).nextStartedService
        assertEquals("com.termux.app.RunCommandService", intent.component!!.className)
        assertEquals("com.termux", intent.component!!.packageName)
        assertEquals("$TERMUX_PREFIX/bin/bash", intent.getStringExtra("com.termux.RUN_COMMAND_PATH"))
        assertTrue(intent.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
        assertEquals("0", intent.getStringExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL"))
        assertFalse(connection.isCompleted)
        complete(intent)
        connection.await()
        assertTrue(bridge.connected())
        val id = bridge.start(ShellCommand("printf hello", TERMUX_HOME, 120))
        assertTrue(validTaskId(id))
        assertTrue(id in TermuxBridge(app).taskIds())
        bridge.disconnect()
        assertFalse(bridge.connected())
        assertTrue(id in bridge.taskIds())
    }

    @Test
    fun `permission revocation prevents command dispatch`() = runTest {
        val bridge = TermuxBridge(app)
        val connection = async { bridge.connect() }
        runCurrent()
        complete(shadowOf(app).nextStartedService)
        connection.await()
        shadowOf(app).denyPermissions(TERMUX_PERMISSION)
        assertFalse(bridge.connected())
        org.junit.Assert.assertThrows(TermuxException::class.java) {
            bridge.start(ShellCommand("true", TERMUX_HOME, 1))
        }
        assertTrue(bridge.taskIds().isEmpty())
    }
}
