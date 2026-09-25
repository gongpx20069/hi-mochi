package com.example.mochi_termux

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Looper
import com.example.mochi_extension.ExtensionExecutionContext
import com.example.mochi_extension.ExtensionToolRequest
import com.example.mochi_extension.ExtensionToolResult
import com.example.mochi_extension.IMochiExtensionService
import com.example.mochi_extension.IMochiToolCallback
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TermuxBridgeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `setup never skips installation or denied permission`() {
        assertEquals(TermuxSetupStep.INSTALL, termuxSetupStep(false, true))
        assertEquals(TermuxSetupStep.PERMISSION, termuxSetupStep(true, false))
        assertEquals(TermuxSetupStep.CHECK, termuxSetupStep(true, true))
        val model = TermuxSetupViewModel(app)
        model.permissionResult(false)
        assertEquals(TermuxSetupStep.PERMISSION, model.step)
        assertEquals(R.string.permission_denied, model.error)
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test
    fun `repeated resume dispatches one bounded probe and never assumes success`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val model = TermuxSetupViewModel(app)
            store.put("setup", model)
            model.awaitingTerminal = true
            model.resume()
            model.resume()
            runCurrent()
            assertFalse(model.awaitingTerminal)
            assertEquals(TermuxSetupStep.CHECKING, model.step)
            assertTrue(shadowOf(app).nextStartedService != null)
            assertEquals(null, shadowOf(app).nextStartedService)
            testScheduler.advanceTimeBy(15_001)
            runCurrent()
            assertEquals(TermuxSetupStep.ERROR, model.step)
            assertEquals(R.string.check_timeout, model.error)
        } finally {
            store.clear()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

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

    @Test
    fun `signed service accepts foreground scheduled and subagent contexts but not unknown contexts`() = runTest {
        val bridge = TermuxBridge(app)
        val connection = async { bridge.connect() }
        runCurrent()
        complete(shadowOf(app).nextStartedService)
        connection.await()
        val controller = Robolectric.buildService(TermuxExtensionService::class.java).create()
        try {
            val service = IMochiExtensionService.Stub.asInterface(controller.get().onBind(Intent()))
            suspend fun call(scope: String): ExtensionToolResult {
                val result = CompletableDeferred<ExtensionToolResult>()
                service.callTool(
                    ExtensionToolRequest(UUID.randomUUID().toString(), "termux_task", """{"action":"list"}""", 5_000, scope),
                    object : IMochiToolCallback.Stub() {
                        override fun onResult(value: ExtensionToolResult) { result.complete(value) }
                    },
                )
                return withTimeout(5_000) { result.await() }
            }
            // The Android service uses a real IO dispatcher, not the test scheduler.
            runBlocking {
                ExtensionExecutionContext.ALL.forEach { scope ->
                    val result = call(scope)
                    assertTrue("$scope: ${result.errorCode}", result.success)
                    assertEquals("""{"task_ids":[]}""", result.contentJson)
                }
                assertEquals("INVALID_ARGS", call("unknown").errorCode)
                bridge.disconnect()
                assertEquals("PERMISSION_DENIED", call(ExtensionExecutionContext.SCHEDULED).errorCode)
            }
        } finally {
            controller.destroy()
        }
    }
}
