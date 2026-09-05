package com.example.mochi_pet.core.voice

import kotlinx.coroutines.flow.StateFlow

data class VoiceRuntimeState(
    val recognitionAvailable: Boolean = false,
    val ttsReady: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val errorMessage: String? = null,
    val offerSpeechSettings: Boolean = false,
)

enum class VoiceInputTrigger {
    DIRECT,
    WAKE_WORD,
}

sealed interface VoiceRuntimeEvent {
    data class Availability(
        val recognitionAvailable: Boolean,
        val ttsReady: Boolean,
    ) : VoiceRuntimeEvent

    data object ListeningStarted : VoiceRuntimeEvent

    data class PartialTranscript(
        val text: String,
    ) : VoiceRuntimeEvent

    data object ListeningStopped : VoiceRuntimeEvent

    data class Failed(
        val message: String,
        val offerSpeechSettings: Boolean = false,
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
        onCompleted: () -> Unit = {},
    )

    fun stopSpeaking()
}

const val MAX_TRANSCRIPT_CHARS = 20_000
