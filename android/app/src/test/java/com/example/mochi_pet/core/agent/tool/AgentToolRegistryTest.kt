package com.example.mochi_pet.core.agent.tool

import com.example.mochi_pet.core.database.PlannerStore
import com.example.mochi_pet.core.model.CalendarEvent
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.model.MochiTodo
import com.example.mochi_pet.core.model.MochiTodoDraft
import com.example.mochi_pet.core.model.TodoStatus
import com.example.mochi_pet.core.navigation.NavigateMochiUiTool
import com.example.mochi_pet.core.navigation.NavigationDecision
import com.example.mochi_pet.core.navigation.NavigationPolicy
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRegistryTest {
    private val today = LocalDate.of(2026, 7, 31)
    private val context = ToolExecutionContext(
        currentDate = today,
        currentSurface = MochiSurface.Face,
    )

    @Test
    fun `unknown tool returns explicit error`() = runBlocking {
        val result = ToolRegistry(emptyList()).execute(
            name = "missing",
            arguments = buildJsonObject {},
            context = context,
        )

        assertEquals("error", result.status)
        assertEquals(ToolErrorCode.UNKNOWN_TOOL.name, result.code)
    }

    @Test
    fun `camera image input requires explicit camera and image intent`() {
        assertTrue(
            allowsCameraEventImageInput(
                "Show the latest door camera event image",
            ),
        )
        assertTrue(
            allowsCameraEventImageInput(
                "看看门铃摄像头最新事件画面",
            ),
        )
        assertFalse(
            allowsCameraEventImageInput(
                "Turn on camera motion detection",
            ),
        )
        assertFalse(
            allowsCameraEventImageInput(
                "Disable camera event notifications",
            ),
        )
        assertFalse(
            allowsCameraEventImageInput(
                "Show camera event settings",
            ),
        )
        assertFalse(
            allowsCameraEventImageInput(
                "Can you see whether the latest camera event notification is enabled?",
            ),
        )
        assertFalse(
            allowsCameraEventImageInput(
                "Show me the latest image",
            ),
        )
    }

    @Test
    fun `todo tool creates dated local todo`() = runBlocking {
        val store = RecordingPlannerStore()
        val registry = ToolRegistry(listOf(ManageMochiTodoTool(store)))

        val result = registry.execute(
            name = "manage_mochi_todo",
            arguments = buildJsonObject {
                put("operate", "create")
                put("content", "Buy milk")
                put("scheduled_date", "2026-08-01")
                put("priority", "high")
            },
            context = context,
        )

        assertEquals("ok", result.status)
        assertEquals("Buy milk", store.todos.single().content)
        assertEquals(LocalDate.of(2026, 8, 1), store.todos.single().scheduledDate)
        assertEquals(
            "todo_1",
            result.data
                ?.jsonObject
                ?.get("todo")
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `todo tool defaults missing date to today and returns notice`() =
        runBlocking {
            val store = RecordingPlannerStore()
            val registry = ToolRegistry(listOf(ManageMochiTodoTool(store)))

            val result = registry.execute(
                name = "manage_mochi_todo",
                arguments = buildJsonObject {
                    put("operate", "create")
                    put("content", "Review notes")
                },
                context = context,
            )

            assertEquals(today, store.todos.single().scheduledDate)
            assertEquals(
                "true",
                result.data
                    ?.jsonObject
                    ?.get("scheduled_date_defaulted")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `todo deletion requires confirmation`() = runBlocking {
        val store = RecordingPlannerStore().apply {
            createTodo(MochiTodoDraft(content = "Keep me"))
        }
        val registry = ToolRegistry(listOf(ManageMochiTodoTool(store)))

        val rejected = registry.execute(
            name = "manage_mochi_todo",
            arguments = buildJsonObject {
                put("operate", "delete")
                put("todo_id", "todo_1")
            },
            context = context,
        )

        assertEquals(ToolErrorCode.INVALID_ARGS.name, rejected.code)
        assertFalse(store.todos.isEmpty())

        val accepted = registry.execute(
            name = "manage_mochi_todo",
            arguments = buildJsonObject {
                put("operate", "delete")
                put("todo_id", "todo_1")
                put("confirmed", true)
            },
            context = context,
        )

        assertEquals("ok", accepted.status)
        assertTrue(store.todos.isEmpty())
    }

    @Test
    fun `calendar tool creates and lists local event`() = runBlocking {
        val store = RecordingPlannerStore()
        val registry = ToolRegistry(listOf(ManageMochiCalendarTool(store)))

        val created = registry.execute(
            name = "manage_mochi_calendar",
            arguments = buildJsonObject {
                put("operate", "create")
                put("title", "Project review")
                put("start_iso", "2026-08-01T01:00:00Z")
                put("end_iso", "2026-08-01T02:00:00Z")
                put("timezone", "Asia/Shanghai")
            },
            context = context,
        )
        val listed = registry.execute(
            name = "manage_mochi_calendar",
            arguments = buildJsonObject {
                put("operate", "list")
                put("range_start_iso", "2026-08-01T00:00:00Z")
                put("range_end_iso", "2026-08-02T00:00:00Z")
            },
            context = context,
        )

        assertEquals("ok", created.status)
        assertEquals("event_1", store.events.single().id)
        assertEquals(
            1,
            listed.data?.jsonObject?.get("count")?.jsonPrimitive?.content?.toInt(),
        )
    }

    @Test
    fun `navigation tool applies validated directive`() = runBlocking {
        var applied: NavigationDecision? = null
        val tool = NavigateMochiUiTool(
            policy = NavigationPolicy(),
            sink = { decision -> applied = decision },
        )

        val result = ToolRegistry(listOf(tool)).execute(
            name = "navigate_mochi_ui",
            arguments = buildJsonObject {
                put("operate", "show_calendar_day")
                put("reason", "other_date")
                put("date", "2026-08-01")
                put("section", "agenda")
            },
            context = context,
        )

        assertEquals("ok", result.status)
        assertNotNull(applied)
        assertEquals(
            LocalDate.of(2026, 8, 1),
            applied?.directive?.date,
        )
    }

    @Test
    fun `result envelope serializes without null fields`() {
        val encoded = AgentToolJson.encode(
            ToolResultEnvelope.success(
                buildJsonObject {
                    put("value", JsonPrimitive("ok"))
                },
            ),
        )

        assertEquals("""{"status":"ok","data":{"value":"ok"}}""", encoded)
    }
}

private class RecordingPlannerStore : PlannerStore {
    val todos = mutableListOf<MochiTodo>()
    val events = mutableListOf<CalendarEvent>()
    private var nextTodoId = 1
    private var nextEventId = 1

    override suspend fun createTodo(draft: MochiTodoDraft): MochiTodo {
        val now = Instant.parse("2026-07-31T10:00:00Z")
        return MochiTodo(
            id = "todo_${nextTodoId++}",
            content = draft.content,
            status = TodoStatus.ACTIVE,
            priority = draft.priority,
            scheduledDate = draft.scheduledDate,
            dueAt = draft.dueAt,
            reminderAt = draft.reminderAt,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        ).also(todos::add)
    }

    override suspend fun getTodo(id: String): MochiTodo? =
        todos.firstOrNull { it.id == id }

    override suspend fun updateTodo(todo: MochiTodo): MochiTodo {
        todos.replaceAll { if (it.id == todo.id) todo else it }
        return todo
    }

    override suspend fun completeTodo(id: String): MochiTodo {
        val existing = requireNotNull(getTodo(id))
        val completed = existing.copy(
            status = TodoStatus.COMPLETED,
            completedAt = Instant.parse("2026-07-31T10:00:00Z"),
        )
        return updateTodo(completed)
    }

    override suspend fun listTodosForDate(date: LocalDate): List<MochiTodo> =
        todos.filter { it.scheduledDate == date }

    override suspend fun listActiveTodosThroughDate(
        date: LocalDate,
    ): List<MochiTodo> =
        todos.filter {
            it.status == TodoStatus.ACTIVE &&
                it.scheduledDate != null &&
                it.scheduledDate <= date
        }

    override suspend fun listUndatedTodos(status: TodoStatus): List<MochiTodo> =
        todos.filter { it.scheduledDate == null && it.status == status }

    override suspend fun listTodosByStatus(status: TodoStatus): List<MochiTodo> =
        todos.filter { it.status == status }

    override suspend fun deleteTodo(id: String) {
        todos.removeAll { it.id == id }
    }

    override suspend fun createCalendarEvent(
        draft: CalendarEventDraft,
    ): CalendarEvent {
        val now = Instant.parse("2026-07-31T10:00:00Z")
        return CalendarEvent(
            id = "event_${nextEventId++}",
            title = draft.title,
            description = draft.description,
            startAt = draft.startAt,
            endAt = draft.endAt,
            allDay = draft.allDay,
            timezone = draft.timezone,
            recurrenceRule = draft.recurrenceRule,
            location = draft.location,
            reminderAt = draft.reminderAt,
            createdAt = now,
            updatedAt = now,
        ).also(events::add)
    }

    override suspend fun updateCalendarEvent(event: CalendarEvent): CalendarEvent {
        events.replaceAll { if (it.id == event.id) event else it }
        return event
    }

    override suspend fun getCalendarEvent(id: String): CalendarEvent? =
        events.firstOrNull { it.id == id }

    override suspend fun listCalendarEvents(
        rangeStart: Instant,
        rangeEnd: Instant,
    ): List<CalendarEvent> =
        events.filter {
            it.startAt < rangeEnd && (it.endAt ?: it.startAt.plusMillis(1)) >
                rangeStart
        }

    override suspend fun deleteCalendarEvent(id: String) {
        events.removeAll { it.id == id }
    }
}
