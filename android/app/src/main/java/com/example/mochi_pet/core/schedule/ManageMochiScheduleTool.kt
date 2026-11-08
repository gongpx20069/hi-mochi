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

    suspend fun runNow(id: String): AgentScheduleRun
}

private enum class ScheduleOperation {
    CREATE,
    UPDATE,
    GET,
    LIST,
    DELETE,
    RUN,
    RESULTS,
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
            put("id", stringSchema("Schedule ID for update, delete, get, results, or run"))
            put("output_offset", integerSchema("Character offset into a specific run output"))
            put("revision", integerSchema("Required current revision for update"))
            put("run_id", stringSchema("Exact execution ID for a result"))
            put("ends_at", stringSchema("Inclusive ISO-8601 cutoff; updates retain the existing cutoff unless explicitly changed"))
            put("max_runs", integerSchema("Number of planned occurrences; manual runs do not consume it"))
            put("indefinite", buildJsonObject { put("type", "boolean") })
            put("confirmed", buildJsonObject { put("type", "boolean") })
            put("limit", integerSchema("Page size, 1 to 100"))
            put("offset", integerSchema("Zero-based page offset"))
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
            ScheduleOperation.CREATE -> {
                require(arguments.string("id") == null) { "create must not include an id" }
                set(arguments)
            }
            ScheduleOperation.UPDATE -> {
                arguments.requiredId()
                set(arguments)
            }
            ScheduleOperation.GET -> ToolResultEnvelope.success(
                (store.get(arguments.requiredId()) ?: throw AgentScheduleNotFoundException(arguments.requiredId())).toJson(),
            )
            ScheduleOperation.LIST -> list(arguments)
            ScheduleOperation.DELETE -> remove(arguments)
            ScheduleOperation.RUN -> run(arguments)
            ScheduleOperation.RESULTS -> results(arguments)
        }

    private suspend fun set(arguments: JsonObject): ToolResultEnvelope {
        require(listOf(arguments.string("ends_at") != null, arguments.long("max_runs") != null,
            arguments.boolean("indefinite") == true).count { it } <= 1) {
            "Choose exactly one end condition: ends_at, max_runs, or indefinite"
        }
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
            expectedRevision = if (id == null) null else arguments.long("revision")
                ?: throw ToolInputException("update requires revision from get/list"),
            endsAt = when {
                arguments.boolean("indefinite") == true || arguments.long("max_runs") != null -> null
                else -> arguments.string("ends_at")?.let(Instant::parse) ?: existing?.endsAt
            },
            maxRuns = when {
                arguments.boolean("indefinite") == true || arguments.string("ends_at") != null -> null
                else -> arguments.long("max_runs")?.also { require(it in 1..100_000) }?.toInt() ?: existing?.maxRuns
            },
            indefinite = arguments.boolean("indefinite")
                ?: (existing != null && existing.endsAt == null && existing.maxRuns == null &&
                    arguments.string("ends_at") == null && arguments.long("max_runs") == null),
        )
        val schedule = store.set(id, draft)
        controller.sync(schedule)
        return ToolResultEnvelope.success(schedule.toJson())
    }

    private suspend fun list(arguments: JsonObject): ToolResultEnvelope =
        ToolResultEnvelope.success(
            buildJsonObject {
                val limit = (arguments.long("limit") ?: 20).also { require(it in 1..100) }.toInt()
                val offset = (arguments.long("offset") ?: 0).also { require(it in 0..Int.MAX_VALUE) }.toInt()
                val all = store.list()
                val schedules = all.drop(offset).take(limit)
                put(
                    "schedules",
                    JsonArray(schedules.map(AgentSchedule::toJson)),
                )
                put("count", schedules.size)
                put("total_count", all.size)
                if (offset + schedules.size < all.size) put("next_offset", offset + schedules.size)
            },
        )

    private suspend fun remove(arguments: JsonObject): ToolResultEnvelope {
        require(arguments.boolean("confirmed") == true) { "Deletion requires confirmed=true for explicit user intent" }
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
        val run = controller.runNow(id)
        return ToolResultEnvelope.success(
            buildJsonObject {
                put("accepted", true)
                put("id", id)
                put("run_id", run.id)
                put("state", run.state.name.lowercase())
                put("schedule_changed", false)
            },
        )
    }

    private suspend fun results(arguments: JsonObject): ToolResultEnvelope {
        val runId = arguments.string("run_id")
        if (runId != null) {
            val run = store.run(runId) ?: throw ToolInputException("Execution not found")
            val offset = (arguments.long("output_offset") ?: 0).also { require(it in 0..Int.MAX_VALUE) }.toInt()
            return ToolResultEnvelope.success(run.toJson(outputOffset = offset))
        }
        val limit = (arguments.long("limit") ?: 20).also { require(it in 1..100) }.toInt()
        val offset = (arguments.long("offset") ?: 0).also { require(it in 0..Int.MAX_VALUE) }.toInt()
        val runs = store.runs(arguments.requiredId(), limit, offset)
        return ToolResultEnvelope.success(buildJsonObject {
            put("runs", JsonArray(runs.map { it.toJson(summary = true) }))
            put("offset", offset)
            if (runs.size == limit) put("next_offset", offset + limit)
        })
    }
}

private fun AgentScheduleRun.toJson(summary: Boolean = false, outputOffset: Int = 0) = buildJsonObject {
    put("run_id", id)
    put("id", scheduleId)
    put("revision", revision)
    put("name", name)
    put("trigger", if (manual) "manual" else "scheduled")
    put("state", state.name.lowercase())
    put("created_at", createdAt.toString())
    startedAt?.let { put("started_at", it.toString()) }
    finishedAt?.let { put("finished_at", it.toString()) }
    output?.let {
        val length = if (summary) 240 else 24_000
        put("output", it.drop(outputOffset).take(length))
        if (it.length > outputOffset + length) put("next_output_offset", outputOffset + length)
    }
    error?.let { put("error", it) }
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
    put("revision", revision)
    put("planned_runs", plannedRuns)
    endsAt?.let { put("ends_at", it.toString()) }
    maxRuns?.let { put("max_runs", it) }
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
            "operation must be create, update, get, list, delete, run, or results",
        )

private fun JsonObject.requiredId(): String =
    string("id") ?: throw ToolInputException("id is required")

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun JsonObject.long(name: String): Long? {
    val value = this[name] ?: return null
    return value.jsonPrimitive.longOrNull ?: throw ToolInputException("$name must be an integer")
}

private fun JsonObject.boolean(name: String): Boolean? {
    val value = this[name] ?: return null
    return value.jsonPrimitive.booleanOrNull ?: throw ToolInputException("$name must be a boolean")
}

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
