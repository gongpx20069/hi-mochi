package com.example.mochi_pet.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String?,
    val startAt: Instant,
    val endAt: Instant?,
    val allDay: Boolean,
    val timezone: ZoneId,
    val recurrenceRule: String?,
    val location: String?,
    val reminderAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CalendarEventDraft(
    val title: String,
    val description: String? = null,
    val startAt: Instant,
    val endAt: Instant? = null,
    val allDay: Boolean = false,
    val timezone: ZoneId,
    val recurrenceRule: String? = null,
    val location: String? = null,
    val reminderAt: Instant? = null,
)

data class MochiTodo(
    val id: String,
    val content: String,
    val status: TodoStatus,
    val priority: TodoPriority,
    val scheduledDate: LocalDate?,
    val dueAt: Instant?,
    val reminderAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MochiTodoDraft(
    val content: String,
    val priority: TodoPriority = TodoPriority.NORMAL,
    val scheduledDate: LocalDate? = null,
    val dueAt: Instant? = null,
    val reminderAt: Instant? = null,
)

enum class TodoPriority {
    HIGH,
    NORMAL,
    LOW,
}
