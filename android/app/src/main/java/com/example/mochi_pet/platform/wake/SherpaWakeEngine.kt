package com.example.mochi_pet.platform.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import com.example.mochi_pet.core.wake.WakeKeyword

class SherpaWakeEngine(
    private val context: Context,
    private val onWakeDetected: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val recording = AtomicBoolean(false)
    private var keywordSpotter: KeywordSpotter? = null
    private var keywordStream: OnlineStream? = null
    private var vad: Vad? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var recordingThread: Thread? = null
    private var lastTriggerAtMillis = 0L

    @Synchronized
    fun initialize() {
        if (keywordSpotter != null && vad != null) {
            return
        }
        val models = WakeModelInstaller(context).install()
        keywordSpotter = createKeywordSpotter(models)
        vad = Vad(
            null,
            VadModelConfig(
                SileroVadModelConfig(
                    models.vad.path,
                    VAD_THRESHOLD,
                    VAD_MIN_SILENCE_SECONDS,
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
    }

    @Synchronized
    fun start() {
        if (recording.get()) {
            return
        }
        check(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        ) {
            "Microphone permission is required for wake word"
        }
        val spotter = checkNotNull(keywordSpotter) {
            "Wake word engine is not initialized"
        }
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            channel,
            encoding,
        )
        check(minBufferBytes > 0) {
            "No valid microphone buffer is available"
        }
        val chunkSamples = SAMPLE_RATE * CHUNK_MILLIS / 1_000
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            channel,
            encoding,
            maxOf(minBufferBytes, chunkSamples * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Microphone failed to initialize"
        }
        echoCanceler =
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            } else {
                null
            }

        val stream = spotter.createStream("")
        vad?.reset()
        try {
            recorder.startRecording()
        } catch (error: IllegalStateException) {
            stream.release()
            recorder.release()
            throw error
        }
        audioRecord = recorder
        keywordStream = stream
        recording.set(true)
        recordingThread = Thread(
            { readLoop(recorder, spotter, stream, chunkSamples) },
            "MochiWakeCapture",
        ).apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        recording.set(false)
        audioRecord?.let { recorder ->
            try {
                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }
            } catch (error: IllegalStateException) {
                onFailure("Wake microphone could not stop cleanly")
            }
        }
        val thread = recordingThread
        recordingThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_MILLIS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        keywordStream?.release()
        keywordStream = null
        echoCanceler?.release()
        echoCanceler = null
        audioRecord?.release()
        audioRecord = null
    }

    override fun close() {
        stop()
        keywordSpotter?.release()
        keywordSpotter = null
        vad?.release()
        vad = null
    }

    private fun readLoop(
        recorder: AudioRecord,
        spotter: KeywordSpotter,
        stream: OnlineStream,
        chunkSamples: Int,
    ) {
        val pcm = ShortArray(chunkSamples)
        var failureMessage: String? = null
        try {
            while (recording.get()) {
                val count = recorder.read(pcm, 0, pcm.size)
                if (count < 0) {
                    failureMessage = "Wake microphone read failed"
                    break
                }
                if (count == 0) {
                    continue
                }
                val samples = FloatArray(count) { index ->
                    pcm[index] / 32768.0f
                }
                vad?.let { currentVad ->
                    currentVad.acceptWaveform(samples)
                    while (!currentVad.empty()) {
                        currentVad.pop()
                    }
                }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (recording.get() && spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream)
                        .keyword
                        ?.trim()
                        .orEmpty()
                    if (keyword.isNotEmpty()) {
                        spotter.reset(stream)
                        emitWake(keyword)
                    }
                }
            }
        } catch (error: RuntimeException) {
            if (recording.get()) {
                failureMessage = "Wake native audio processing failed"
            }
        } finally {
            if (recording.get()) {
                stop()
            }
            failureMessage?.let(onFailure)
        }
    }

    private fun emitWake(keyword: String) {
        if (!readWakeKeyword(context).matches(keyword)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (
            lastTriggerAtMillis > 0 &&
            now - lastTriggerAtMillis < TRIGGER_DEBOUNCE_MILLIS
        ) {
            return
        }
        lastTriggerAtMillis = now
        onWakeDetected(keyword)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MILLIS = 100
        const val STOP_JOIN_MILLIS = 500L
        const val TRIGGER_DEBOUNCE_MILLIS = 2_000L
        const val VAD_THRESHOLD = 0.5f
        const val VAD_MIN_SILENCE_SECONDS = 1.2f
        const val VAD_MIN_SPEECH_SECONDS = 0.25f
        const val VAD_WINDOW_SIZE = 512
        const val VAD_MAX_SPEECH_SECONDS = 30.0f
    }
}

/** Consumes the STT recorder's samples; never opens a second microphone. */
internal class BriefingKeywordDetector(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val spotter = createKeywordSpotter(WakeModelInstaller(context).install())
    private var stream = spotter.createStream("")

    @Synchronized fun reset() {
        stream.release()
        stream = spotter.createStream("")
    }

    @Synchronized fun accept(samples: ShortArray, count: Int): Boolean {
        stream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16_000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotBlank()) {
                spotter.reset(stream)
                if (readWakeKeyword(applicationContext).matches(keyword)) return true
            }
        }
        return false
    }

    @Synchronized override fun close() {
        stream.release()
        spotter.release()
    }
}

private fun createKeywordSpotter(models: WakeModelFiles): KeywordSpotter = KeywordSpotter(
    null,
    KeywordSpotterConfig(
        FeatureConfig(16_000, 80, 0.0f),
        OnlineModelConfig(
            OnlineTransducerModelConfig(models.encoder.path, models.decoder.path, models.joiner.path),
            OnlineParaformerModelConfig(), OnlineZipformer2CtcModelConfig(),
            OnlineNeMoCtcModelConfig(), OnlineToneCtcModelConfig(),
            models.tokens.path, 1, false, "cpu", "", "phone+ppinyin", "",
        ),
        4, models.keywords.path, 1.5f, 0.25f, 1,
    ),
)

internal fun WakeKeyword.tokens(): String = when (this) {
    WakeKeyword.ENGLISH -> "HH AY1 M OW1 CH IY0 @Hi_Mochi"
    WakeKeyword.CHINESE -> "l ǎo m ò l ǎo m ò @老莫老莫"
}

// Native custom streams append to the default keyword graph rather than replacing it.
internal fun WakeKeyword.matches(result: String): Boolean = result == phrase.replace(' ', '_')

private data class WakeModelFiles(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val keywords: File,
    val vad: File,
)

private class WakeModelInstaller(
    private val context: Context,
) {
    fun install(): WakeModelFiles = synchronized(WakeModelInstaller::class.java) {
        val directory = File(context.filesDir, MODEL_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create wake model directory")
        }
        val encoder = copyAsset(directory, ENCODER, ENCODER_BYTES)
        val decoder = copyAsset(directory, DECODER, DECODER_BYTES)
        val joiner = copyAsset(directory, JOINER, JOINER_BYTES)
        val tokens = copyAsset(directory, TOKENS, TOKENS_BYTES)
        val vad = copyAsset(directory, VAD, VAD_BYTES)
        val keywords = File(directory, "keywords.txt")
        val keywordText = WakeKeyword.entries.joinToString("\n", postfix = "\n") { it.tokens() }
        if (
            !keywords.isFile ||
            keywords.readText() != keywordText
        ) {
            keywords.writeText(keywordText)
        }
        val legacy = File(context.filesDir, "wake-model-v1")
        listOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            TOKENS, VAD, "keywords.txt",
        ).forEach { name ->
            val old = File(legacy, name)
            if (old.exists() && !old.delete()) throw IOException("Failed to remove obsolete wake model")
        }
        if (legacy.isDirectory && legacy.list()?.isEmpty() == true && !legacy.delete()) {
            throw IOException("Failed to remove obsolete wake model directory")
        }
        WakeModelFiles(
            encoder = encoder,
            decoder = decoder,
            joiner = joiner,
            tokens = tokens,
            keywords = keywords,
            vad = vad,
        )
    }

    private fun copyAsset(
        directory: File,
        name: String,
        expectedBytes: Long,
    ): File {
        val target = File(directory, name)
        if (target.isFile && target.length() == expectedBytes) {
            return target
        }
        target.delete()
        val temporary = File(directory, "$name.tmp")
        context.assets.open("wake/$name").use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        if (temporary.length() != expectedBytes) {
            temporary.delete()
            throw IOException("Wake model asset size mismatch: $name")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Failed to install wake model: $name")
        }
        return target
    }

    private companion object {
        const val MODEL_DIRECTORY = "wake-model-v2"
        const val ENCODER =
            "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
        const val DECODER =
            "decoder-epoch-13-avg-2-chunk-16-left-64.onnx"
        const val JOINER =
            "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
        const val TOKENS = "tokens.txt"
        const val VAD = "silero_vad.onnx"
        const val ENCODER_BYTES = 4_599_656L
        const val DECODER_BYTES = 759_829L
        const val JOINER_BYTES = 86_629L
        const val TOKENS_BYTES = 1_928L
        const val VAD_BYTES = 643_854L
    }
}
