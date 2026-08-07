package com.example.mochi_pet.core.schedule

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface AgentScheduleController {
    suspend fun sync(schedule: AgentSchedule)

    suspend fun cancel(id: String)

    suspend fun runNow(id: String)
}

private enum class ScheduleOperation {
    SET,
    LIST,
    REMOVE,
    RUN,
}

class ManageMochiScheduleTool(
    private val store: AgentScheduleStore,
    private val controller: AgentScheduleController,
) : AgentTool {
    override val name = "manage_mochi_schedule"
    override val schema = functionToolSchema(
        name = name,
        description =
            "Create, update, list, remove, or immediately run Mochi Agent " +
                "schedules shown in Planner.",
        properties = buildJsonObject {
            put("operation", enumSchema(ScheduleOperation.entries))
            put("id", stringSchema("Schedule ID for update, remove, or run"))
            put("name", stringSchema("Short user-facing schedule name"))
            put("prompt", stringSchema("Prompt executed by the main Mochi Agent"))
            put(
                "schedule_type",
                enumSchema(AgentScheduleType.entries),
            )
            put(
                "run_at",
                stringSchema("ISO-8601 instant for a one-time schedule"),
            )
            put(
                "local_time",
                stringSchema("Local HH:mm time for daily or weekly schedules"),
            )
            put(
                "days",
                stringSchema(
                    "Comma-separated weekdays for weekly schedules, " +
                        "for example MONDAY,FRIDAY",
                ),
            )
            put(
                "interval_minutes",
                integerSchema("Interval of at least 15 minutes"),
            )
            put("timezone", stringSchema("IANA timezone such as Asia/Shanghai"))
            put(
                "enabled",
                buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether the schedule is active")
                },
            )
        },
        required = listOf("operation"),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope =
        when (arguments.requiredOperation()) {
            ScheduleOperation.SET -> set(arguments)
            ScheduleOperation.LIST -> list()
            ScheduleOperation.REMOVE -> remove(arguments)
            ScheduleOperation.RUN -> run(arguments)
        }

    private suspend fun set(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.string("id")
        val existing = id?.let { store.get(it) }
        if (id != null && existing == null) {
            throw AgentScheduleNotFoundException(id)
        }
        val type = arguments.string("schedule_type")
            ?.uppercase()
            ?.let(AgentScheduleType::valueOf)
            ?: existing?.type
            ?: throw ToolInputException("schedule_type is required")
        val timezone = arguments.string("timezone")
            ?.let(ZoneId::of)
            ?: existing?.timezone
            ?: ZoneId.systemDefault()
        val draft = AgentScheduleDraft(
            name = arguments.string("name")
                ?: existing?.name
                ?: throw ToolInputException("name is required"),
            prompt = arguments.string("prompt")
                ?: existing?.prompt
                ?: throw ToolInputException("prompt is required"),
            type = type,
            runAt = arguments.string("run_at")
                ?.let(Instant::parse)
                ?: existing?.runAt,
            localTime = arguments.string("local_time")
                ?.let(LocalTime::parse)
                ?: existing?.localTime,
            daysOfWeek = arguments.string("days")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.mapTo(mutableSetOf()) {
                    DayOfWeek.valueOf(it.uppercase())
                }
                ?: existing?.daysOfWeek
                ?: emptySet(),
            intervalMinutes = arguments.long("interval_minutes")
                ?: existing?.intervalMinutes,
            timezone = timezone,
            enabled = arguments.boolean("enabled")
                ?: existing?.enabled
                ?: true,
        )
        val schedule = store.set(id, draft)
        controller.sync(schedule)
        return ToolResultEnvelope.success(schedule.toJson())
    }

    private suspend fun list(): ToolResultEnvelope =
        ToolResultEnvelope.success(
            buildJsonObject {
                val schedules = store.list()
                put(
                    "schedules",
                    JsonArray(schedules.map(AgentSchedule::toJson)),
                )
                put("count", schedules.size)
            },
        )

    private suspend fun remove(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.requiredId()
        store.remove(id)
        controller.cancel(id)
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("removed", true)
                put("id", id)
            },
        )
    }

    private suspend fun run(arguments: JsonObject): ToolResultEnvelope {
        val id = arguments.requiredId()
        store.get(id) ?: throw AgentScheduleNotFoundException(id)
        controller.runNow(id)
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("accepted", true)
                put("id", id)
            },
        )
    }
}

private fun AgentSchedule.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("prompt", prompt)
    put("schedule_type", type.name.lowercase())
    runAt?.let { put("run_at", it.toString()) }
    localTime?.let { put("local_time", it.toString()) }
    if (daysOfWeek.isNotEmpty()) {
        put(
            "days",
            daysOfWeek.sortedBy(DayOfWeek::getValue)
                .joinToString(",") { it.name },
        )
    }
    intervalMinutes?.let { put("interval_minutes", it) }
    put("timezone", timezone.id)
    put("enabled", enabled)
    nextRunAt?.let { put("next_run_at", it.toString()) }
    lastResult?.let { put("last_result", it.name.lowercase()) }
}

private fun JsonObject.requiredOperation(): ScheduleOperation =
    string("operation")
        ?.uppercase()
        ?.let {
            runCatching { ScheduleOperation.valueOf(it) }.getOrNull()
        }
        ?: throw ToolInputException(
            "operation must be set, list, remove, or run",
        )

private fun JsonObject.requiredId(): String =
    string("id") ?: throw ToolInputException("id is required")

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun stringSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
    }

private fun integerSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

private fun <T : Enum<T>> enumSchema(values: List<T>): JsonObject =
    buildJsonObject {
        put("type", "string")
        put(
            "enum",
            JsonArray(values.map { JsonPrimitive(it.name.lowercase()) }),
        )
    }
