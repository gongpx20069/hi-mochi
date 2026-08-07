package com.example.mochi_pet.core.agent.tool

import com.example.mochi_pet.core.database.PlannerNotFoundException
import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.MochiTodoDraft
import com.example.mochi_pet.core.model.TodoPriority
import com.example.mochi_pet.core.model.TodoStatus
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
    UPDATE,
    DELETE,
}

private enum class TodoOperation {
    CREATE,
    LIST,
    UPDATE,
    COMPLETE,
    DELETE,
}

class ManageMochiCalendarTool(
    private val plannerStore: PlannerStore,
) : AgentTool {
    override val name: String = "manage_mochi_calendar"

    override val schema: JsonObject = functionToolSchema(
        name = name,
        description = "Manage events in Mochi's local calendar.",
        properties = buildJsonObject {
            put("operate", enumProperty(CalendarOperation.entries))
            put("event_id", stringProperty())
            put("title", stringProperty())
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
        required = listOf("operate"),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope =
        when (arguments.requiredEnum<CalendarOperation>("operate")) {
            CalendarOperation.CREATE -> create(arguments)
            CalendarOperation.LIST -> list(arguments)
            CalendarOperation.UPDATE -> update(arguments)
            CalendarOperation.DELETE -> delete(arguments)
        }

    private suspend fun create(arguments: JsonObject): ToolResultEnvelope {
        val event = plannerStore.createCalendarEvent(
            CalendarEventDraft(
                title = arguments.requiredString("title"),
                description = arguments.optionalString("description"),
                startAt = Instant.parse(arguments.requiredString("start_iso")),
                endAt = arguments.optionalString("end_iso")?.let(Instant::parse),
                allDay = arguments.optionalBoolean("all_day") ?: false,
                timezone = ZoneId.of(arguments.requiredString("timezone")),
                recurrenceRule = arguments.optionalString("recurrence_rule"),
                location = arguments.optionalString("location"),
                reminderAt =
                    arguments.optionalString("reminder_iso")?.let(Instant::parse),
            ),
        )
        return ToolResultEnvelope.success(event.toJson())
    }

    private suspend fun list(arguments: JsonObject): ToolResultEnvelope {
        val allEvents = plannerStore.listCalendarEvents(
            rangeStart = Instant.parse(
                arguments.requiredString("range_start_iso"),
            ),
            rangeEnd = Instant.parse(
                arguments.requiredString("range_end_iso"),
            ),
        )
        val limit = arguments.listLimit()
        val events = allEvents.take(limit)
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("events", JsonArray(events.map(CalendarEvent::toJson)))
                put("count", events.size)
                put("total_count", allEvents.size)
                put("has_more", allEvents.size > events.size)
            },
        )
    }

    private suspend fun update(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.requiredString("event_id")
        val existing = plannerStore.getCalendarEvent(id)
            ?: throw PlannerNotFoundException("Calendar event not found: $id")
        val updated = plannerStore.updateCalendarEvent(
            existing.copy(
                title = arguments.optionalString("title") ?: existing.title,
                description = arguments.updatedString(
                    "description",
                    existing.description,
                ),
                startAt = arguments.optionalString("start_iso")
                    ?.let(Instant::parse)
                    ?: existing.startAt,
                endAt = arguments.updatedInstant("end_iso", existing.endAt),
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
                    existing.reminderAt,
                ),
            ),
        )
        return ToolResultEnvelope.success(updated.toJson())
    }

    private suspend fun delete(arguments: JsonObject): ToolResultEnvelope {
        if (arguments.optionalBoolean("confirmed") != true) {
            throw ToolInputException(
                "Calendar deletion requires confirmed=true",
            )
        }
        val id = arguments.requiredString("event_id")
        plannerStore.deleteCalendarEvent(id)
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("event_id", id)
                put("deleted", true)
            },
        )
    }
}

class ManageMochiTodoTool(
    private val plannerStore: PlannerStore,
) : AgentTool {
    override val name: String = "manage_mochi_todo"

    override val schema: JsonObject = functionToolSchema(
        name = name,
        description =
            "Manage explicit todo requests in Mochi's local planner.",
        properties = buildJsonObject {
            put("operate", enumProperty(TodoOperation.entries))
            put("todo_id", stringProperty())
            put("content", stringProperty())
            put("priority", enumProperty(TodoPriority.entries))
            put("scheduled_date", stringProperty())
            put("due_iso", stringProperty())
            put("reminder_iso", stringProperty())
            put("status", enumProperty(TodoStatus.entries))
            put("undated_only", booleanProperty())
            put("limit", integerProperty())
            put("confirmed", booleanProperty())
        },
        required = listOf("operate"),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope =
        when (arguments.requiredEnum<TodoOperation>("operate")) {
            TodoOperation.CREATE -> create(arguments, context)
            TodoOperation.LIST -> list(arguments)
            TodoOperation.UPDATE -> update(arguments)
            TodoOperation.COMPLETE -> complete(arguments)
            TodoOperation.DELETE -> delete(arguments)
        }

    private suspend fun create(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        val requestedDate = arguments.optionalString("scheduled_date")
            ?.let(LocalDate::parse)
        val scheduledDate = requestedDate ?: context.currentDate
        val todo = plannerStore.createTodo(
            MochiTodoDraft(
                content = arguments.requiredString("content"),
                priority = arguments.optionalEnum<TodoPriority>("priority")
                    ?: TodoPriority.NORMAL,
                scheduledDate = scheduledDate,
                dueAt = arguments.optionalString("due_iso")?.let(Instant::parse),
                reminderAt =
                    arguments.optionalString("reminder_iso")?.let(Instant::parse),
            ),
        )
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("todo", todo.toJson())
                put("scheduled_date", scheduledDate.toString())
                put("scheduled_date_defaulted", requestedDate == null)
                if (requestedDate == null) {
                    put(
                        "user_notice",
                        "No date was provided, so this todo was scheduled today.",
                    )
                }
            },
        )
    }

    private suspend fun list(arguments: JsonObject): ToolResultEnvelope {
        val status = arguments.optionalEnum<TodoStatus>("status")
            ?: TodoStatus.ACTIVE
        val scheduledDate = arguments.optionalString("scheduled_date")
            ?.let(LocalDate::parse)
        val allTodos = when {
            scheduledDate != null ->
                plannerStore.listTodosForDate(scheduledDate)
                    .filter { it.status == status }
            arguments.optionalBoolean("undated_only") == true ->
                plannerStore.listUndatedTodos(status)
            else -> plannerStore.listTodosByStatus(status)
        }
        val todos = allTodos.take(arguments.listLimit())
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("todos", JsonArray(todos.map(MochiTodo::toJson)))
                put("count", todos.size)
                put("total_count", allTodos.size)
                put("has_more", allTodos.size > todos.size)
            },
        )
    }

    private suspend fun update(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.requiredString("todo_id")
        val existing = plannerStore.getTodo(id)
            ?: throw PlannerNotFoundException("Todo not found: $id")
        if (arguments.containsKey("status")) {
            throw ToolInputException(
                "Use operate=complete to complete a todo",
            )
        }
        val updated = plannerStore.updateTodo(
            existing.copy(
                content = arguments.optionalString("content") ?: existing.content,
                priority = arguments.optionalEnum<TodoPriority>("priority")
                    ?: existing.priority,
                scheduledDate = arguments.updatedDate(
                    "scheduled_date",
                    existing.scheduledDate,
                ),
                dueAt = arguments.updatedInstant("due_iso", existing.dueAt),
                reminderAt = arguments.updatedInstant(
                    "reminder_iso",
                    existing.reminderAt,
                ),
            ),
        )
        return ToolResultEnvelope.success(updated.toJson())
    }

    private suspend fun complete(arguments: JsonObject): ToolResultEnvelope {
        val completed = plannerStore.completeTodo(
            arguments.requiredString("todo_id"),
        )
        return ToolResultEnvelope.success(completed.toJson())
    }

    private suspend fun delete(arguments: JsonObject): ToolResultEnvelope {
        if (arguments.optionalBoolean("confirmed") != true) {
            throw ToolInputException("Todo deletion requires confirmed=true")
        }
        val id = arguments.requiredString("todo_id")
        plannerStore.deleteTodo(id)
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("todo_id", id)
                put("deleted", true)
            },
        )
    }
}

private fun CalendarEvent.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
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

private fun JsonObject.updatedDate(
    name: String,
    current: LocalDate?,
): LocalDate? =
    if (containsKey(name)) {
        optionalString(name)?.let(LocalDate::parse)
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
