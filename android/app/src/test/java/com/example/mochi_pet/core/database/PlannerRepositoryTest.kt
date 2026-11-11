package com.example.mochi_pet.core.database

import com.example.mochi_pet.core.database.dao.CalendarEventDao
import com.example.mochi_pet.core.database.dao.TodoDao
import com.example.mochi_pet.core.database.entity.CalendarEventEntity
import com.example.mochi_pet.core.database.entity.TodoEntity
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiTodoDraft
import com.example.mochi_pet.core.model.TodoPriority
import com.example.mochi_pet.core.model.TodoStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlannerRepositoryTest {
    private val now = Instant.parse("2026-07-31T10:00:00Z")
    private val database = androidx.room.Room.inMemoryDatabaseBuilder(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(), MochiDatabase::class.java,
    ).build()
    private val calendarDao = database.calendarEventDao()
    private val todoDao = database.todoDao()
    private val repository = PlannerRepository(
        calendarEventDao = calendarDao,
        todoDao = todoDao,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        idGenerator = SequenceIdGenerator(),
    )
    @After fun close() { database.close() }

    @Test
    fun `create event normalizes text and lists overlapping range`() = runBlocking {
        val event = repository.createCalendarEvent(
            CalendarEventDraft(
                title = "  Project review  ",
                description = "  Discuss native migration  ",
                startAt = Instant.parse("2026-08-05T01:00:00Z"),
                endAt = Instant.parse("2026-08-05T02:00:00Z"),
                timezone = ZoneId.of("Asia/Shanghai"),
            ),
        )

        val events = repository.listCalendarEvents(
            rangeStart = Instant.parse("2026-08-05T00:00:00Z"),
            rangeEnd = Instant.parse("2026-08-06T00:00:00Z"),
        )

        assertEquals("event_1", event.id)
        assertEquals("Project review", event.title)
        assertEquals("Discuss native migration", event.description)
        assertEquals(listOf(event), events)
        assertEquals(now, event.createdAt)
        assertEquals(now, event.updatedAt)
    }

    @Test
    fun `create event rejects an end before start`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createCalendarEvent(
                    CalendarEventDraft(
                        title = "Invalid",
                        startAt = Instant.parse("2026-08-05T02:00:00Z"),
                        endAt = Instant.parse("2026-08-05T01:00:00Z"),
                        timezone = ZoneId.of("UTC"),
                    ),
                )
            }

        }
        assertTrue(error.message.orEmpty().contains("end must be after start"))
    }

    @Test
    fun `recurrence expands completes one occurrence and preserves moved identity`(): Unit = runBlocking {
                val first = Instant.parse("2026-08-05T01:00:00Z")
                val event = repository.createCalendarEvent(CalendarEventDraft("Exercise", startAt = first,
                    timezone = ZoneOffset.UTC, recurrenceRule = "FREQ=DAILY;COUNT=3", reminderAt = first.minusSeconds(600)))
                val instances = repository.listCalendarEvents(first, first.plusSeconds(4 * 86400))
                assertEquals(3, instances.size)
                val done = repository.completeCalendarOccurrence(instances[1].occurrenceId, instances[1].revision)
                assertEquals(now, done.completedAt)
                val refreshed = repository.listCalendarEvents(first, first.plusSeconds(4 * 86400))
                assertEquals(1, refreshed.count { it.completedAt != null })
                assertEquals(2, repository.listCalendarReminders(first.minusSeconds(600), first.plusSeconds(4 * 86400)).size)
                val selected = refreshed[2]
                val moved = repository.updateCalendarOccurrence(selected.copy(startAt = selected.startAt.plusSeconds(3600)),
                    com.example.mochi_pet.core.model.OccurrenceScope.THIS)
                assertEquals(selected.occurrenceId, moved.occurrenceId)
                assertEquals(moved.startAt, repository.getCalendarEvent(moved.occurrenceId)?.startAt)
                assertEquals(3, repository.listCalendarEvents(first, first.plusSeconds(4 * 86400)).size)
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { repository.updateCalendarEvent(event.copy(title = "Stale edit")) }
                }
            }

            @Test
            fun `defaults are finite but explicit indefinite and legacy records remain indefinite`(): Unit = runBlocking {
                val draft = CalendarEventDraft("Daily", startAt = now, timezone = ZoneOffset.UTC, recurrenceRule = "FREQ=DAILY")
                assertTrue(repository.createCalendarEvent(draft).recurrenceRule!!.contains("UNTIL="))
                assertEquals("FREQ=DAILY", repository.createCalendarEvent(draft.copy(indefiniteRecurrence = true)).recurrenceRule)
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { repository.createCalendarEvent(draft.copy(recurrenceRule = "FREQ=DAILY;COUNT=2;UNTIL=20270101")) }
                }
            }

            @Test
            fun `month-end dates skip invalid months while last-day rule includes them`() = runBlocking {
                val first = Instant.parse("2026-01-31T09:00:00Z")
                repository.createCalendarEvent(CalendarEventDraft("31st", startAt = first, timezone = ZoneOffset.UTC,
                    recurrenceRule = "FREQ=MONTHLY;COUNT=3"))
                repository.createCalendarEvent(CalendarEventDraft("Last", startAt = first, timezone = ZoneOffset.UTC,
                    recurrenceRule = "FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=3"))
                val events = repository.listCalendarEvents(first, Instant.parse("2026-06-01T00:00:00Z"))
                assertEquals(listOf(1, 3, 5), events.filter { it.title == "31st" }.map { it.startAt.atZone(ZoneOffset.UTC).monthValue })
                assertEquals(listOf(1, 2, 3), events.filter { it.title == "Last" }.map { it.startAt.atZone(ZoneOffset.UTC).monthValue })
            }

            @Test
            fun `future edit retains previous instances and remaining count`() = runBlocking {
                val first = Instant.parse("2026-08-05T01:00:00Z")
                repository.createCalendarEvent(CalendarEventDraft("Original", startAt = first, timezone = ZoneOffset.UTC,
                    recurrenceRule = "FREQ=DAILY;COUNT=4"))
                val third = repository.listCalendarEvents(first, first.plusSeconds(5 * 86400))[2]
                repository.updateCalendarOccurrence(third.copy(title = "Future"),
                    com.example.mochi_pet.core.model.OccurrenceScope.FUTURE)
                val events = repository.listCalendarEvents(first, first.plusSeconds(5 * 86400))
                assertEquals(listOf("Original", "Original", "Future", "Future"), events.map { it.title })
            }
    @Test
    fun `todo without date defaults to today`() = runBlocking {
        val date = LocalDate.of(2026, 8, 5)
        val dated = repository.createTodo(
            MochiTodoDraft(
                content = "  Buy milk  ",
                priority = TodoPriority.HIGH,
                scheduledDate = date,
            ),
        )
        val defaulted = repository.createTodo(
            MochiTodoDraft(content = "Review notes"),
        )

        assertEquals(listOf(dated), repository.listTodosForDate(date))
        assertEquals(
            listOf(defaulted),
            repository.listTodosForDate(LocalDate.of(2026, 7, 31)),
        )
        assertTrue(repository.listUndatedTodos().isEmpty())
        assertNull(dated.completedAt)
    }

    @Test
    fun `adding recurrence applies finite default and changing frequency retains expiry`() = runBlocking {
        val event = repository.createCalendarEvent(CalendarEventDraft("Read", startAt = now, timezone = ZoneOffset.UTC))
        val recurring = repository.updateCalendarOccurrence(event.copy(recurrenceRule = "FREQ=DAILY"),
            com.example.mochi_pet.core.model.OccurrenceScope.THIS)
        val end = recurring.recurrenceRule!!.substringAfter("UNTIL=")
        val changed = repository.updateCalendarOccurrence(recurring.copy(recurrenceRule = "FREQ=WEEKLY"),
            com.example.mochi_pet.core.model.OccurrenceScope.ALL)
        assertTrue(changed.recurrenceRule!!.endsWith("UNTIL=$end"))
        val indefinite = repository.updateCalendarOccurrence(changed.copy(recurrenceRule = "FREQ=WEEKLY"),
            com.example.mochi_pet.core.model.OccurrenceScope.ALL, indefiniteRecurrence = true)
        assertEquals("FREQ=WEEKLY", indefinite.recurrenceRule)
    }

    @Test
    fun `leap-day default includes a real next occurrence and future edits keep completed instances`() = runBlocking {
        val leap = repository.createCalendarEvent(CalendarEventDraft("Leap", startAt = Instant.parse("2024-02-29T09:00:00Z"),
            timezone = ZoneOffset.UTC, recurrenceRule = "FREQ=YEARLY"))
        assertTrue(leap.recurrenceRule!!.endsWith("UNTIL=20280229"))
        val first = Instant.parse("2026-08-05T01:00:00Z")
        val daily = repository.createCalendarEvent(CalendarEventDraft("Exercise", startAt = first, timezone = ZoneOffset.UTC,
            recurrenceRule = "FREQ=DAILY;COUNT=4"))
        repository.completeCalendarOccurrence("${daily.id}@${first.plusSeconds(86400).toEpochMilli()}", daily.revision)
        val original = requireNotNull(repository.getCalendarEvent("${daily.id}@${first.plusSeconds(86400).toEpochMilli()}"))
        repository.updateCalendarOccurrence(original.copy(title = "New"), com.example.mochi_pet.core.model.OccurrenceScope.FUTURE)
        val items = repository.listCalendarEvents(first, first.plusSeconds(5 * 86400)).filter { it.title != "Leap" }
        assertEquals(4, items.size)
        assertEquals(1, items.count { it.completedAt != null })
        assertEquals(1, items.count { it.title == "Exercise" && it.completedAt != null })
    }

    @Test
    fun `complete todo persists completion state`() = runBlocking {
        val todo = repository.createTodo(
            MochiTodoDraft(content = "Ship native shell"),
        )

        val completed = repository.completeTodo(todo.id)

        assertEquals(TodoStatus.COMPLETED, completed.status)
        assertEquals(now, completed.completedAt)
        assertEquals(
            listOf(completed),
            repository.listTodosByStatus(TodoStatus.COMPLETED),
        )
        assertTrue(repository.listUndatedTodos().isEmpty())
    }

    @Test
    fun `moving a weekly future anchor changes its weekday without resetting expiry`() = runBlocking {
        val start = Instant.parse("2026-08-03T09:00:00Z")
        val event = repository.createCalendarEvent(CalendarEventDraft("Read", startAt = start, timezone = ZoneOffset.UTC,
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO;COUNT=3"))
        repository.updateCalendarOccurrence(event.copy(startAt = start.plusSeconds(86400)),
            com.example.mochi_pet.core.model.OccurrenceScope.FUTURE)
        val occurrences = repository.listCalendarEvents(start, start.plusSeconds(30 * 86400))
        assertEquals(3, occurrences.size)
        assertTrue(occurrences.all { it.startAt.atZone(ZoneOffset.UTC).dayOfWeek == java.time.DayOfWeek.TUESDAY })
    }

    @Test
    fun `todo reminder cannot occur after due time`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createTodo(
                    MochiTodoDraft(
                        content = "Invalid reminder",
                        dueAt = Instant.parse("2026-08-05T01:00:00Z"),
                        reminderAt = Instant.parse("2026-08-05T02:00:00Z"),
                    ),
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("reminder"))
    }
}

private class SequenceIdGenerator : PlannerIdGenerator {
    private var next = 1

    override fun nextId(prefix: String): String = "${prefix}_${next++}"
}
