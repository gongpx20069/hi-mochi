package com.example.mochi_pet.platform.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.mochi_pet.core.settings.AppLanguage
import com.example.mochi_pet.core.settings.SpeechRuntimeConfig
import com.example.mochi_pet.core.settings.SpeechSettingsRepository
import com.example.mochi_pet.core.voice.MAX_TRANSCRIPT_CHARS
import com.example.mochi_pet.core.voice.VoiceRuntime
import com.example.mochi_pet.core.voice.VoiceRuntimeEvent
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import com.example.mochi_pet.core.voice.reduceVoiceRuntimeState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AndroidVoiceRuntime(
    context: Context,
    private val speechSettingsRepository: SpeechSettingsRepository,
) : VoiceRuntime, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioFocus = AndroidAudioFocusCoordinator(applicationContext)
    private val mutableState = MutableStateFlow(
        VoiceRuntimeState(
            recognitionAvailable = recognitionAvailable(applicationContext),
        ),
    )
    private var finalTranscriptCallback: ((String) -> Unit)? = null
    private var noResultCallback: (() -> Unit)? = null
    private var speechCompletionCallback: (() -> Unit)? = null
    private var activeUtteranceId: String? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var latestPartialTranscript = ""
    private var speechCapture: RecordedSpeechCapture? = null
    private var iflytekLiveSession: IFlytekLiveSpeechSession? = null
    private var transcriptionJob: Job? = null
    private var interactionVersion = 0L

    override val state: StateFlow<VoiceRuntimeState> = mutableState.asStateFlow()

    init {
        mainHandler.post {
            if (mutableState.value.recognitionAvailable) {
                speechRecognizer = createSpeechRecognizer(applicationContext)
                    .apply { setRecognitionListener(RecognitionCallbacks()) }
            }
            textToSpeech = TextToSpeech(applicationContext) { status ->
                val languageStatus = if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.setLanguage(
                        AppLanguage.resolveContentLocale(),
                    )
                } else {
                    TextToSpeech.LANG_NOT_SUPPORTED
                }
                val ready =
                    status == TextToSpeech.SUCCESS &&
                        languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                        languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
                dispatch(
                    VoiceRuntimeEvent.Availability(
                        recognitionAvailable =
                            mutableState.value.recognitionAvailable,
                        ttsReady = ready,
                    ),
                )
                textToSpeech?.setOnUtteranceProgressListener(
                    SpeechProgressCallbacks(),
                )
            }
        }
    }

    override fun startListening(
        onFinalTranscript: (String) -> Unit,
        onNoResult: () -> Unit,
    ) {
        mainHandler.post {
            if (
                !audioFocus.requestRecognitionFocus {
                    mainHandler.post {
                        cancelRecognition()
                        finishRecognition(
                            errorMessage = "Microphone audio focus was lost",
                        )
                    }
                }
            ) {
                dispatch(
                    VoiceRuntimeEvent.Failed(
                        "Microphone audio focus is unavailable",
                    ),
                )
                onNoResult()
                return@post
            }
            cancelRecognition()
            textToSpeech?.stop()
            finalTranscriptCallback = onFinalTranscript
            noResultCallback = onNoResult
            latestPartialTranscript = ""
            val version = ++interactionVersion
            dispatch(VoiceRuntimeEvent.ListeningStarted)
            transcriptionJob = scope.launch {
                val config = try {
                    speechSettingsRepository.loadRuntimeConfig()
                } catch (error: IllegalStateException) {
                    mainHandler.post {
                        if (version == interactionVersion) {
                            finishRecognition(
                                errorMessage =
                                    error.message
                                        ?: "Speech settings are incomplete",
                                offerSpeechSettings = true,
                            )
                        }
                    }
                    return@launch
                }
                mainHandler.post {
                    if (version != interactionVersion) {
                        return@post
                    }
                    when (config) {
                        SpeechRuntimeConfig.System ->
                            startSystemRecognition()
                        is SpeechRuntimeConfig.IFlytek ->
                            startIFlytekLiveCapture(config, version)
                        is SpeechRuntimeConfig.Azure ->
                            startCloudCapture(config, version)
                    }
                }
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            interactionVersion += 1
            cancelRecognition()
            finalTranscriptCallback = null
            noResultCallback = null
            latestPartialTranscript = ""
            audioFocus.abandon()
            dispatch(VoiceRuntimeEvent.ListeningStopped)
        }
    }

    override fun speak(
        text: String,
        onCompleted: () -> Unit,
    ) {
        val bounded = text.trim().take(
            minOf(
                MAX_TRANSCRIPT_CHARS,
                TextToSpeech.getMaxSpeechInputLength(),
            ),
        )
        if (bounded.isEmpty()) {
            onCompleted()
            return
        }
        mainHandler.post {
            val tts = textToSpeech
            if (!mutableState.value.ttsReady || tts == null) {
                onCompleted()
                return@post
            }
            val languageStatus = tts.setLanguage(
                AppLanguage.resolveContentLocale(),
            )
            if (
                languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                onCompleted()
                return@post
            }
            if (
                !audioFocus.requestSpeechFocus {
                    mainHandler.post {
                        textToSpeech?.stop()
                        finishSpeech()
                    }
                }
            ) {
                onCompleted()
                return@post
            }
            val utteranceId = UUID.randomUUID().toString()
            activeUtteranceId = utteranceId
            speechCompletionCallback = onCompleted
            val result = tts.speak(
                bounded,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId,
            )
            if (result == TextToSpeech.ERROR) {
                finishSpeech()
            }
        }
    }

    override fun stopSpeaking() {
        mainHandler.post {
            textToSpeech?.stop()
            finishSpeech(invokeCompletion = false)
        }
    }

    override fun close() {
        mainHandler.post {
            interactionVersion += 1
            finalTranscriptCallback = null
            noResultCallback = null
            finishSpeech(invokeCompletion = false)
            audioFocus.abandon()
            cancelRecognition()
            speechRecognizer?.destroy()
            speechRecognizer = null
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            dispatch(VoiceRuntimeEvent.ListeningStopped)
            scope.cancel()
        }
    }

    private fun dispatch(event: VoiceRuntimeEvent) {
        mutableState.update { reduceVoiceRuntimeState(it, event) }
    }

    private fun startSystemRecognition() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            finishRecognition(
                errorMessage =
                    "Android speech recognition is unavailable. " +
                        "You can set up iFlytek or Azure Speech in Settings.",
                offerSpeechSettings = true,
            )
            return
        }
        try {
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        AppLanguage.resolveContentLocale().toLanguageTag(),
                    )
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        MINIMUM_SPEECH_LENGTH_MILLIS,
                    )
                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        POSSIBLY_COMPLETE_SILENCE_MILLIS,
                    )
                    putExtra(
                        RecognizerIntent
                            .EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        COMPLETE_SILENCE_MILLIS,
                    )
                    deviceRecognitionExtras(Build.MANUFACTURER)
                        .forEach { (name, value) ->
                            when (value) {
                                is Boolean -> putExtra(name, value)
                                is String -> putExtra(name, value)
                            }
                        }
                },
            )
        } catch (_: SecurityException) {
            finishRecognition(
                errorMessage = "Microphone permission is required",
            )
        }
    }

    private fun startCloudCapture(
        config: SpeechRuntimeConfig,
        version: Long,
    ) {
        val capture = RecordedSpeechCapture(
            context = applicationContext,
            onCaptured = { file ->
                mainHandler.post {
                    if (version != interactionVersion) {
                        file.delete()
                    } else {
                        speechCapture = null
                        transcribeCapturedSpeech(config, file, version)
                    }
                }
            },
            onFailure = { message ->
                mainHandler.post {
                    speechCapture = null
                    if (version == interactionVersion) {
                        finishRecognition(errorMessage = message)
                    }
                }
            },
        )
        speechCapture = capture
        try {
            capture.start()
        } catch (_: SecurityException) {
            speechCapture = null
            capture.close()
            finishRecognition(
                errorMessage = "Microphone permission is required",
            )
        }
    }

    private fun startIFlytekLiveCapture(
        config: SpeechRuntimeConfig.IFlytek,
        version: Long,
    ) {
        lateinit var capture: RecordedSpeechCapture
        val session = IFlytekLiveSpeechSession(
            appId = config.appId,
            apiKey = config.apiKey,
            apiSecret = config.apiSecret,
            locale = AppLanguage.resolveContentLocale(),
            onProviderEndpoint = {
                mainHandler.post {
                    if (version == interactionVersion) {
                        capture.completeFromProvider()
                    }
                }
            },
        )
        iflytekLiveSession = session
        capture = RecordedSpeechCapture(
            context = applicationContext,
            onAudioSamples = session::acceptPcm,
            vadMinimumSilenceSeconds =
                IFLYTEK_LOCAL_VAD_FALLBACK_SECONDS,
            noSpeechTimeoutSeconds =
                IFLYTEK_NO_SPEECH_TIMEOUT_SECONDS,
            onCaptured = { file ->
                mainHandler.post {
                    if (version != interactionVersion) {
                        file.delete()
                        session.close()
                    } else {
                        speechCapture = null
                        session.finish()
                        awaitIFlytekLiveResult(
                            config = config,
                            session = session,
                            file = file,
                            version = version,
                        )
                    }
                }
            },
            onFailure = { message ->
                mainHandler.post {
                    speechCapture = null
                    if (iflytekLiveSession === session) {
                        iflytekLiveSession = null
                    }
                    session.close()
                    if (version == interactionVersion) {
                        finishRecognition(errorMessage = message)
                    }
                }
            },
        )
        speechCapture = capture
        try {
            capture.start()
        } catch (_: SecurityException) {
            speechCapture = null
            iflytekLiveSession = null
            capture.close()
            session.close()
            finishRecognition(
                errorMessage = "Microphone permission is required",
            )
        }
    }

    private fun awaitIFlytekLiveResult(
        config: SpeechRuntimeConfig.IFlytek,
        session: IFlytekLiveSpeechSession,
        file: File,
        version: Long,
    ) {
        transcriptionJob = scope.launch {
            val endpointAt = SystemClock.elapsedRealtime()
            try {
                val transcript = try {
                    session.awaitResult().also {
                        Log.i(
                            SPEECH_LOG_TAG,
                            "transcription_completed provider=iflytek" +
                                " mode=live postEndpointLatencyMs=" +
                                (SystemClock.elapsedRealtime() - endpointAt),
                        )
                    }
                } catch (error: SpeechTranscriptionException) {
                    Log.w(
                        SPEECH_LOG_TAG,
                        "transcription_failed provider=iflytek mode=live" +
                            " retryable=${error.retryable}",
                    )
                    if (!error.retryable) {
                        throw error
                    }
                    transcribeWithRetry(
                        transcriber = IFlytekSpeechTranscriber(
                            appId = config.appId,
                            apiKey = config.apiKey,
                            apiSecret = config.apiSecret,
                        ),
                        file = file,
                        providerName = "iflytek",
                        attemptCount =
                            MAX_CLOUD_RECOGNITION_ATTEMPTS - 1,
                        attemptOffset = 1,
                    )
                }
                mainHandler.post {
                    if (version == interactionVersion) {
                        completeRecognition(transcript)
                    }
                }
            } catch (_: CancellationException) {
                Unit
            } catch (error: SpeechTranscriptionException) {
                mainHandler.post {
                    if (version == interactionVersion) {
                        finishRecognition(
                            errorMessage = error.message
                                ?: "Speech recognition failed",
                            offerSpeechSettings = true,
                        )
                    }
                }
            } finally {
                file.delete()
                session.close()
                mainHandler.post {
                    if (iflytekLiveSession === session) {
                        iflytekLiveSession = null
                    }
                }
            }
        }
    }

    private fun transcribeCapturedSpeech(
        config: SpeechRuntimeConfig,
        file: File,
        version: Long,
    ) {
        transcriptionJob = scope.launch {
            try {
                val transcriber = when (config) {
                    SpeechRuntimeConfig.System ->
                        error("System recognition does not use captured audio")
                    is SpeechRuntimeConfig.IFlytek ->
                        IFlytekSpeechTranscriber(
                            appId = config.appId,
                            apiKey = config.apiKey,
                            apiSecret = config.apiSecret,
                        )
                    is SpeechRuntimeConfig.Azure ->
                        AzureSpeechTranscriber(
                            endpoint = config.endpoint,
                            apiKey = config.apiKey,
                        )
                }
                val providerName = when (config) {
                    SpeechRuntimeConfig.System -> "system"
                    is SpeechRuntimeConfig.IFlytek -> "iflytek"
                    is SpeechRuntimeConfig.Azure -> "azure"
                }
                Log.i(
                    SPEECH_LOG_TAG,
                    "transcription_queued provider=$providerName audioMs=" +
                        pcmDurationMillis(file),
                )
                val transcript = transcribeWithRetry(
                    transcriber = transcriber,
                    file = file,
                    providerName = providerName,
                )
                mainHandler.post {
                    if (version == interactionVersion) {
                        completeRecognition(transcript)
                    }
                }
            } catch (_: CancellationException) {
                Unit
            } catch (error: SpeechTranscriptionException) {
                mainHandler.post {
                    if (version == interactionVersion) {
                        finishRecognition(
                            errorMessage = error.message
                                ?: "Speech recognition failed",
                            offerSpeechSettings = true,
                        )
                    }
                }
            } finally {
                file.delete()
            }
        }
    }

    private suspend fun transcribeWithRetry(
        transcriber: CloudSpeechTranscriber,
        file: File,
        providerName: String,
        attemptCount: Int = MAX_CLOUD_RECOGNITION_ATTEMPTS,
        attemptOffset: Int = 0,
    ): String {
        var lastError: SpeechTranscriptionException? = null
        repeat(attemptCount) { index ->
            val attemptNumber = attemptOffset + index + 1
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val transcript = transcriber.transcribe(
                    pcmFile = file,
                    locale = AppLanguage.resolveContentLocale(),
                )
                Log.i(
                    SPEECH_LOG_TAG,
                    "transcription_completed provider=$providerName" +
                        " attempt=$attemptNumber latencyMs=" +
                        (SystemClock.elapsedRealtime() - startedAt),
                )
                return transcript
            } catch (error: SpeechTranscriptionException) {
                Log.w(
                    SPEECH_LOG_TAG,
                    "transcription_failed provider=$providerName" +
                        " attempt=$attemptNumber latencyMs=" +
                        (SystemClock.elapsedRealtime() - startedAt) +
                        " retryable=${error.retryable}",
                )
                lastError = error
                if (
                    !error.retryable ||
                    index == attemptCount - 1
                ) {
                    throw error
                }
                delay(cloudRetryDelayMillis(index + 1))
            }
        }
        throw checkNotNull(lastError)
    }

    private fun finishRecognition(
        errorMessage: String? = null,
        offerSpeechSettings: Boolean = false,
    ) {
        val noResult = noResultCallback
        cancelRecognition()
        finalTranscriptCallback = null
        noResultCallback = null
        latestPartialTranscript = ""
        audioFocus.abandon()
        if (errorMessage == null) {
            dispatch(VoiceRuntimeEvent.ListeningStopped)
        } else {
            dispatch(
                VoiceRuntimeEvent.Failed(
                    message = errorMessage,
                    offerSpeechSettings = offerSpeechSettings,
                ),
            )
        }
        noResult?.invoke()
    }

    private fun completeRecognition(transcript: String) {
        val callback = finalTranscriptCallback
        val noResult = noResultCallback
        cancelRecognition()
        finalTranscriptCallback = null
        noResultCallback = null
        latestPartialTranscript = ""
        audioFocus.abandon()
        dispatch(VoiceRuntimeEvent.ListeningStopped)
        if (transcript.isNotBlank()) {
            callback?.invoke(transcript.trim().take(MAX_TRANSCRIPT_CHARS))
        } else {
            noResult?.invoke()
        }
    }

    private fun cancelRecognition() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        speechCapture?.close()
        speechCapture = null
        iflytekLiveSession?.close()
        iflytekLiveSession = null
        speechRecognizer?.cancel()
    }

    private fun finishSpeech(invokeCompletion: Boolean = true) {
        val completion = speechCompletionCallback
        activeUtteranceId = null
        speechCompletionCallback = null
        audioFocus.abandon()
        if (invokeCompletion) {
            completion?.invoke()
        }
    }

    private inner class RecognitionCallbacks : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (
                finalTranscriptCallback == null &&
                !mutableState.value.isListening
            ) {
                return
            }
            if (
                error in RECOVERABLE_NO_MATCH_ERRORS &&
                latestPartialTranscript.isNotBlank()
            ) {
                completeRecognition(latestPartialTranscript)
            } else {
                finishRecognition(
                    errorMessage = systemRecognitionErrorMessage(error),
                    offerSpeechSettings =
                        error in SYSTEM_PROVIDER_GUIDANCE_ERRORS,
                )
            }
        }

        override fun onResults(results: Bundle?) {
            val transcript = preferredRecognitionTranscript(
                finalCandidates = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                ),
                latestPartialTranscript = latestPartialTranscript,
            )
            completeRecognition(transcript)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val transcript = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.take(MAX_TRANSCRIPT_CHARS)
                .orEmpty()
            if (transcript.isNotEmpty()) {
                latestPartialTranscript = transcript
            }
            dispatch(VoiceRuntimeEvent.PartialTranscript(transcript))
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

    private inner class SpeechProgressCallbacks : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId == activeUtteranceId) {
                mainHandler.post { finishSpeech() }
            }
        }

        @Deprecated("Deprecated by Android")
        override fun onError(utteranceId: String?) {
            handleSpeechError(utteranceId)
        }

        override fun onError(
            utteranceId: String?,
            errorCode: Int,
        ) {
            handleSpeechError(utteranceId)
        }

        private fun handleSpeechError(utteranceId: String?) {
            if (utteranceId == activeUtteranceId) {
                mainHandler.post { finishSpeech() }
            }
        }
    }

    private companion object {
        const val SPEECH_LOG_TAG = "MochiSpeech"
        const val IFLYTEK_LOCAL_VAD_FALLBACK_SECONDS = 1.5f
        const val IFLYTEK_NO_SPEECH_TIMEOUT_SECONDS = 3.0f
        const val MINIMUM_SPEECH_LENGTH_MILLIS = 1_000L
        const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 1_500L
        const val COMPLETE_SILENCE_MILLIS = 2_500L
        const val MAX_CLOUD_RECOGNITION_ATTEMPTS = 3
        val RECOVERABLE_NO_MATCH_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )
        val SYSTEM_PROVIDER_GUIDANCE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )
    }
}

private fun pcmDurationMillis(file: File): Long =
    file.length() * 1_000L / (16_000L * 2L)

private fun recognitionAvailable(context: Context): Boolean =
    SpeechRecognizer.isRecognitionAvailable(context) ||
        (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        )

private fun createSpeechRecognizer(context: Context): SpeechRecognizer =
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    ) {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

internal fun deviceRecognitionExtras(
    manufacturer: String,
): Map<String, Any> =
    if (manufacturer.equals("Xiaomi", ignoreCase = true)) {
        mapOf(
            XIAOMI_USE_LOCAL_EXTRA to true,
            XIAOMI_SCENE_EXTRA to "default",
        )
    } else {
        emptyMap()
    }

internal fun preferredRecognitionTranscript(
    finalCandidates: List<String>?,
    latestPartialTranscript: String,
): String =
    finalCandidates
        ?.firstOrNull(String::isNotBlank)
        ?.trim()
        ?.take(MAX_TRANSCRIPT_CHARS)
        ?: latestPartialTranscript.trim().take(MAX_TRANSCRIPT_CHARS)

internal fun cloudRetryDelayMillis(attempt: Int): Long =
    when (attempt) {
        1 -> 800L
        else -> 2_000L
    }

private fun systemRecognitionErrorMessage(error: Int): String =
    when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Android speech recognition is busy. Try again."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> "Android speech recognition is unstable. Try again, or set up " +
            "iFlytek/Azure Speech in Settings for more reliable recognition."
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> "Android did not recognize that. Try speaking again, or set up " +
            "iFlytek/Azure Speech in Settings."
        else -> "Android speech recognition failed"
    }

private const val XIAOMI_USE_LOCAL_EXTRA = "useLocal"
private const val XIAOMI_SCENE_EXTRA = "scene"
