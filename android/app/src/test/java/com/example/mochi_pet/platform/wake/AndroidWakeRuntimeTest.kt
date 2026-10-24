package com.example.mochi_pet.platform.wake

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeKeyword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidWakeRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

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

    private fun broadcast(keyword: WakeKeyword, status: WakeCaptureStatus) {
        context.sendBroadcast(
            Intent(WakeCaptureService.ACTION_STATE_CHANGED).setPackage(context.packageName)
                .putExtra(WakeCaptureService.EXTRA_KEYWORD, keyword.name)
                .putExtra(WakeCaptureService.EXTRA_STATUS, status.name),
        )
        ShadowLooper.idleMainLooper()
    }
}
