package com.example.mochi_pet.platform.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface PcmSpeechPlayer {
    suspend fun play(audio: ByteArray)
}

internal fun speechPlaybackAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

internal class AndroidPcmSpeechPlayer : PcmSpeechPlayer {
    override suspend fun play(audio: ByteArray) {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SYNTHESIS_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimumBuffer = AudioTrack.getMinBufferSize(
            SYNTHESIS_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) throw SpeechSynthesisException(SynthesisFailure.PLAYBACK)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(speechPlaybackAudioAttributes())
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimumBuffer, 8_192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: UnsupportedOperationException) {
            throw SpeechSynthesisException(SynthesisFailure.PLAYBACK)
        }

        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw SpeechSynthesisException(SynthesisFailure.PLAYBACK)
            }
            val played = withTimeoutOrNull(
                audio.size * 1_000L / (SYNTHESIS_SAMPLE_RATE * 2) + 5_000,
            ) {
                track.play()
                var offset = 0
                while (offset < audio.size) {
                    currentCoroutineContext().ensureActive()
                    val written = track.write(
                        audio,
                        offset,
                        minOf(8_192, audio.size - offset),
                        AudioTrack.WRITE_NON_BLOCKING,
                    )
                    if (written < 0) throw SpeechSynthesisException(SynthesisFailure.PLAYBACK)
                    offset += written
                    if (written == 0) delay(10)
                }
                while (track.playbackHeadPosition.toLong() < audio.size / 2L) {
                    delay(10)
                }
                true
            }
            if (played != true) throw SpeechSynthesisException(SynthesisFailure.PLAYBACK)
        } catch (_: IllegalStateException) {
            throw SpeechSynthesisException(SynthesisFailure.PLAYBACK)
        } finally {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                track.flush()
            } finally {
                track.release()
            }
        }
    }
}
