package com.example.mochi_pet.core.schedule

import com.example.mochi_pet.core.database.dao.AgentScheduleDao
import com.example.mochi_pet.core.database.entity.AgentScheduleEntity
import com.example.mochi_pet.core.database.entity.AgentScheduleRunEntity
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.map

interface AgentScheduleStore {
    val changes: kotlinx.coroutines.flow.Flow<Unit>
    suspend fun set(id: String? = null, draft: AgentScheduleDraft): AgentSchedule
    suspend fun get(id: String): AgentSchedule?
    suspend fun list(): List<AgentSchedule>
    suspend fun listForDate(date: LocalDate): List<AgentSchedule>
    suspend fun remove(id: String)
    suspend fun queue(id: String, manual: Boolean, now: Instant): AgentScheduleRun?
    suspend fun run(id: String): AgentScheduleRun?
    suspend fun runs(id: String, limit: Int = 20, offset: Int = 0): List<AgentScheduleRun>
    suspend fun activeRuns(): List<AgentScheduleRun>
    suspend fun startRun(id: String): AgentScheduleRun?
    suspend fun finishRun(id: String, state: ScheduleRunState, output: String? = null, error: String? = null): AgentSchedule?
}

class RoomAgentScheduleRepository(
    private val dao: AgentScheduleDao,
    private val clock: Clock = Clock.systemUTC(),
) : AgentScheduleStore {
    override val changes = dao.observeChanges().map { }
    override suspend fun set(id: String?, draft: AgentScheduleDraft): AgentSchedule {
        validate(draft)
        val now = clock.instant()
        val existingEntity = id?.let { dao.getById(it) ?: throw AgentScheduleNotFoundException(it) }
        val existing = existingEntity?.toDomain()
        val first = nextAgentScheduleRun(draft.copy(enabled = true, endsAt = null), now.minusMillis(1))
        require(first != null || !draft.enabled) { "Execution time must be in the future" }
        val end = when {
            draft.type == AgentScheduleType.ONCE -> null
            draft.endsAt != null -> draft.endsAt
            draft.maxRuns != null || draft.indefinite -> null
            existing != null -> existing.endsAt
            else -> requireNotNull(first).atZone(draft.timezone)
                .plusDays(if (draft.type == AgentScheduleType.EVERY) 7 else 30).toInstant().minusMillis(1)
        }
        val plan = AgentSchedule(
            id = existing?.id ?: "schedule_${UUID.randomUUID()}",
            name = draft.name.trim(), prompt = draft.prompt.trim(), type = draft.type,
            runAt = draft.runAt, localTime = draft.localTime, daysOfWeek = draft.daysOfWeek,
            intervalMinutes = draft.intervalMinutes, timezone = draft.timezone, enabled = draft.enabled,
            nextRunAt = null, lastResult = existing?.lastResult, createdAt = existing?.createdAt ?: now,
            updatedAt = now, endsAt = end, maxRuns = draft.maxRuns,
            plannedRuns = existing?.plannedRuns ?: 0, revision = (existing?.revision ?: -1) + 1,
        )
        val next = if (existing?.nextRunAt != null && sameTiming(existing, plan) && existing.enabled == plan.enabled) {
            existing.nextRunAt.takeIf { (end == null || it <= end) && (plan.maxRuns == null || plan.plannedRuns < plan.maxRuns) }
        } else {
            nextAgentScheduleRun(plan, now.minusMillis(1))
        }
        require(!plan.enabled || next != null) { "Schedule has ended; extend its end condition before enabling it" }
        val saved = plan.copy(nextRunAt = next)
        dao.savePlan(saved.toEntity(), if (id == null) null else requireNotNull(draft.expectedRevision) {
            "An update requires the current revision"
        }, existingEntity)
        return saved
    }

    override suspend fun get(id: String): AgentSchedule? = dao.getById(id)?.toDomain()
    override suspend fun list(): List<AgentSchedule> = dao.listAll().map { it.toDomain() }
    override suspend fun listForDate(date: LocalDate): List<AgentSchedule> = list().filter {
        it.nextRunAt?.atZone(it.timezone)?.toLocalDate() == date || (!it.enabled && date == LocalDate.now(clock))
    }
    override suspend fun remove(id: String) {
        dao.delete(dao.getById(id) ?: throw AgentScheduleNotFoundException(id))
    }

    override suspend fun queue(id: String, manual: Boolean, now: Instant): AgentScheduleRun? {
        val plan = get(id) ?: throw AgentScheduleNotFoundException(id)
        val due = plan.nextRunAt
        if (!manual && (!plan.enabled || due == null || due > now)) return null
        val run = AgentScheduleRun(
            id = UUID.randomUUID().toString(), scheduleId = id, revision = plan.revision,
            name = plan.name, prompt = plan.prompt, manual = manual, plannedAt = if (manual) null else due,
            state = ScheduleRunState.QUEUED, createdAt = now,
        )
        val advanced = if (manual) null else {
            val counted = plan.copy(plannedRuns = plan.plannedRuns + 1)
            val next = nextAgentScheduleRun(counted, now)
            counted.copy(nextRunAt = next, enabled = next != null)
        }
        return dao.reserve(run.toEntity(), advanced?.toEntity())?.toDomain()
    }

    override suspend fun run(id: String): AgentScheduleRun? = dao.run(id)?.toDomain()
    override suspend fun runs(id: String, limit: Int, offset: Int): List<AgentScheduleRun> {
        require(limit in 1..100 && offset >= 0) { "Invalid result page" }
        return dao.runs(id, limit, offset).map { it.toDomain() }
    }
    override suspend fun activeRuns(): List<AgentScheduleRun> = dao.activeRuns().map { it.toDomain() }
    override suspend fun startRun(id: String): AgentScheduleRun? {
        val run = run(id) ?: return null
        val plan = get(run.scheduleId)
        if (plan == null || plan.revision != run.revision || (!run.manual && plan.endsAt != null && plan.endsAt < clock.instant())) {
            finishRun(id, ScheduleRunState.CANCELLED, error = "Plan was changed or deleted before execution")
            return null
        }
        if (dao.startRun(id, clock.millis()) != 1) return null
        return run(id)
    }

    override suspend fun finishRun(id: String, state: ScheduleRunState, output: String?, error: String?): AgentSchedule? {
        require(state !in setOf(ScheduleRunState.QUEUED, ScheduleRunState.RUNNING, ScheduleRunState.SKIPPED_BUSY))
        val run = run(id) ?: throw IllegalArgumentException("Execution not found: $id")
        val now = clock.instant()
        dao.finish(run.copy(state = state, finishedAt = now, output = output, error = error).toEntity()) { entity ->
            val plan = entity.toDomain()
            val next = if (!run.manual && plan.type == AgentScheduleType.EVERY) {
                nextAgentScheduleRun(plan, now)
            } else {
                plan.nextRunAt
            }
            plan.copy(nextRunAt = next, enabled = plan.enabled && next != null,
                lastResult = AgentScheduleResult.valueOf(state.name), updatedAt = now).toEntity()
        }
        return get(run.scheduleId)
    }
}

class AgentScheduleNotFoundException(id: String) : IllegalStateException("Mochi task not found: $id")

private fun sameTiming(a: AgentSchedule, b: AgentSchedule): Boolean =
    a.type == b.type && a.runAt == b.runAt && a.localTime == b.localTime &&
        a.daysOfWeek == b.daysOfWeek && a.intervalMinutes == b.intervalMinutes && a.timezone == b.timezone

private fun validate(draft: AgentScheduleDraft) {
    require(draft.name.trim().length in 1..120) { "Schedule name must contain 1 to 120 characters" }
    require(draft.prompt.trim().length in 1..4_000) { "Schedule prompt must contain 1 to 4000 characters" }
    require(draft.endsAt == null || draft.maxRuns == null) { "Choose an end date or count, not both" }
    require(draft.maxRuns == null || draft.maxRuns in 1..100_000) { "Invalid occurrence count" }
    require(!draft.indefinite || (draft.endsAt == null && draft.maxRuns == null)) { "Indefinite recurrence cannot have an end condition" }
    when (draft.type) {
        AgentScheduleType.ONCE -> requireNotNull(draft.runAt) { "One-time task requires run_at" }
        AgentScheduleType.DAILY -> requireNotNull(draft.localTime) { "Daily task requires local_time" }
        AgentScheduleType.WEEKLY -> {
            requireNotNull(draft.localTime) { "Weekly task requires local_time" }
            require(draft.daysOfWeek.isNotEmpty()) { "Weekly task requires days" }
        }
        AgentScheduleType.EVERY -> require(draft.intervalMinutes != null && draft.intervalMinutes in 15L..525_600L) {
            "Interval must be between 15 minutes and one year"
        }
    }
}

private fun AgentSchedule.toEntity() = AgentScheduleEntity(
    id, name, prompt, type.name, runAt?.toEpochMilli(), localTime?.toString(),
    daysOfWeek.sortedBy(DayOfWeek::getValue).joinToString(",") { it.name },
    intervalMinutes, timezone.id, enabled, nextRunAt?.toEpochMilli(), lastResult?.name,
    createdAt.toEpochMilli(), updatedAt.toEpochMilli(), endsAt?.toEpochMilli(), maxRuns, plannedRuns, revision,
)

private fun AgentScheduleEntity.toDomain() = AgentSchedule(
    id, name, prompt, AgentScheduleType.valueOf(type), runAtEpochMillis?.let(Instant::ofEpochMilli),
    localTime?.let(LocalTime::parse), daysOfWeek.split(',').filter(String::isNotBlank).mapTo(mutableSetOf(), DayOfWeek::valueOf),
    intervalMinutes, ZoneId.of(timezone), enabled, nextRunAtEpochMillis?.let(Instant::ofEpochMilli),
    lastResult?.let(AgentScheduleResult::valueOf), Instant.ofEpochMilli(createdAtEpochMillis),
    Instant.ofEpochMilli(updatedAtEpochMillis), endsAtEpochMillis?.let(Instant::ofEpochMilli), maxRuns, plannedRuns, revision,
)

private fun AgentScheduleRun.toEntity() = AgentScheduleRunEntity(
    id, scheduleId, revision, name, prompt, manual, plannedAt?.toEpochMilli(), state.name,
    createdAt.toEpochMilli(), startedAt?.toEpochMilli(), finishedAt?.toEpochMilli(), output, error,
)

private fun AgentScheduleRunEntity.toDomain() = AgentScheduleRun(
    id, scheduleId, revision, name, prompt, manual, plannedAt?.let(Instant::ofEpochMilli),
    ScheduleRunState.valueOf(state), Instant.ofEpochMilli(createdAt),
    startedAt?.let(Instant::ofEpochMilli), finishedAt?.let(Instant::ofEpochMilli), output, error,
)
