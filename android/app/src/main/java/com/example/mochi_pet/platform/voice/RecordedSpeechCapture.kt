package com.example.mochi_pet.platform.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class RecordedSpeechCapture(
    private val context: Context,
    private val onAudioSamples: ((ShortArray, Int) -> Unit)? = null,
    private val vadMinimumSilenceSeconds: Float =
        VAD_MIN_SILENCE_SECONDS,
    private val noSpeechTimeoutSeconds: Float =
        NO_SPEECH_TIMEOUT_SECONDS,
    private val onCaptured: (File) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val recording = AtomicBoolean(false)
    private val providerEndpointReached = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null

    fun start() {
        check(recording.compareAndSet(false, true)) {
            "Speech capture is already active"
        }
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            recording.set(false)
            onFailure("Microphone permission is required")
            return
        }
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            recording.set(false)
            onFailure("No valid microphone buffer is available")
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, CHUNK_SAMPLES * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recording.set(false)
            recorder.release()
            onFailure("Microphone failed to initialize")
            return
        }
        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            recording.set(false)
            recorder.release()
            onFailure("Temporary speech storage is unavailable")
            return
        }
        directory.listFiles()?.forEach { staleFile ->
            if (staleFile.isFile) {
                staleFile.delete()
            }
        }
        val file = File(directory, "${UUID.randomUUID()}.pcm")
        outputFile = file
        audioRecord = recorder
        try {
            recorder.startRecording()
        } catch (error: IllegalStateException) {
            recording.set(false)
            audioRecord = null
            outputFile = null
            recorder.release()
            file.delete()
            onFailure("Microphone could not start")
            return
        }
        recordingThread = Thread(
            { captureLoop(recorder, file) },
            "MochiSpeechCapture",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        recording.set(false)
        audioRecord?.let { recorder ->
            try {
                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // The capture loop still owns final resource cleanup.
            }
        }
        val thread = recordingThread
        recordingThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        audioRecord?.release()
        audioRecord = null
        outputFile?.delete()
        outputFile = null
    }

    fun completeFromProvider() {
        providerEndpointReached.set(true)
        recording.set(false)
        audioRecord?.let { recorder ->
            try {
                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // The capture loop owns final error reporting and cleanup.
            }
        }
    }

    private fun captureLoop(
        recorder: AudioRecord,
        file: File,
    ) {
        val samples = ShortArray(CHUNK_SAMPLES)
        var detector: SherpaSpeechEndpointDetector? = null
        var captured = false
        var failure: String? = null
        val captureStartedAt = SystemClock.elapsedRealtime()
        var speechStartLogged = false
        try {
            val activeDetector = try {
                SherpaSpeechEndpointDetector(
                    context = context,
                    minimumSilenceSeconds =
                        vadMinimumSilenceSeconds,
                    noSpeechChunkLimit =
                        secondsToChunkCount(noSpeechTimeoutSeconds),
                )
            } catch (_: IOException) {
                failure = "Speech endpoint model is unavailable"
                null
            }
            detector = activeDetector
            if (activeDetector != null) {
                Log.i(SPEECH_LOG_TAG, "capture_started provider=cloud")
                BufferedOutputStream(file.outputStream()).use { output ->
                    while (recording.get()) {
                        val count = recorder.read(samples, 0, samples.size)
                        if (count < 0) {
                            failure = "Microphone audio error"
                            break
                        }
                        if (count == 0) {
                            continue
                        }
                        writePcm16(output, samples, count)
                        onAudioSamples?.invoke(samples, count)
                        val endpoint = activeDetector.accept(samples, count)
                        if (
                            activeDetector.speechStarted &&
                            !speechStartLogged
                        ) {
                            speechStartLogged = true
                            Log.i(
                                SPEECH_LOG_TAG,
                                "vad_speech_started elapsedMs=" +
                                    (SystemClock.elapsedRealtime() -
                                        captureStartedAt),
                            )
                        }
                        when (endpoint) {
                            SpeechEndpoint.CONTINUE -> Unit
                            SpeechEndpoint.COMPLETE -> {
                                captured = true
                                Log.i(
                                    SPEECH_LOG_TAG,
                                    "capture_completed totalMs=" +
                                        (SystemClock.elapsedRealtime() -
                                            captureStartedAt) +
                                        " endpointLagMs=" +
                                        activeDetector.endpointLagMillis,
                                )
                                break
                            }
                            SpeechEndpoint.NO_SPEECH -> {
                                Log.i(
                                    SPEECH_LOG_TAG,
                                    "capture_no_speech totalMs=" +
                                        (SystemClock.elapsedRealtime() -
                                            captureStartedAt),
                                )
                                failure = "No speech was detected"
                                break
                            }
                        }
                    }
                    if (providerEndpointReached.get()) {
                        captured = true
                        Log.i(
                            SPEECH_LOG_TAG,
                            "capture_completed providerEndpoint=true totalMs=" +
                                (SystemClock.elapsedRealtime() -
                                    captureStartedAt),
                        )
                    }
                }
            }
        } catch (_: IOException) {
            if (recording.get()) {
                failure = "Microphone audio error"
            }
        } catch (_: RuntimeException) {
            if (recording.get()) {
                failure = "Speech endpoint detection failed"
            }
        } catch (_: LinkageError) {
            if (recording.get()) {
                failure = "Speech endpoint detector is unavailable"
            }
        } finally {
            detector?.close()
            recording.set(false)
            try {
                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                failure = failure ?: "Microphone could not stop cleanly"
            }
            recorder.release()
            if (audioRecord === recorder) {
                audioRecord = null
            }
            if (captured && outputFile === file) {
                outputFile = null
                onCaptured(file)
            } else {
                file.delete()
                if (failure != null) {
                    onFailure(failure)
                }
            }
        }
    }
}

private class SherpaSpeechEndpointDetector(
    context: Context,
    minimumSilenceSeconds: Float,
    private val noSpeechChunkLimit: Int = NO_SPEECH_CHUNK_LIMIT,
    private val maximumChunkLimit: Int = MAXIMUM_CHUNK_LIMIT,
) : AutoCloseable {
    private val vad = Vad(
        null,
        VadModelConfig(
            SileroVadModelConfig(
                installSpeechVadModel(context).path,
                VAD_THRESHOLD,
                minimumSilenceSeconds,
                VAD_MIN_SPEECH_SECONDS,
                VAD_WINDOW_SIZE,
                VAD_MAX_SPEECH_SECONDS,
            ),
            TenVadModelConfig(),
            SAMPLE_RATE,
            1,
            "cpu",
            false,
        ),
    )
    private var totalChunks = 0
    private var totalSamples = 0
    var speechStarted = false
        private set
    var endpointLagMillis: Long? = null
        private set

    fun accept(
        samples: ShortArray,
        count: Int,
    ): SpeechEndpoint {
        totalChunks += 1
        totalSamples += count
        vad.acceptWaveform(
            FloatArray(count) { index ->
                samples[index] / 32768.0f
            },
        )
        val segment = if (vad.empty()) null else vad.front()
        if (vad.isSpeechDetected() || segment != null) {
            speechStarted = true
        }
        endpointLagMillis = segment?.let {
            (
                (totalSamples - it.start - it.samples.size)
                    .coerceAtLeast(0) *
                    1_000L
            ) / SAMPLE_RATE
        }
        val endpoint = resolveSpeechEndpoint(
            speechStarted = speechStarted,
            segmentComplete = segment != null,
            totalChunks = totalChunks,
            noSpeechChunkLimit = noSpeechChunkLimit,
            maximumChunkLimit = maximumChunkLimit,
        )
        while (!vad.empty()) {
            vad.pop()
        }
        return endpoint
    }

    override fun close() {
        vad.release()
    }
}

internal fun resolveSpeechEndpoint(
    speechStarted: Boolean,
    segmentComplete: Boolean,
    totalChunks: Int,
    noSpeechChunkLimit: Int,
    maximumChunkLimit: Int,
): SpeechEndpoint = when {
    speechStarted && segmentComplete -> SpeechEndpoint.COMPLETE
    speechStarted && totalChunks >= maximumChunkLimit ->
        SpeechEndpoint.COMPLETE
    !speechStarted && totalChunks >= noSpeechChunkLimit ->
        SpeechEndpoint.NO_SPEECH
    else -> SpeechEndpoint.CONTINUE
}

internal enum class SpeechEndpoint {
    CONTINUE,
    COMPLETE,
    NO_SPEECH,
}

private fun writePcm16(
    output: BufferedOutputStream,
    samples: ShortArray,
    count: Int,
) {
    val bytes = ByteArray(count * 2)
    repeat(count) { index ->
        val sample = samples[index].toInt()
        bytes[index * 2] = sample.toByte()
        bytes[index * 2 + 1] = (sample shr 8).toByte()
    }
    output.write(bytes)
}

private const val SAMPLE_RATE = 16_000
private const val CHUNK_MILLIS = 20
private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MILLIS / 1_000
private const val NO_SPEECH_TIMEOUT_SECONDS = 8.0f
private const val NO_SPEECH_CHUNK_LIMIT = 400
private const val MAXIMUM_CHUNK_LIMIT = 1_500
private const val STOP_JOIN_MILLIS = 500L
private const val CACHE_DIRECTORY = "speech-retry"
private const val SPEECH_LOG_TAG = "MochiSpeech"
private const val VAD_MODEL_DIRECTORY = "speech-vad-v1"
private const val VAD_MODEL_ASSET = "wake/silero_vad.onnx"
private const val VAD_MODEL_FILE = "silero_vad.onnx"
private const val VAD_MODEL_BYTES = 643_854L
private const val VAD_THRESHOLD = 0.5f
private const val VAD_MIN_SILENCE_SECONDS = 0.7f
private const val VAD_MIN_SPEECH_SECONDS = 0.15f
private const val VAD_WINDOW_SIZE = 512
private const val VAD_MAX_SPEECH_SECONDS = 30.0f

internal fun secondsToChunkCount(seconds: Float): Int =
    (seconds * 1_000 / CHUNK_MILLIS).toInt().coerceAtLeast(1)

private fun installSpeechVadModel(context: Context): File {
    val directory = File(context.filesDir, VAD_MODEL_DIRECTORY)
    if (!directory.exists() && !directory.mkdirs()) {
        throw IOException("Failed to create speech VAD model directory")
    }
    val target = File(directory, VAD_MODEL_FILE)
    if (target.isFile && target.length() == VAD_MODEL_BYTES) {
        return target
    }
    target.delete()
    val temporary = File(directory, "$VAD_MODEL_FILE.tmp")
    context.assets.open(VAD_MODEL_ASSET).use { input ->
        temporary.outputStream().use(input::copyTo)
    }
    if (!temporary.renameTo(target)) {
        temporary.delete()
        throw IOException("Failed to install speech VAD model")
    }
    return target
}
