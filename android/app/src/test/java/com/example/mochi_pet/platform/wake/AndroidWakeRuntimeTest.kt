package com.example.mochi_pet.platform.wake

import android.app.Application
import android.app.KeyguardManager
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeKeyword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidWakeRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `unset preference follows Chinese system even with an English app locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            AndroidWakeRuntime(context).use { runtime ->
                assertEquals(WakeKeyword.CHINESE, runtime.state.value.keyword)
                assertEquals(null, runtime.state.value.keywordOverride)
                assertEquals(WakeKeyword.CHINESE, readWakeKeyword(context))
            }
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `other system languages default to English`() {
        AndroidWakeRuntime(context).use { runtime ->
            assertEquals(WakeKeyword.ENGLISH, runtime.state.value.keyword)
            assertEquals(null, runtime.state.value.keywordOverride)
        }
    }

    @Test
    fun `pinning the current phrase survives locale changes and returning to system persists`() {
        AndroidWakeRuntime(context).use { runtime ->
            runtime.selectKeyword(WakeKeyword.ENGLISH)
        }
        RuntimeEnvironment.setQualifiers("zh-rTW")
        AndroidWakeRuntime(context).use { runtime ->
            assertEquals(WakeKeyword.ENGLISH, runtime.state.value.keyword)
            assertEquals(WakeKeyword.ENGLISH, runtime.state.value.keywordOverride)
            runtime.selectKeyword(null)
            assertEquals(WakeKeyword.CHINESE, runtime.state.value.keyword)
            assertEquals(WakeCaptureStatus.DISABLED, runtime.state.value.status)
        }
        AndroidWakeRuntime(context).use { runtime ->
            assertEquals(null, runtime.state.value.keywordOverride)
            assertEquals(WakeKeyword.CHINESE, runtime.state.value.keyword)
            RuntimeEnvironment.setQualifiers("en-rUS")
            context.sendBroadcast(Intent(Intent.ACTION_LOCALE_CHANGED))
            ShadowLooper.idleMainLooper()
            assertEquals(WakeKeyword.ENGLISH, runtime.state.value.keyword)
            assertEquals(WakeCaptureStatus.DISABLED, runtime.state.value.status)
        }
    }

    @Test
    fun `system language changes keep capture paused and reject the old phrase`() {
        AndroidWakeRuntime(context).use { runtime ->
            runtime.enable()
            broadcast(WakeKeyword.ENGLISH, WakeCaptureStatus.PAUSED)
            RuntimeEnvironment.setQualifiers("zh-rCN")
            context.sendBroadcast(Intent(Intent.ACTION_LOCALE_CHANGED))
            ShadowLooper.idleMainLooper()
            assertEquals(WakeKeyword.CHINESE, runtime.state.value.keyword)
            assertEquals(WakeCaptureStatus.PAUSED, runtime.state.value.status)
            broadcast(WakeKeyword.ENGLISH, WakeCaptureStatus.LISTENING)
            assertEquals(WakeCaptureStatus.PAUSED, runtime.state.value.status)
        }
    }

    @Test
    fun `selection survives recreation without enabling disabled capture`() {
        context.getSharedPreferences("mochi_wake_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).commit()
        AndroidWakeRuntime(context).use { runtime ->
            runtime.selectKeyword(WakeKeyword.CHINESE)
            assertEquals(WakeCaptureStatus.DISABLED, runtime.state.value.status)
            assertFalse(runtime.shouldBeEnabled)
        }
        AndroidWakeRuntime(context).use { runtime ->
            assertEquals(WakeKeyword.CHINESE, runtime.state.value.keyword)
            assertFalse(runtime.shouldBeEnabled)
        }
    }

    @Test
    fun `old phrase broadcasts cannot resume capture or release a pending pause`() {
        AndroidWakeRuntime(context).use { runtime ->
            runtime.setCaptureAllowed(true)
            runtime.enable()
            broadcast(WakeKeyword.ENGLISH, WakeCaptureStatus.LISTENING)
            var paused = false
            runtime.pause { paused = true }
            runtime.selectKeyword(WakeKeyword.CHINESE)
            broadcast(WakeKeyword.ENGLISH, WakeCaptureStatus.PAUSED)
            assertFalse(paused)
            assertEquals(WakeCaptureStatus.STARTING, runtime.state.value.status)
            broadcast(WakeKeyword.CHINESE, WakeCaptureStatus.PAUSED)
            assertEquals(true, paused)
            runtime.selectKeyword(WakeKeyword.ENGLISH)
            assertEquals(WakeCaptureStatus.PAUSED, runtime.state.value.status)
            broadcast(WakeKeyword.CHINESE, WakeCaptureStatus.LISTENING)
            assertEquals(WakeCaptureStatus.PAUSED, runtime.state.value.status)
        }
    }

    @Test fun `hidden Companion blocks starts and stale listening without changing enabled preference`() {
        AndroidWakeRuntime(context).use { runtime ->
            runtime.enable()
            runtime.resume()
            assertEquals(WakeCaptureStatus.PAUSED, runtime.state.value.status)
            assertTrue(runtime.shouldBeEnabled)
            assertFalse(startedActions().contains(WakeCaptureService.ACTION_START))
            runtime.setCaptureAllowed(true)
            assertTrue(startedActions().contains(WakeCaptureService.ACTION_START))
            broadcast(runtime.state.value.keyword, WakeCaptureStatus.LISTENING)
            runtime.setCaptureAllowed(false)
            broadcast(runtime.state.value.keyword, WakeCaptureStatus.PAUSED)
            runtime.resume()
            broadcast(runtime.state.value.keyword, WakeCaptureStatus.LISTENING)
            assertEquals(WakeCaptureStatus.PAUSED, runtime.state.value.status)
            assertFalse(startedActions().contains(WakeCaptureService.ACTION_START))
            assertTrue(runtime.shouldBeEnabled)
        }
    }

    @Test fun `screen off lock and explicit disable cannot be bypassed by resume`() {
        AndroidWakeRuntime(context).use { runtime ->
            runtime.setCaptureAllowed(true)
            runtime.enable()
            startedActions()
            val power = shadowOf(context.getSystemService(PowerManager::class.java))
            val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
            power.setIsInteractive(false)
            runtime.resume()
            assertFalse(runtime.canCapture)
            assertFalse(startedActions().contains(WakeCaptureService.ACTION_START))
            power.setIsInteractive(true)
            keyguard.setKeyguardLocked(true)
            runtime.resume()
            assertFalse(runtime.canCapture)
            assertFalse(startedActions().contains(WakeCaptureService.ACTION_START))
            keyguard.setKeyguardLocked(false)
            runtime.disable()
            runtime.setCaptureAllowed(false)
            runtime.setCaptureAllowed(true)
            runtime.resume()
            assertFalse(runtime.shouldBeEnabled)
            assertFalse(runtime.canCapture)
            broadcast(runtime.state.value.keyword, WakeCaptureStatus.PAUSED)
            assertEquals(WakeCaptureStatus.DISABLED, runtime.state.value.status)
            assertFalse(startedActions().contains(WakeCaptureService.ACTION_START))
        }
    }

    private fun startedActions() = generateSequence { shadowOf(context).nextStartedService }.map { it.action }.toList()

    private fun broadcast(keyword: WakeKeyword, status: WakeCaptureStatus) {
        context.sendBroadcast(
            Intent(WakeCaptureService.ACTION_STATE_CHANGED).setPackage(context.packageName)
                .putExtra(WakeCaptureService.EXTRA_KEYWORD, keyword.name)
                .putExtra(WakeCaptureService.EXTRA_STATUS, status.name),
        )
        ShadowLooper.idleMainLooper()
    }
}
