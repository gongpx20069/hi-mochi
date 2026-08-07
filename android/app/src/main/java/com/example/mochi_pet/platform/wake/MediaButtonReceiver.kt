package com.example.mochi_pet.platform.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
            return
        }
        val event = IntentCompat.getParcelableExtra(
            intent,
            Intent.EXTRA_KEY_EVENT,
            KeyEvent::class.java,
        ) ?: return
        if (
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount != 0 ||
            event.keyCode !in WAKE_MEDIA_KEYS
        ) {
            return
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, WakeCaptureService::class.java)
                .setAction(WakeCaptureService.ACTION_MEDIA_TRIGGER),
        )
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
