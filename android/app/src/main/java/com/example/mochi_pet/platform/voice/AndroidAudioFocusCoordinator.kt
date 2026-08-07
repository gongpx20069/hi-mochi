package com.example.mochi_pet.platform.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

class AndroidAudioFocusCoordinator(
    context: Context,
) {
    private val audioManager =
        context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRequest: AudioFocusRequest? = null

    fun requestRecognitionFocus(onLost: () -> Unit): Boolean =
        request(
            gainType = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            onLost = onLost,
        )

    fun requestSpeechFocus(onLost: () -> Unit): Boolean =
        request(
            gainType = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            onLost = onLost,
        )

    fun abandon() {
        activeRequest?.let(audioManager::abandonAudioFocusRequest)
        activeRequest = null
    }

    private fun request(
        gainType: Int,
        onLost: () -> Unit,
    ): Boolean {
        abandon()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (
                change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                onLost()
            }
        }
        val request = AudioFocusRequest.Builder(gainType)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener, mainHandler)
            .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            activeRequest = request
        }
        return granted
    }
}
