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

class PlannerRepositoryTest {
    private val now = Instant.parse("2026-07-31T10:00:00Z")
    private val calendarDao = FakeCalendarEventDao()
    private val todoDao = FakeTodoDao()
    private val repository = PlannerRepository(
        calendarEventDao = calendarDao,
        todoDao = todoDao,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        idGenerator = SequenceIdGenerator(),
    )

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

private class FakeCalendarEventDao : CalendarEventDao {
    private val events = linkedMapOf<String, CalendarEventEntity>()

    override suspend fun upsert(event: CalendarEventEntity) {
        events[event.id] = event
    }

    override suspend fun getById(id: String): CalendarEventEntity? = events[id]

    override suspend fun listOverlapping(
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): List<CalendarEventEntity> =
        events.values
            .filter { event ->
                event.startAtEpochMillis < rangeEndEpochMillis &&
                    (event.endAtEpochMillis ?: event.startAtEpochMillis + 1) >
                    rangeStartEpochMillis
            }
            .sortedWith(
                compareByDescending<CalendarEventEntity> { it.allDay }
                    .thenBy { it.startAtEpochMillis }
                    .thenBy { it.id },
            )

    override suspend fun delete(event: CalendarEventEntity) {
        events.remove(event.id)
    }
}

private class FakeTodoDao : TodoDao {
    private val todos = linkedMapOf<String, TodoEntity>()

    override suspend fun upsert(todo: TodoEntity) {
        todos[todo.id] = todo
    }

    override suspend fun getById(id: String): TodoEntity? = todos[id]

    override suspend fun listForDate(epochDay: Long): List<TodoEntity> =
        todos.values
            .filter { it.scheduledDateEpochDay == epochDay }
            .sortedWith(todoComparator)

    override suspend fun listActiveThroughDate(
        epochDay: Long,
    ): List<TodoEntity> =
        todos.values
            .filter {
                it.status == TodoStatus.ACTIVE.name &&
                    it.scheduledDateEpochDay != null &&
                    it.scheduledDateEpochDay <= epochDay
            }
            .sortedWith(
                compareBy<TodoEntity>(
                    { it.scheduledDateEpochDay },
                    { priorityOrder(it.priority) },
                    { it.dueAtEpochMillis },
                    { it.createdAtEpochMillis },
                ),
            )

    override suspend fun listUndated(status: String): List<TodoEntity> =
        todos.values
            .filter { it.scheduledDateEpochDay == null && it.status == status }
            .sortedWith(todoComparator)

    override suspend fun listByStatus(status: String): List<TodoEntity> =
        todos.values
            .filter { it.status == status }
            .sortedWith(todoComparator)

    override suspend fun delete(todo: TodoEntity) {
        todos.remove(todo.id)
    }

    private companion object {
        val todoComparator =
            compareBy<TodoEntity>(
                { priorityOrder(it.priority) },
                { it.scheduledDateEpochDay },
                { it.dueAtEpochMillis },
                { it.createdAtEpochMillis },
            )

        fun priorityOrder(priority: String): Int =
            when (priority) {
                TodoPriority.HIGH.name -> 0
                TodoPriority.NORMAL.name -> 1
                else -> 2
            }
    }
}
