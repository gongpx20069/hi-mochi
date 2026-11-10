package com.example.mochi_pet.core.journal

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

enum class JournalStatus { WAITING, GENERATING, READY, EMPTY, NO_DATA, FAILED, DELETED }

data class JournalSettings(val enabled: Boolean = false, val since: LocalDate? = null)

data class DailyJournal(
    val date: LocalDate,
    val zone: ZoneId,
    val status: JournalStatus,
    val title: String,
    val text: String,
    val error: String?,
    val updatedAt: Instant,
    val conversationCount: Int? = null,
    val userScheduleCount: Int? = null,
    val taskCount: Int? = null,
    val generatedAt: Instant? = null,
)

@Serializable
data class JournalDraft(val status: String, val title: String, val text: String) {
    fun validated(intermediate: Boolean = false): JournalDraft {
        require(status in setOf("written", "empty")) { "Invalid journal status" }
        require(title.length <= (if (intermediate) 80 else 50) && text.length <= 1_600) { "Journal response is too long" }
        require(if (status == "empty") title.isBlank() && text.isBlank() else title.isNotBlank() && text.isNotBlank()) {
            "Journal content does not match its status"
        }
        return copy(title = title.trim(), text = text.trim())
    }
}

@Serializable
data class JournalRecord(
    val time: String,
    val text: String,
    val title: String? = null,
    val speaker: String? = null,
    val recurring: Boolean? = null,
    val status: String? = null,
    val part: String? = null,
)

data class JournalEvidence(
    val conversations: List<JournalRecord>,
    val userSchedules: List<JournalRecord>,
    val mochiTasks: List<JournalRecord>,
)

interface DailyJournalController {
    suspend fun reconcile()
    suspend fun setEnabled(enabled: Boolean)
    suspend fun retry(date: LocalDate)
    suspend fun delete(date: LocalDate)
}
