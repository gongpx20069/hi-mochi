package com.example.mochi_pet.core.agent.tool

import com.example.mochi_pet.core.database.PlannerNotFoundException
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.MochiTodoDraft
import com.example.mochi_pet.core.model.TodoPriority
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.model.OccurrenceScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class CalendarOperation {
    CREATE,
    LIST,
    GET,
    UPDATE,
    COMPLETE,
    DELETE,
}

class ManageMochiCalendarTool(
    private val plannerStore: PlannerStore,
    private val reminders: com.example.mochi_pet.core.model.UserReminderController? = null,
) : AgentTool {
    override val name: String = "manage_mochi_calendar"

    override val schema: JsonObject = functionToolSchema(
        name = name,
        description = "Manage events in Mochi's local calendar.",
        properties = buildJsonObject {
            put("operation", enumProperty(CalendarOperation.entries))
            put("id", stringProperty())
            put("scope", enumProperty(OccurrenceScope.entries))
            put("revision", buildJsonObject { put("type", "integer") })
            put("scheduled_date", stringProperty())
            put("indefinite", booleanProperty())
            put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
            put("title", stringProperty())
            put("priority", enumProperty(TodoPriority.entries))
            put("status", enumProperty(TodoStatus.entries))
            put("description", stringProperty())
            put("start_iso", stringProperty())
            put("end_iso", stringProperty())
            put("all_day", booleanProperty())
            put("timezone", stringProperty())
            put("recurrence_rule", stringProperty())
            put("location", stringProperty())
            put("reminder_iso", stringProperty())
            put("range_start_iso", stringProperty())
            put("range_end_iso", stringProperty())
            put("limit", integerProperty())
            put("confirmed", booleanProperty())
        },
        required = listOf("operation"),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        val operation = arguments.requiredEnum<CalendarOperation>("operation")
        val result = when (operation) {
            CalendarOperation.CREATE -> create(arguments, context)
            CalendarOperation.LIST -> list(arguments, context)
            CalendarOperation.GET -> get(arguments)
            CalendarOperation.UPDATE -> update(arguments)
            CalendarOperation.COMPLETE -> complete(arguments)
            CalendarOperation.DELETE -> delete(arguments)
        }
        val readiness = try {
            if (operation in setOf(CalendarOperation.GET, CalendarOperation.LIST)) reminders?.readiness() else reminders?.sync()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return ToolResultEnvelope(
                status = "error", code = ToolErrorCode.INTERNAL_ERROR.name,
                message = "Record saved, but reminder registration failed. Query the saved record before retrying.",
                data = result.data,
            )
        }
        val data = result.data as? JsonObject ?: return result
        return result.copy(data = JsonObject(data + ("reminder_capability" to JsonPrimitive(readiness?.name?.lowercase() ?: "unavailable"))))
    }

    private suspend fun create(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope {
        require(arguments.optionalString("id") == null) { "create must not include an id" }
        val zone = arguments.optionalString("timezone")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val requestedDate = arguments.optionalString("scheduled_date")?.let(LocalDate::parse)
        val start = arguments.optionalString("start_iso")?.let(Instant::parse)
        require(start != null || arguments.optionalBoolean("all_day") != false) { "A timed schedule requires start_iso" }
        if (start == null && arguments.optionalString("recurrence_rule") == null &&
            listOf("description", "location", "end_iso").none { arguments.optionalString(it) != null }) {
            val todo = plannerStore.createTodo(MochiTodoDraft(
                content = arguments.requiredString("title"),
                priority = arguments.optionalEnum<TodoPriority>("priority") ?: TodoPriority.NORMAL,
                scheduledDate = requestedDate ?: context.currentDate,
                reminderAt = arguments.optionalString("reminder_iso")?.let(Instant::parse),
            ))
            return ToolResultEnvelope.success(buildJsonObject {
                put("todo", todo.toJson())
                put("scheduled_date_defaulted", requestedDate == null)
            })
        }
        val event = plannerStore.createCalendarEvent(
            CalendarEventDraft(
                title = arguments.requiredString("title"),
                description = arguments.optionalString("description"),
                startAt = start ?: (requestedDate ?: context.currentDate).atStartOfDay(zone).toInstant(),
                endAt = arguments.optionalString("end_iso")?.let(Instant::parse),
                allDay = arguments.optionalBoolean("all_day") ?: (start == null),
                timezone = zone,
                recurrenceRule = arguments.optionalString("recurrence_rule"),
                location = arguments.optionalString("location"),
                reminderAt =
                    arguments.optionalString("reminder_iso")?.let(Instant::parse),
                indefiniteRecurrence = arguments.optionalBoolean("indefinite") ?: false,
                priority = arguments.optionalEnum<TodoPriority>("priority") ?: TodoPriority.NORMAL,
            ),
        )
        return ToolResultEnvelope.success(event.toJson())
    }

    private suspend fun list(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope {
        val zone = arguments.optionalString("timezone")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val date = arguments.optionalString("scheduled_date")?.let(LocalDate::parse) ?: context.currentDate
        val start = arguments.optionalString("range_start_iso")?.let(Instant::parse) ?: date.atStartOfDay(zone).toInstant()
        val end = arguments.optionalString("range_end_iso")?.let(Instant::parse) ?: date.plusDays(1).atStartOfDay(zone).toInstant()
        val status = arguments.optionalEnum<TodoStatus>("status")
        val priority = arguments.optionalEnum<TodoPriority>("priority")
        val allEvents = plannerStore.listCalendarEvents(
            rangeStart = start,
            rangeEnd = end,
        ).filter { (status == null || (it.completedAt != null) == (status == TodoStatus.COMPLETED)) &&
            (priority == null || it.priority == priority) }
        val limit = arguments.listLimit()
        val offset = arguments.optionalInt("offset") ?: 0
        require(offset >= 0) { "offset must not be negative" }
        val firstDate = start.atZone(zone).toLocalDate()
        val matchingTodos = if (firstDate == context.currentDate &&
            start == firstDate.atStartOfDay(zone).toInstant() && end == firstDate.plusDays(1).atStartOfDay(zone).toInstant()) {
            plannerStore.listAgendaTodos(firstDate, context.currentDate)
        } else {
            plannerStore.listTodosInRange(firstDate, end.minusNanos(1).atZone(zone).toLocalDate().plusDays(1))
        }
        val allTodos = matchingTodos.filter { (status == null || it.status == status) && (priority == null || it.priority == priority) }
        val events = allEvents.drop(offset).take(limit)
        val todos = allTodos.drop((offset - allEvents.size).coerceAtLeast(0)).take(limit - events.size)
        val total = allEvents.size + allTodos.size
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("events", JsonArray(events.map(CalendarEvent::toJson)))
                put("todos", JsonArray(todos.map(MochiTodo::toJson)))
                put("count", events.size + todos.size)
                put("total_count", total)
                put("has_more", total > offset + limit)
                if (total > offset + limit) put("next_offset", offset + limit)
            },
        )
    }

    private suspend fun update(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.requiredString("id")
        val todo = plannerStore.getTodo(id)
        if (todo != null) {
            require(arguments.requiredRevision() == todo.revision) { "Schedule changed; read it again" }
            val start = arguments.optionalString("start_iso")?.let(Instant::parse)
            if (arguments.optionalString("recurrence_rule") != null) {
                val zone = arguments.optionalString("timezone")?.let(ZoneId::of) ?: ZoneId.systemDefault()
                return ToolResultEnvelope.success(plannerStore.promoteTodo(todo, CalendarEventDraft(
                    title = arguments.optionalString("title") ?: todo.content,
                    startAt = start ?: todo.dueAt ?: requireNotNull(todo.scheduledDate).atStartOfDay(zone).toInstant(),
                    allDay = start == null && todo.dueAt == null, timezone = zone,
                    recurrenceRule = arguments.optionalString("recurrence_rule"),
                    reminderAt = arguments.updatedInstant("reminder_iso", todo.reminderAt),
                    indefiniteRecurrence = arguments.optionalBoolean("indefinite") ?: false,
                )).toJson())
            }
            return ToolResultEnvelope.success(plannerStore.updateTodo(todo.copy(
                content = arguments.optionalString("title") ?: todo.content,
                priority = arguments.optionalEnum<TodoPriority>("priority") ?: todo.priority,
                scheduledDate = arguments.optionalString("scheduled_date")?.let(LocalDate::parse)
                    ?: start?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: todo.scheduledDate,
                dueAt = start ?: todo.dueAt,
                reminderAt = arguments.updatedInstant("reminder_iso", todo.reminderAt),
            )).toJson())
        }
        val existing = plannerStore.getCalendarEvent(id)
            ?: throw PlannerNotFoundException("Calendar event not found: $id")
        val start = arguments.optionalString("start_iso")?.let(Instant::parse) ?: existing.startAt
        val shift = java.time.Duration.between(existing.startAt, start)
        val updated = plannerStore.updateCalendarOccurrence(
            existing.copy(
                title = arguments.optionalString("title") ?: existing.title,
                priority = arguments.optionalEnum<TodoPriority>("priority") ?: existing.priority,
                description = arguments.updatedString(
                    "description",
                    existing.description,
                ),
                startAt = start,
                endAt = arguments.updatedInstant("end_iso", existing.endAt?.plus(shift)),
                allDay = arguments.optionalBoolean("all_day") ?: existing.allDay,
                timezone = arguments.optionalString("timezone")
                    ?.let(ZoneId::of)
                    ?: existing.timezone,
                recurrenceRule = arguments.updatedString(
                    "recurrence_rule",
                    existing.recurrenceRule,
                ),
                location = arguments.updatedString(
                    "location",
                    existing.location,
                ),
                reminderAt = arguments.updatedInstant(
                    "reminder_iso",
                    existing.reminderAt?.plus(shift),
                ),
                revision = arguments.requiredRevision(),
            ),
            arguments.optionalEnum<OccurrenceScope>("scope") ?: OccurrenceScope.THIS,
            indefiniteRecurrence = arguments.optionalBoolean("indefinite") ?: false,
        )
        return ToolResultEnvelope.success(updated.toJson())
    }

    private suspend fun delete(arguments: JsonObject): ToolResultEnvelope {
        if (arguments.optionalBoolean("confirmed") != true) {
            throw ToolInputException(
                "Calendar deletion requires confirmed=true",
            )
        }
        val id = arguments.requiredString("id")
        val todo = plannerStore.getTodo(id)
        if (todo != null) {
            require(arguments.requiredRevision() == todo.revision) { "Schedule changed; read it again" }
            plannerStore.deleteTodo(id, todo.revision)
        } else {
            plannerStore.deleteCalendarOccurrence(id, arguments.requiredRevision(),
                arguments.optionalEnum<OccurrenceScope>("scope") ?: OccurrenceScope.THIS)
        }
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("id", id)
                put("deleted", true)
            },
        )
    }

    private suspend fun get(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.requiredString("id")
        return ToolResultEnvelope.success(plannerStore.getCalendarEvent(id)?.toJson()
            ?: plannerStore.getTodo(id)?.toJson() ?: throw PlannerNotFoundException("User schedule not found: $id"))
    }

    private suspend fun complete(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.requiredString("id")
        val todo = plannerStore.getTodo(id)
        if (todo != null) {
            require(todo.completedAt != null || arguments.requiredRevision() == todo.revision) { "Schedule changed; read it again" }
            return ToolResultEnvelope.success(plannerStore.completeTodo(id, todo.revision).toJson())
        }
        return ToolResultEnvelope.success(plannerStore.completeCalendarOccurrence(id, arguments.requiredRevision()).toJson())
    }
}

private fun CalendarEvent.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("series_id", rootId)
        put("occurrence_id", occurrenceId)
        put("revision", revision)
        put("status", if (completedAt == null) "active" else "completed")
        put("priority", priority.name.lowercase())
        put("title", title)
        description?.let { put("description", it) }
        put("start_iso", startAt.toString())
        endAt?.let { put("end_iso", it.toString()) }
        put("all_day", allDay)
        put("timezone", timezone.id)
        recurrenceRule?.let { put("recurrence_rule", it) }
        location?.let { put("location", it) }
        reminderAt?.let { put("reminder_iso", it.toString()) }
        put("created_at", createdAt.toString())
        put("updated_at", updatedAt.toString())
    }

private fun MochiTodo.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("revision", revision)
        put("title", content)
        put("content", content)
        put("status", status.name.lowercase())
        put("priority", priority.name.lowercase())
        scheduledDate?.let { put("scheduled_date", it.toString()) }
        dueAt?.let { put("due_iso", it.toString()) }
        reminderAt?.let { put("reminder_iso", it.toString()) }
        completedAt?.let { put("completed_at", it.toString()) }
        put("created_at", createdAt.toString())
        put("updated_at", updatedAt.toString())
    }

private fun JsonObject.updatedString(
    name: String,
    current: String?,
): String? =
    if (containsKey(name)) {
        optionalString(name)
    } else {
        current
    }

private fun JsonObject.updatedInstant(
    name: String,
    current: Instant?,
): Instant? =
    if (containsKey(name)) {
        optionalString(name)?.let(Instant::parse)
    } else {
        current
    }

private fun stringProperty(): JsonObject =
    buildJsonObject {
        put("type", "string")
    }

private fun booleanProperty(): JsonObject =
    buildJsonObject {
        put("type", "boolean")
    }

private fun integerProperty(): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", MAX_LIST_ITEMS)
    }

private fun <T : Enum<T>> enumProperty(values: List<T>): JsonObject =
    buildJsonObject {
        put("type", "string")
        put(
            "enum",
            JsonArray(values.map { JsonPrimitive(it.name.lowercase()) }),
        )
    }

private fun JsonObject.listLimit(): Int {
    val limit = optionalInt("limit") ?: DEFAULT_LIST_ITEMS
    if (limit !in 1..MAX_LIST_ITEMS) {
        throw ToolInputException(
            "limit must be between 1 and $MAX_LIST_ITEMS",
        )
    }
    return limit
}

private const val DEFAULT_LIST_ITEMS = 50
private const val MAX_LIST_ITEMS = 200

private fun JsonObject.requiredRevision(): Long =
    this["revision"]?.toString()?.toLongOrNull() ?: throw ToolInputException("Current revision is required; query get/list first")
