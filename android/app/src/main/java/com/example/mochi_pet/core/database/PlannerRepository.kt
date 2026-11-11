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
import com.example.mochi_pet.core.model.CalendarRecurrence
import com.example.mochi_pet.core.model.OccurrenceScope
import com.example.mochi_pet.core.model.occurrences
import com.example.mochi_pet.core.model.overlaps
import com.example.mochi_pet.core.model.withDefaultRecurrenceEnd
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import com.example.mochi_pet.core.model.checkPlannerRevision

fun interface PlannerIdGenerator {
    fun nextId(prefix: String): String
}

class UuidPlannerIdGenerator : PlannerIdGenerator {
    override fun nextId(prefix: String): String = "${prefix}_${UUID.randomUUID()}"
}

class PlannerNotFoundException(message: String) : IllegalStateException(message)

interface PlannerStore {
    val changes: Flow<Unit> get() = emptyFlow()

    suspend fun reminderDelivered(id: String): Boolean

    suspend fun recordReminder(id: String)

    suspend fun createCalendarEvent(draft: CalendarEventDraft): CalendarEvent

    suspend fun updateCalendarEvent(event: CalendarEvent, indefiniteRecurrence: Boolean = false): CalendarEvent

    suspend fun getCalendarEvent(id: String): CalendarEvent?

    suspend fun listCalendarEvents(
        rangeStart: Instant,
        rangeEnd: Instant,
    ): List<CalendarEvent>

    suspend fun deleteCalendarEvent(id: String)

    suspend fun updateCalendarOccurrence(requestedEvent: CalendarEvent, scope: OccurrenceScope, indefiniteRecurrence: Boolean = false): CalendarEvent

    suspend fun completeCalendarOccurrence(id: String, revision: Long): CalendarEvent

    suspend fun deleteCalendarOccurrence(id: String, revision: Long, scope: OccurrenceScope)

    suspend fun listCalendarReminders(start: Instant, end: Instant): List<CalendarEvent>

    suspend fun promoteTodo(todo: MochiTodo, draft: CalendarEventDraft): CalendarEvent

    suspend fun listTodosInRange(start: LocalDate, end: LocalDate): List<MochiTodo>

    suspend fun createTodo(draft: MochiTodoDraft): MochiTodo

    suspend fun updateTodo(todo: MochiTodo): MochiTodo

    suspend fun getTodo(id: String): MochiTodo?

    suspend fun completeTodo(id: String, expectedRevision: Long? = null): MochiTodo

    suspend fun listTodosForDate(date: LocalDate): List<MochiTodo>

    suspend fun listAgendaTodos(date: LocalDate, today: LocalDate): List<MochiTodo> =
        if (date == today) {
            listActiveTodosThroughDate(date) + listTodosForDate(date).filter { it.status == TodoStatus.COMPLETED }
        } else {
            listTodosForDate(date)
        }

    suspend fun listActiveTodosThroughDate(date: LocalDate): List<MochiTodo>

    suspend fun listUndatedTodos(
        status: TodoStatus = TodoStatus.ACTIVE,
    ): List<MochiTodo>

    suspend fun listTodosByStatus(status: TodoStatus): List<MochiTodo>

    suspend fun deleteTodo(id: String, expectedRevision: Long? = null)
}

class PlannerRepository(
    private val calendarEventDao: CalendarEventDao,
    private val todoDao: TodoDao,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: PlannerIdGenerator = UuidPlannerIdGenerator(),
) : PlannerStore {
    override val changes: Flow<Unit> = calendarEventDao.observeChanges().map { }
    override suspend fun reminderDelivered(id: String): Boolean = calendarEventDao.reminderDelivered(id)
    override suspend fun recordReminder(id: String) = calendarEventDao.recordReminder(id, clock.millis())

    override suspend fun createCalendarEvent(
        draft: CalendarEventDraft,
    ): CalendarEvent {
        val normalized = draft.normalized().withDefaultRecurrenceEnd()
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
            priority = normalized.priority,
        )
        calendarEventDao.upsert(event.toEntity())
        return event
    }

    override suspend fun updateCalendarEvent(event: CalendarEvent, indefiniteRecurrence: Boolean): CalendarEvent {
        val existing = calendarEventDao.getById(event.id)
            ?: throw PlannerNotFoundException(
                "Calendar event not found: ${event.id}",
            )
        val normalized = event.preserveRecurrenceEnd(existing.recurrenceRule, indefiniteRecurrence).normalized().copy(
            createdAt = Instant.ofEpochMilli(existing.createdAtEpochMillis),
            updatedAt = clock.instant(),
            revision = existing.revision + 1,
            completedAt = if (existing.recurrenceRule == null && event.recurrenceRule != null) null else event.completedAt,
        )
        validateCalendarEvent(normalized)
        calendarEventDao.replace(normalized.toEntity(), event.revision)
        return normalized
    }

    override suspend fun getCalendarEvent(id: String): CalendarEvent? {
        if ('@' !in id) return calendarEventDao.getById(id)?.toDomain()
        val root = calendarEventDao.getById(id.substringBeforeLast('@'))?.toDomain() ?: return null
        val original = Instant.ofEpochMilli(id.substringAfterLast('@').toLong())
        val exception = calendarEventDao.getById(id)?.toDomain()
        if (exception != null) {
            return exception.copy(revision = root.revision, recurrenceRule = root.recurrenceRule)
        }
        return root.occurrences(original, original.plusMillis(1))
            .firstOrNull { it.occurrenceId == id }
    }

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
        ).flatMap { entity ->
            val root = entity.toDomain()
            val overrides = calendarEventDao.exceptions(root.id).associateBy { it.id }
            val generated = root.occurrences(rangeStart, rangeEnd).filter { it.occurrenceId !in overrides }
            generated + overrides.values.map { it.toDomain().copy(revision = root.revision, recurrenceRule = root.recurrenceRule) }
                .filter { it.overlaps(rangeStart, rangeEnd) }
        }.filterNot { it.cancelled }.sortedWith(compareBy<CalendarEvent> { !it.allDay }.thenBy { it.startAt }.thenBy { it.id })
    }

    override suspend fun deleteCalendarEvent(id: String) {
        val existing = calendarEventDao.getById(id)
            ?: throw PlannerNotFoundException("Calendar event not found: $id")
        calendarEventDao.delete(existing)
    }

    override suspend fun updateCalendarOccurrence(requestedEvent: CalendarEvent, scope: OccurrenceScope, indefiniteRecurrence: Boolean): CalendarEvent {
        val root = calendarEventDao.getById(requestedEvent.rootId)?.toDomain()
            ?: throw PlannerNotFoundException("User schedule not found: ${requestedEvent.rootId}")
        val original = requestedEvent.occurrenceStartAt ?: root.startAt
        val rebased = if (scope == OccurrenceScope.FUTURE && requestedEvent.recurrenceRule == root.recurrenceRule) {
            requestedEvent.copy(recurrenceRule = com.example.mochi_pet.core.model.moveRecurrenceAnchor(
                root.recurrenceRule, original, requestedEvent.startAt, requestedEvent.timezone,
            ))
        } else requestedEvent
        val event = rebased.preserveRecurrenceEnd(root.recurrenceRule, indefiniteRecurrence)
        checkPlannerRevision(root.revision == event.revision)
        requireNotNull(getCalendarEvent("${root.id}@${original.toEpochMilli()}")) { "Occurrence does not exist" }
        validateCalendarEvent(if (scope == OccurrenceScope.THIS) event.copy(recurrenceRule = null) else event)
        if (scope == OccurrenceScope.ALL || root.recurrenceRule == null) {
            return updateCalendarEvent(event.copy(
                id = root.id, seriesId = null, occurrenceStartAt = null,
            ), indefiniteRecurrence)
        }
        if (scope == OccurrenceScope.THIS) {
            require(event.recurrenceRule == root.recurrenceRule) { "A recurrence change requires future or all scope" }
            val saved = event.normalized().copy(
                id = event.occurrenceId, seriesId = root.id, occurrenceStartAt = original,
                recurrenceRule = null, revision = root.revision + 1, updatedAt = clock.instant(),
            )
            calendarEventDao.saveException(saved.toEntity(), root.revision)
            return saved.copy(recurrenceRule = root.recurrenceRule)
        }
        val base = requireNotNull(root.recurrenceRule).split(';')
            .filterNot { it.startsWith("COUNT=") || it.startsWith("UNTIL=") }.joinToString(";")
        val until = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(original.minusSeconds(1))
        var futureRule = event.recurrenceRule
        val rule = CalendarRecurrence.parse(requireNotNull(root.recurrenceRule), root.timezone)
        if (rule.count != null && futureRule != null && CalendarRecurrence.parse(futureRule, event.timezone).count == rule.count) {
            var before = 0
            var cursor = root.startAt
            while (cursor < original) {
                val end = minOf(cursor.plusSeconds(366L * 86400), original)
                before += root.occurrences(cursor, end).size
                cursor = end
            }
            futureRule = futureRule.replace("COUNT=${rule.count}", "COUNT=${rule.count - before}")
        }
        val future = event.normalized().copy(
            id = idGenerator.nextId("event"), seriesId = null, occurrenceStartAt = null,
            recurrenceRule = futureRule, revision = 0, createdAt = clock.instant(), updatedAt = clock.instant(),
            completedAt = null,
        )
        calendarEventDao.splitSeries(
            root.copy(recurrenceRule = if (original == root.startAt) root.recurrenceRule else "$base;UNTIL=$until",
                cancelled = original == root.startAt, revision = root.revision + 1).toEntity(),
            future.toEntity(), root.revision, original.toEpochMilli(),
        )
        return future
    }

    override suspend fun completeCalendarOccurrence(id: String, revision: Long): CalendarEvent {
        val event = getCalendarEvent(id) ?: throw PlannerNotFoundException("Occurrence not found: $id")
        if (event.completedAt != null) return event
        require(!event.cancelled) { "Occurrence has been deleted" }
        return updateCalendarOccurrence(event.copy(completedAt = clock.instant(), revision = revision), OccurrenceScope.THIS)
    }

    override suspend fun deleteCalendarOccurrence(id: String, revision: Long, scope: OccurrenceScope) {
        val event = getCalendarEvent(id) ?: throw PlannerNotFoundException("Occurrence not found: $id")
        if (scope == OccurrenceScope.ALL) {
            calendarEventDao.deleteVersion(event.rootId, revision)
        } else {
            updateCalendarOccurrence(event.copy(cancelled = true, revision = revision), scope)
        }
    }

    override suspend fun listCalendarReminders(start: Instant, end: Instant): List<CalendarEvent> {
        return calendarEventDao.reminderSeries().flatMap { entity ->
            val root = entity.toDomain()
            val overrides = calendarEventDao.exceptions(root.id).associateBy { it.id }
            val generated = root.reminderAt?.let { reminder ->
                val offset = java.time.Duration.between(root.startAt, reminder)
                root.occurrences(start.minus(offset).minusSeconds(86400), end.minus(offset).plusSeconds(86400))
                    .filter { it.occurrenceId !in overrides }
            }.orEmpty()
            generated +
                overrides.values.map { it.toDomain().copy(revision = root.revision) }
        }.filter { !it.cancelled && it.completedAt == null && it.reminderAt != null && it.reminderAt >= start && it.reminderAt < end }
    }

    override suspend fun listTodosInRange(start: LocalDate, end: LocalDate): List<MochiTodo> =
        todoDao.listRange(start.toEpochDay(), end.toEpochDay()).map { it.toDomain() }

    override suspend fun promoteTodo(todo: MochiTodo, draft: CalendarEventDraft): CalendarEvent {
        val normalized = draft.normalized().withDefaultRecurrenceEnd()
        validateCalendarEvent(normalized)
        val event = CalendarEvent(
            id = todo.id, title = normalized.title, description = normalized.description,
            startAt = normalized.startAt, endAt = normalized.endAt, allDay = normalized.allDay,
            timezone = normalized.timezone, recurrenceRule = normalized.recurrenceRule,
            location = normalized.location, reminderAt = normalized.reminderAt,
            createdAt = todo.createdAt, updatedAt = clock.instant(),
            completedAt = if (normalized.recurrenceRule == null) todo.completedAt else null,
            revision = todo.revision + 1, priority = todo.priority,
        )
        calendarEventDao.promoteTodo(event.toEntity(), todo.revision, todo.completedAt?.toEpochMilli())
        return event
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
            revision = existing.revision + 1,
        )
        validateTodo(normalized)
        todoDao.replace(normalized.toEntity(), todo.revision)
        return normalized
    }

    override suspend fun getTodo(id: String): MochiTodo? =
        todoDao.getById(id)?.toDomain()

    override suspend fun completeTodo(id: String, expectedRevision: Long?): MochiTodo {
        val existing = (
            todoDao.getById(id)
                ?: throw PlannerNotFoundException("Todo not found: $id")
            ).toDomain()
        if (existing.status == TodoStatus.COMPLETED) {
            return existing
        }
        checkPlannerRevision(expectedRevision == null || existing.revision == expectedRevision)
        val now = clock.instant()
        val completed = existing.copy(
            status = TodoStatus.COMPLETED,
            completedAt = now,
            updatedAt = now,
            revision = existing.revision + 1,
        )
        todoDao.replace(completed.toEntity(), existing.revision)
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

    override suspend fun deleteTodo(id: String, expectedRevision: Long?) {
        val existing = todoDao.getById(id)
            ?: throw PlannerNotFoundException("Todo not found: $id")
        checkPlannerRevision(expectedRevision == null || existing.revision == expectedRevision)
        todoDao.deleteVersion(existing)
    }
}

private fun CalendarEvent.preserveRecurrenceEnd(previous: String?, indefinite: Boolean): CalendarEvent {
    val rule = recurrenceRule ?: return this
    val parsed = CalendarRecurrence.parse(rule, timezone)
    if (indefinite) {
        require(parsed.until == null && parsed.count == null) { "Indefinite recurrence cannot have an end condition" }
        return this
    }
    if (parsed.until != null || parsed.count != null) return this
    val ending = previous?.split(';')?.firstOrNull { it.startsWith("UNTIL=") || it.startsWith("COUNT=") }
    if (ending != null) return copy(recurrenceRule = "$rule;$ending")
    if (previous != null) return this
    val draft = CalendarEventDraft(title = title, startAt = startAt, timezone = timezone, recurrenceRule = rule)
    return copy(recurrenceRule = draft.withDefaultRecurrenceEnd().recurrenceRule)
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
    require(draft.title.length <= 500) { "Calendar title is too long" }
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
    require(draft.reminderAt == null || !draft.reminderAt.isAfter(
        if (draft.allDay) draft.startAt.atZone(draft.timezone).toLocalDate().plusDays(1)
            .atStartOfDay(draft.timezone).toInstant().minusNanos(1) else draft.startAt,
    )) {
        "Calendar reminder must not be after start"
    }
    draft.recurrenceRule?.let {
        val rule = CalendarRecurrence.parse(it, draft.timezone)
        require(!draft.indefiniteRecurrence || (rule.until == null && rule.count == null)) { "Indefinite recurrence cannot have an end condition" }
        require(rule.until == null || rule.until >= draft.startAt) { "Recurrence ends before its first occurrence" }
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
        completedAtEpochMillis = completedAt?.toEpochMilli(),
        revision = revision,
        seriesId = seriesId,
        occurrenceStartAtEpochMillis = occurrenceStartAt?.toEpochMilli(),
        cancelled = cancelled,
        priority = priority.name,
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
        completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
        revision = revision,
        seriesId = seriesId,
        occurrenceStartAt = occurrenceStartAtEpochMillis?.let(Instant::ofEpochMilli),
        cancelled = cancelled,
        priority = TodoPriority.valueOf(priority),
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
        revision = revision,
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
        revision = revision,
    )

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)
