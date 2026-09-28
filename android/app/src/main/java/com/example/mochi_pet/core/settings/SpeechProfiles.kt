package com.example.mochi_pet.core.settings

const val SYSTEM_SPEECH_PROFILE_ID = "system-speech"

val SpeechProvider.connectionTitle: String
    get() = when (this) {
        SpeechProvider.SYSTEM -> "Android default"
        SpeechProvider.IFLYTEK -> "iFlytek Speech"
        SpeechProvider.AZURE -> "Azure Speech"
    }

data class SpeechProfileSummary(val id: String, val name: String, val settings: SpeechSettingsSummary)

data class SpeechProfilesSummary(
    val profiles: List<SpeechProfileSummary> = listOf(
        SpeechProfileSummary(SYSTEM_SPEECH_PROFILE_ID, SpeechProvider.SYSTEM.connectionTitle, SpeechSettingsSummary()),
    ),
    val activeId: String = SYSTEM_SPEECH_PROFILE_ID,
) {
    val active: SpeechProfileSummary get() = profiles.single { it.id == activeId }
}

data class SpeechProfileInput(val id: String? = null, val name: String, val settings: SpeechSettingsInput)
