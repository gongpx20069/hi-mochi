package com.example.mochi_pet.core.database

import com.example.mochi_pet.core.database.dao.CalendarEventDao
import com.example.mochi_pet.core.database.dao.TodoDao
import com.example.mochi_pet.core.database.entity.CalendarEventEntity
import com.example.mochi_pet.core.database.entity.TodoEntity
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.MochiTodoDraft
import com.example.mochi_pet.core.model.TodoPriority
import com.example.mochi_pet.core.model.TodoStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

fun interface PlannerIdGenerator {
    fun nextId(prefix: String): String
}

class UuidPlannerIdGenerator : PlannerIdGenerator {
    override fun nextId(prefix: String): String = "${prefix}_${UUID.randomUUID()}"
}

class PlannerNotFoundException(message: String) : IllegalStateException(message)

interface PlannerStore {
    suspend fun createCalendarEvent(draft: CalendarEventDraft): CalendarEvent

    suspend fun updateCalendarEvent(event: CalendarEvent): CalendarEvent

    suspend fun getCalendarEvent(id: String): CalendarEvent?

    suspend fun listCalendarEvents(
        rangeStart: Instant,
        rangeEnd: Instant,
    ): List<CalendarEvent>

    suspend fun deleteCalendarEvent(id: String)

    suspend fun createTodo(draft: MochiTodoDraft): MochiTodo

    suspend fun updateTodo(todo: MochiTodo): MochiTodo

    suspend fun getTodo(id: String): MochiTodo?

    suspend fun completeTodo(id: String): MochiTodo

    suspend fun listTodosForDate(date: LocalDate): List<MochiTodo>

    suspend fun listActiveTodosThroughDate(date: LocalDate): List<MochiTodo>

    suspend fun listUndatedTodos(
        status: TodoStatus = TodoStatus.ACTIVE,
    ): List<MochiTodo>

    suspend fun listTodosByStatus(status: TodoStatus): List<MochiTodo>

    suspend fun deleteTodo(id: String)
}

class PlannerRepository(
    private val calendarEventDao: CalendarEventDao,
    private val todoDao: TodoDao,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: PlannerIdGenerator = UuidPlannerIdGenerator(),
) : PlannerStore {
    override suspend fun createCalendarEvent(
        draft: CalendarEventDraft,
    ): CalendarEvent {
        val normalized = draft.normalized()
        validateCalendarEvent(normalized)
        val now = clock.instant()
        val event = CalendarEvent(
            id = idGenerator.nextId("event"),
            title = normalized.title,
            description = normalized.description,
            startAt = normalized.startAt,
            endAt = normalized.endAt,
            allDay = normalized.allDay,
            timezone = normalized.timezone,
            recurrenceRule = normalized.recurrenceRule,
            location = normalized.location,
            reminderAt = normalized.reminderAt,
            createdAt = now,
            updatedAt = now,
        )
        calendarEventDao.upsert(event.toEntity())
        return event
    }

    override suspend fun updateCalendarEvent(event: CalendarEvent): CalendarEvent {
        val existing = calendarEventDao.getById(event.id)
            ?: throw PlannerNotFoundException(
                "Calendar event not found: ${event.id}",
            )
        val normalized = event.normalized().copy(
            createdAt = Instant.ofEpochMilli(existing.createdAtEpochMillis),
            updatedAt = clock.instant(),
        )
        validateCalendarEvent(normalized)
        calendarEventDao.upsert(normalized.toEntity())
        return normalized
    }

    override suspend fun getCalendarEvent(id: String): CalendarEvent? =
        calendarEventDao.getById(id)?.toDomain()

    override suspend fun listCalendarEvents(
        rangeStart: Instant,
        rangeEnd: Instant,
    ): List<CalendarEvent> {
        require(rangeEnd.isAfter(rangeStart)) {
            "Calendar range end must be after range start"
        }
        return calendarEventDao.listOverlapping(
            rangeStart.toEpochMilli(),
            rangeEnd.toEpochMilli(),
        ).map(CalendarEventEntity::toDomain)
    }

    override suspend fun deleteCalendarEvent(id: String) {
        val existing = calendarEventDao.getById(id)
            ?: throw PlannerNotFoundException("Calendar event not found: $id")
        calendarEventDao.delete(existing)
    }

    override suspend fun createTodo(draft: MochiTodoDraft): MochiTodo {
        val normalized = draft.normalized().copy(
            scheduledDate = draft.scheduledDate ?: LocalDate.now(clock),
        )
        validateTodoDraft(normalized)
        val now = clock.instant()
        val todo = MochiTodo(
            id = idGenerator.nextId("todo"),
            content = normalized.content,
            status = TodoStatus.ACTIVE,
            priority = normalized.priority,
            scheduledDate = normalized.scheduledDate,
            dueAt = normalized.dueAt,
            reminderAt = normalized.reminderAt,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        todoDao.upsert(todo.toEntity())
        return todo
    }

    override suspend fun updateTodo(todo: MochiTodo): MochiTodo {
        val existing = todoDao.getById(todo.id)
            ?: throw PlannerNotFoundException("Todo not found: ${todo.id}")
        val normalized = todo.normalized().copy(
            createdAt = Instant.ofEpochMilli(existing.createdAtEpochMillis),
            updatedAt = clock.instant(),
        )
        validateTodo(normalized)
        todoDao.upsert(normalized.toEntity())
        return normalized
    }

    override suspend fun getTodo(id: String): MochiTodo? =
        todoDao.getById(id)?.toDomain()

    override suspend fun completeTodo(id: String): MochiTodo {
        val existing = (
            todoDao.getById(id)
                ?: throw PlannerNotFoundException("Todo not found: $id")
            ).toDomain()
        if (existing.status == TodoStatus.COMPLETED) {
            return existing
        }
        val now = clock.instant()
        val completed = existing.copy(
            status = TodoStatus.COMPLETED,
            completedAt = now,
            updatedAt = now,
        )
        todoDao.upsert(completed.toEntity())
        return completed
    }

    override suspend fun listTodosForDate(date: LocalDate): List<MochiTodo> =
        todoDao.listForDate(date.toEpochDay()).map(TodoEntity::toDomain)

    override suspend fun listActiveTodosThroughDate(
        date: LocalDate,
    ): List<MochiTodo> =
        todoDao.listActiveThroughDate(date.toEpochDay())
            .map(TodoEntity::toDomain)

    override suspend fun listUndatedTodos(
        status: TodoStatus,
    ): List<MochiTodo> =
        todoDao.listUndated(status.name).map(TodoEntity::toDomain)

    override suspend fun listTodosByStatus(status: TodoStatus): List<MochiTodo> =
        todoDao.listByStatus(status.name).map(TodoEntity::toDomain)

    override suspend fun deleteTodo(id: String) {
        val existing = todoDao.getById(id)
            ?: throw PlannerNotFoundException("Todo not found: $id")
        todoDao.delete(existing)
    }
}

private fun CalendarEventDraft.normalized(): CalendarEventDraft =
    copy(
        title = title.trim(),
        description = description.trimmedOrNull(),
        recurrenceRule = recurrenceRule.trimmedOrNull(),
        location = location.trimmedOrNull(),
    )

private fun CalendarEvent.normalized(): CalendarEvent =
    copy(
        title = title.trim(),
        description = description.trimmedOrNull(),
        recurrenceRule = recurrenceRule.trimmedOrNull(),
        location = location.trimmedOrNull(),
    )

private fun MochiTodoDraft.normalized(): MochiTodoDraft =
    copy(content = content.trim())

private fun MochiTodo.normalized(): MochiTodo = copy(content = content.trim())

private fun validateCalendarEvent(draft: CalendarEventDraft) {
    require(draft.title.isNotEmpty()) { "Calendar title must not be empty" }
    require(draft.title.length <= 200) { "Calendar title is too long" }
    require(draft.description == null || draft.description.length <= 4_000) {
        "Calendar description is too long"
    }
    require(draft.location == null || draft.location.length <= 500) {
        "Calendar location is too long"
    }
    require(
        draft.recurrenceRule == null || draft.recurrenceRule.length <= 1_000,
    ) {
        "Calendar recurrence rule is too long"
    }
    require(draft.endAt == null || draft.endAt.isAfter(draft.startAt)) {
        "Calendar end must be after start"
    }
    require(draft.reminderAt == null || !draft.reminderAt.isAfter(draft.startAt)) {
        "Calendar reminder must not be after start"
    }
}

private fun validateCalendarEvent(event: CalendarEvent) {
    validateCalendarEvent(
        CalendarEventDraft(
            title = event.title,
            description = event.description,
            startAt = event.startAt,
            endAt = event.endAt,
            allDay = event.allDay,
            timezone = event.timezone,
            recurrenceRule = event.recurrenceRule,
            location = event.location,
            reminderAt = event.reminderAt,
        ),
    )
}

private fun validateTodoDraft(draft: MochiTodoDraft) {
    require(draft.content.isNotEmpty()) { "Todo content must not be empty" }
    require(draft.content.length <= 500) { "Todo content is too long" }
    require(draft.reminderAt == null || draft.dueAt == null ||
        !draft.reminderAt.isAfter(draft.dueAt)) {
        "Todo reminder must not be after due time"
    }
}

private fun validateTodo(todo: MochiTodo) {
    validateTodoDraft(
        MochiTodoDraft(
            content = todo.content,
            priority = todo.priority,
            scheduledDate = todo.scheduledDate,
            dueAt = todo.dueAt,
            reminderAt = todo.reminderAt,
        ),
    )
    require(
        (todo.status == TodoStatus.COMPLETED) == (todo.completedAt != null),
    ) {
        "Todo completion status and completedAt must agree"
    }
}

private fun CalendarEvent.toEntity(): CalendarEventEntity =
    CalendarEventEntity(
        id = id,
        title = title,
        description = description,
        startAtEpochMillis = startAt.toEpochMilli(),
        endAtEpochMillis = endAt?.toEpochMilli(),
        allDay = allDay,
        timezoneId = timezone.id,
        recurrenceRule = recurrenceRule,
        location = location,
        reminderAtEpochMillis = reminderAt?.toEpochMilli(),
        createdAtEpochMillis = createdAt.toEpochMilli(),
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun CalendarEventEntity.toDomain(): CalendarEvent =
    CalendarEvent(
        id = id,
        title = title,
        description = description,
        startAt = Instant.ofEpochMilli(startAtEpochMillis),
        endAt = endAtEpochMillis?.let(Instant::ofEpochMilli),
        allDay = allDay,
        timezone = ZoneId.of(timezoneId),
        recurrenceRule = recurrenceRule,
        location = location,
        reminderAt = reminderAtEpochMillis?.let(Instant::ofEpochMilli),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

private fun MochiTodo.toEntity(): TodoEntity =
    TodoEntity(
        id = id,
        content = content,
        status = status.name,
        priority = priority.name,
        scheduledDateEpochDay = scheduledDate?.toEpochDay(),
        dueAtEpochMillis = dueAt?.toEpochMilli(),
        reminderAtEpochMillis = reminderAt?.toEpochMilli(),
        completedAtEpochMillis = completedAt?.toEpochMilli(),
        createdAtEpochMillis = createdAt.toEpochMilli(),
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun TodoEntity.toDomain(): MochiTodo =
    MochiTodo(
        id = id,
        content = content,
        status = enumValueOf(status),
        priority = enumValueOf(priority),
        scheduledDate = scheduledDateEpochDay?.let(LocalDate::ofEpochDay),
        dueAt = dueAtEpochMillis?.let(Instant::ofEpochMilli),
        reminderAt = reminderAtEpochMillis?.let(Instant::ofEpochMilli),
        completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)
