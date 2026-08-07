package com.example.mochi_pet.platform.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeRuntime
import com.example.mochi_pet.core.wake.WakeRuntimeEvent
import com.example.mochi_pet.core.wake.WakeRuntimeState
import com.example.mochi_pet.core.wake.reduceWakeRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidWakeRuntime(
    context: Context,
) : WakeRuntime, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(WakeRuntimeState())
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            if (intent?.action != WakeCaptureService.ACTION_STATE_CHANGED) {
                return
            }
            val status = intent.getStringExtra(
                WakeCaptureService.EXTRA_STATUS,
            )?.let(WakeCaptureStatus::valueOf)
                ?: WakeCaptureStatus.ERROR
            val event = when (status) {
                WakeCaptureStatus.DISABLED -> WakeRuntimeEvent.Disabled
                WakeCaptureStatus.STARTING -> WakeRuntimeEvent.Starting
                WakeCaptureStatus.LISTENING -> WakeRuntimeEvent.Listening
                WakeCaptureStatus.PAUSED -> WakeRuntimeEvent.Paused(
                    triggerSource = intent.getStringExtra(
                        WakeCaptureService.EXTRA_TRIGGER_SOURCE,
                    ),
                )
                WakeCaptureStatus.ERROR -> WakeRuntimeEvent.Failed(
                    message = intent.getStringExtra(
                        WakeCaptureService.EXTRA_ERROR,
                    ) ?: "Wake capture failed",
                )
            }
            mutableState.value = reduceWakeRuntimeState(
                mutableState.value,
                event,
            )
            if (
                status == WakeCaptureStatus.PAUSED ||
                status == WakeCaptureStatus.DISABLED ||
                status == WakeCaptureStatus.ERROR
            ) {
                val callbacks = pauseCallbacks.toList()
                pauseCallbacks.clear()
                callbacks.forEach { it() }
            }
        }
    }
    private val pauseCallbacks = mutableListOf<() -> Unit>()

    override val state: StateFlow<WakeRuntimeState> = mutableState.asStateFlow()
    override val shouldBeEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)

    init {
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            IntentFilter(WakeCaptureService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sendAction(WakeCaptureService.ACTION_QUERY_STATE)
    }

    override fun enable() {
        preferences.edit { putBoolean(KEY_ENABLED, true) }
        mutableState.value = reduceWakeRuntimeState(
            mutableState.value,
            WakeRuntimeEvent.Starting,
        )
        sendAction(WakeCaptureService.ACTION_START, foreground = true)
    }

    override fun disable() {
        preferences.edit { putBoolean(KEY_ENABLED, false) }
        sendAction(WakeCaptureService.ACTION_STOP)
        mutableState.value = reduceWakeRuntimeState(
            mutableState.value,
            WakeRuntimeEvent.Disabled,
        )
    }

    override fun pause(onPaused: () -> Unit) {
        if (!mutableState.value.enabled) {
            onPaused()
            return
        }
        if (mutableState.value.status == WakeCaptureStatus.PAUSED) {
            sendAction(WakeCaptureService.ACTION_PAUSE)
            onPaused()
            return
        }
        pauseCallbacks += onPaused
        sendAction(WakeCaptureService.ACTION_PAUSE)
    }

    override fun resume() {
        if (mutableState.value.enabled) {
            sendAction(WakeCaptureService.ACTION_RESUME)
        }
    }

    override fun close() {
        pauseCallbacks.clear()
        applicationContext.unregisterReceiver(receiver)
    }

    private fun sendAction(
        action: String,
        foreground: Boolean = false,
    ) {
        val intent = Intent(applicationContext, WakeCaptureService::class.java)
            .setAction(action)
        try {
            if (foreground) {
                ContextCompat.startForegroundService(applicationContext, intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (error: SecurityException) {
            mutableState.value = reduceWakeRuntimeState(
                mutableState.value,
                WakeRuntimeEvent.Failed(
                    "Android blocked wake-word microphone access",
                ),
            )
        } catch (error: IllegalStateException) {
            mutableState.value = reduceWakeRuntimeState(
                mutableState.value,
                WakeRuntimeEvent.Failed(
                    "Wake-word service cannot start in the current state",
                ),
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "mochi_wake_preferences"
        const val KEY_ENABLED = "enabled"
    }
}
