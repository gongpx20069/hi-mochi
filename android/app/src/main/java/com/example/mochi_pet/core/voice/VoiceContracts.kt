package com.example.mochi_pet.core.voice

import com.example.mochi_pet.core.settings.SpeechProvider
import kotlinx.coroutines.flow.StateFlow

data class VoiceRuntimeState(
    val recognitionAvailable: Boolean = false,
    val ttsReady: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val errorMessage: String? = null,
    val offerSpeechSettings: Boolean = false,
    val playbackStage: SpeechPlaybackStage = SpeechPlaybackStage.IDLE,
    val diagnosticCode: String? = null,
)

enum class VoiceInputTrigger {
    DIRECT,
    WAKE_WORD,
}

enum class SpeechPurpose {
    REPLY,
    WAKE_ACKNOWLEDGEMENT,
    PREVIEW,
}

enum class SpeechPlaybackStage { IDLE, SYNTHESIZING, PLAYING }

enum class SpeechPlaybackResult {
    COMPLETED,
    FAILED,
}

sealed interface VoiceRuntimeEvent {
    data class Availability(
        val recognitionAvailable: Boolean,
        val ttsReady: Boolean,
    ) : VoiceRuntimeEvent

    data object ListeningStarted : VoiceRuntimeEvent
    data object SpeakingStarted : VoiceRuntimeEvent
    data object PlaybackStarted : VoiceRuntimeEvent
    data object SpeakingStopped : VoiceRuntimeEvent

    data class PartialTranscript(
        val text: String,
    ) : VoiceRuntimeEvent

    data object ListeningStopped : VoiceRuntimeEvent

    data class Failed(
        val message: String,
        val offerSpeechSettings: Boolean = false,
        val diagnosticCode: String? = null,
    ) : VoiceRuntimeEvent
}

fun reduceVoiceRuntimeState(
    state: VoiceRuntimeState,
    event: VoiceRuntimeEvent,
): VoiceRuntimeState =
    when (event) {
        is VoiceRuntimeEvent.Availability -> state.copy(
            recognitionAvailable = event.recognitionAvailable,
            ttsReady = event.ttsReady,
        )
        VoiceRuntimeEvent.ListeningStarted -> state.copy(
            isListening = true,
            partialTranscript = "",
            errorMessage = null,
            offerSpeechSettings = false,
        )
        VoiceRuntimeEvent.SpeakingStarted -> state.copy(
            errorMessage = null,
            offerSpeechSettings = false,
            diagnosticCode = null,
            playbackStage = SpeechPlaybackStage.SYNTHESIZING,
        )
        VoiceRuntimeEvent.PlaybackStarted -> state.copy(playbackStage = SpeechPlaybackStage.PLAYING)
        VoiceRuntimeEvent.SpeakingStopped -> state.copy(playbackStage = SpeechPlaybackStage.IDLE)
        is VoiceRuntimeEvent.PartialTranscript -> state.copy(
            partialTranscript = event.text.take(MAX_TRANSCRIPT_CHARS),
        )
        VoiceRuntimeEvent.ListeningStopped -> state.copy(
            isListening = false,
            partialTranscript = "",
        )
        is VoiceRuntimeEvent.Failed -> state.copy(
            isListening = false,
            partialTranscript = "",
            errorMessage = event.message,
            offerSpeechSettings = event.offerSpeechSettings,
            diagnosticCode = event.diagnosticCode,
            playbackStage = SpeechPlaybackStage.IDLE,
        )
    }

interface VoiceRuntime {
    val state: StateFlow<VoiceRuntimeState>

    fun startListening(
        onFinalTranscript: (String) -> Unit,
        onNoResult: () -> Unit = {},
    )

    fun stopListening()

    fun speak(
        text: String,
        purpose: SpeechPurpose = SpeechPurpose.REPLY,
        previewVoiceId: String? = null,
        onCompleted: (SpeechPlaybackResult) -> Unit = {},
    )

    suspend fun availableVoices(provider: SpeechProvider): List<SpeechVoice> =
        throw IllegalStateException("Voice catalog is unavailable")

    fun stopSpeaking()
}

const val MAX_TRANSCRIPT_CHARS = 20_000
