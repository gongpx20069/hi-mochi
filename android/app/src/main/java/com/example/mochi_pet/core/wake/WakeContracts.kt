package com.example.mochi_pet.core.wake

import kotlinx.coroutines.flow.StateFlow

enum class WakeCaptureStatus {
    DISABLED,
    STARTING,
    LISTENING,
    PAUSED,
    ERROR,
}

enum class WakeKeyword(val phrase: String) {
    ENGLISH("Hi Mochi"),
    CHINESE("老莫老莫"),
}

data class WakeRuntimeState(
    val status: WakeCaptureStatus = WakeCaptureStatus.DISABLED,
    val lastTriggerSource: String? = null,
    val errorMessage: String? = null,
    val keyword: WakeKeyword = WakeKeyword.ENGLISH,
    val keywordOverride: WakeKeyword? = null,
) {
    val enabled: Boolean
        get() = status != WakeCaptureStatus.DISABLED
}

sealed interface WakeRuntimeEvent {
    data object Starting : WakeRuntimeEvent

    data object Listening : WakeRuntimeEvent

    data class Paused(
        val triggerSource: String? = null,
    ) : WakeRuntimeEvent

    data class Failed(
        val message: String,
    ) : WakeRuntimeEvent

    data object Disabled : WakeRuntimeEvent
}

fun reduceWakeRuntimeState(
    state: WakeRuntimeState,
    event: WakeRuntimeEvent,
): WakeRuntimeState =
    when (event) {
        WakeRuntimeEvent.Starting -> state.copy(
            status = WakeCaptureStatus.STARTING,
            errorMessage = null,
        )
        WakeRuntimeEvent.Listening -> state.copy(
            status = WakeCaptureStatus.LISTENING,
            errorMessage = null,
        )
        is WakeRuntimeEvent.Paused -> state.copy(
            status = WakeCaptureStatus.PAUSED,
            lastTriggerSource =
                event.triggerSource ?: state.lastTriggerSource,
            errorMessage = null,
        )
        is WakeRuntimeEvent.Failed -> state.copy(
            status = WakeCaptureStatus.ERROR,
            errorMessage = event.message,
        )
        WakeRuntimeEvent.Disabled -> state.copy(
            status = WakeCaptureStatus.DISABLED, lastTriggerSource = null, errorMessage = null,
        )
    }

interface WakeRuntime {
    val state: StateFlow<WakeRuntimeState>

    val shouldBeEnabled: Boolean
        get() = true

    fun enable()

    fun disable()

    fun selectKeyword(keyword: WakeKeyword?)

    fun pause(onPaused: () -> Unit = {})

    fun resume()
}
