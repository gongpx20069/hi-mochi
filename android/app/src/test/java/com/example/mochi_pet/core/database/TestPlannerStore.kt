package com.example.mochi_pet.core.database

import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.OccurrenceScope
import java.time.Instant
import java.time.LocalDate

abstract class TestPlannerStore : PlannerStore {
    override suspend fun reminderDelivered(id: String): Boolean = error("Reminder receipts not provided by this test")
    override suspend fun recordReminder(id: String): Unit = error("Reminder receipts not provided by this test")
    override suspend fun updateCalendarOccurrence(requestedEvent: CalendarEvent, scope: OccurrenceScope, indefiniteRecurrence: Boolean): CalendarEvent =
        error("Occurrence editing not provided by this test")
    override suspend fun completeCalendarOccurrence(id: String, revision: Long): CalendarEvent =
        error("Occurrence completion not provided by this test")
    override suspend fun deleteCalendarOccurrence(id: String, revision: Long, scope: OccurrenceScope): Unit =
        error("Occurrence deletion not provided by this test")
    override suspend fun listCalendarReminders(start: Instant, end: Instant): List<CalendarEvent> =
        error("Reminders not provided by this test")
    override suspend fun promoteTodo(todo: MochiTodo, draft: CalendarEventDraft): CalendarEvent =
        error("Promotion not provided by this test")
    override suspend fun listTodosInRange(start: LocalDate, end: LocalDate): List<MochiTodo> =
        generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }.toList().flatMap { listTodosForDate(it) }
}
