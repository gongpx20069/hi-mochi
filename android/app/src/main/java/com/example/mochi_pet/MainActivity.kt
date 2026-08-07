package com.example.mochi_pet

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.mochi_pet.feature.home.MochiApp
import com.example.mochi_pet.platform.wake.WakeCaptureService
import com.example.mochi_pet.ui.theme.MochiTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : AppCompatActivity() {
    private val voiceTriggers = Channel<Unit>(Channel.BUFFERED)
    private val oauthCallbacks = Channel<String>(Channel.BUFFERED)
    private val providerShareCallbacks = Channel<String>(Channel.BUFFERED)
    private val wakeTriggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            if (intent?.action == WakeCaptureService.ACTION_VOICE_TRIGGERED) {
                acceptVoiceTrigger()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as MochiApplication
        handleVoiceTriggerIntent(intent)
        handleOAuthIntent(intent)
        handleProviderShareIntent(intent)
        enableEdgeToEdge()
        setContent {
            MochiTheme {
                MochiApp(
                    voiceRuntime = application.voiceRuntime,
                    wakeRuntime = application.wakeRuntime,
                    voiceTriggers = voiceTriggers.receiveAsFlow(),
                    oauthCallbacks = oauthCallbacks.receiveAsFlow(),
                    providerShareCallbacks =
                        providerShareCallbacks.receiveAsFlow(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceTriggerIntent(intent)
        handleOAuthIntent(intent)
        handleProviderShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            wakeTriggerReceiver,
            IntentFilter(WakeCaptureService.ACTION_VOICE_TRIGGERED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(wakeTriggerReceiver)
        super.onStop()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (
            event.repeatCount == 0 &&
            keyCode in WAKE_MEDIA_KEYS
        ) {
            voiceTriggers.trySend(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        voiceTriggers.close()
        oauthCallbacks.close()
        providerShareCallbacks.close()
        super.onDestroy()
    }

    private fun handleVoiceTriggerIntent(intent: Intent?) {
        if (intent?.action != WakeCaptureService.ACTION_OPEN_VOICE) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        acceptVoiceTrigger()
    }

    private fun acceptVoiceTrigger() {
        getSystemService(NotificationManager::class.java)
            .cancel(WakeCaptureService.DETECTED_NOTIFICATION_ID)
        voiceTriggers.trySend(Unit)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (
            intent.action == Intent.ACTION_VIEW &&
            data.scheme == "mochi" &&
            data.host == "oauth" &&
            data.path == "/notion"
        ) {
            oauthCallbacks.trySend(data.toString())
        }
    }

    private fun handleProviderShareIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (
            intent.action == Intent.ACTION_VIEW &&
            uri.scheme == "mochi" &&
            uri.host == "provider" &&
            uri.path == "/import"
        ) {
            providerShareCallbacks.trySend(uri.toString())
            intent.data = null
        }
    }

    private companion object {
        val WAKE_MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }
}
