package com.example.mochi_pet.core.schedule

import com.example.mochi_pet.core.database.dao.AgentScheduleDao
import com.example.mochi_pet.core.database.entity.AgentScheduleEntity
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

interface AgentScheduleStore {
    suspend fun set(
        id: String? = null,
        draft: AgentScheduleDraft,
    ): AgentSchedule

    suspend fun get(id: String): AgentSchedule?

    suspend fun list(): List<AgentSchedule>

    suspend fun listForDate(date: LocalDate): List<AgentSchedule>

    suspend fun remove(id: String)

    suspend fun claimDue(
        id: String,
        now: Instant,
    ): AgentSchedule?

    suspend fun recordResult(
        id: String,
        result: AgentScheduleResult,
        completedAt: Instant,
        advanceSchedule: Boolean = true,
    ): AgentSchedule
}

class RoomAgentScheduleRepository(
    private val dao: AgentScheduleDao,
    private val clock: Clock = Clock.systemUTC(),
) : AgentScheduleStore {
    override suspend fun set(
        id: String?,
        draft: AgentScheduleDraft,
    ): AgentSchedule {
        validate(draft)
        val now = clock.instant()
        val existing = if (id == null) null else dao.getById(id)
        val schedule = AgentSchedule(
            id = existing?.id ?: "schedule_${UUID.randomUUID()}",
            name = draft.name.trim(),
            prompt = draft.prompt.trim(),
            type = draft.type,
            runAt = draft.runAt,
            localTime = draft.localTime,
            daysOfWeek = draft.daysOfWeek,
            intervalMinutes = draft.intervalMinutes,
            timezone = draft.timezone,
            enabled = draft.enabled,
            nextRunAt = nextAgentScheduleRun(draft, now.minusMillis(1)),
            lastResult = existing?.lastResult?.let(AgentScheduleResult::valueOf),
            createdAt = existing?.createdAtEpochMillis
                ?.let(Instant::ofEpochMilli)
                ?: now,
            updatedAt = now,
        )
        dao.upsert(schedule.toEntity())
        return schedule
    }

    override suspend fun get(id: String): AgentSchedule? =
        dao.getById(id)?.toDomain()

    override suspend fun list(): List<AgentSchedule> =
        dao.listAll().map(AgentScheduleEntity::toDomain)

    override suspend fun listForDate(date: LocalDate): List<AgentSchedule> =
        dao.listAll()
            .map(AgentScheduleEntity::toDomain)
            .filter { schedule ->
                schedule.nextRunAt
                    ?.atZone(schedule.timezone)
                    ?.toLocalDate() == date ||
                    (!schedule.enabled && date == LocalDate.now(clock))
            }

    override suspend fun remove(id: String) {
        val existing = dao.getById(id)
            ?: throw AgentScheduleNotFoundException(id)
        dao.delete(existing)
    }

    override suspend fun claimDue(
        id: String,
        now: Instant,
    ): AgentSchedule? {
        if (dao.claimDue(id, now.toEpochMilli()) != 1) {
            return null
        }
        return dao.getById(id)?.toDomain()
    }

    override suspend fun recordResult(
        id: String,
        result: AgentScheduleResult,
        completedAt: Instant,
        advanceSchedule: Boolean,
    ): AgentSchedule {
        val existing = dao.getById(id)?.toDomain()
            ?: throw AgentScheduleNotFoundException(id)
        val enabled = if (advanceSchedule) {
            existing.enabled && existing.type != AgentScheduleType.ONCE
        } else {
            existing.enabled
        }
        val updated = existing.copy(
            enabled = enabled,
            nextRunAt = if (!advanceSchedule) {
                existing.nextRunAt
            } else if (enabled) {
                nextAgentScheduleRun(existing, completedAt)
            } else {
                null
            },
            lastResult = result,
            updatedAt = completedAt,
        )
        dao.upsert(updated.toEntity())
        return updated
    }
}

class AgentScheduleNotFoundException(id: String) :
    IllegalStateException("Agent schedule not found: $id")

private fun validate(draft: AgentScheduleDraft) {
    require(draft.name.trim().isNotEmpty()) {
        "Schedule name must not be empty"
    }
    require(draft.name.trim().length <= 120) {
        "Schedule name is too long"
    }
    require(draft.prompt.trim().isNotEmpty()) {
        "Schedule prompt must not be empty"
    }
    require(draft.prompt.trim().length <= 4_000) {
        "Schedule prompt is too long"
    }
    when (draft.type) {
        AgentScheduleType.ONCE -> {
            requireNotNull(draft.runAt) {
                "One-time schedule requires run_at"
            }
        }
        AgentScheduleType.DAILY -> {
            requireNotNull(draft.localTime) {
                "Daily schedule requires local_time"
            }
        }
        AgentScheduleType.WEEKLY -> {
            requireNotNull(draft.localTime) {
                "Weekly schedule requires local_time"
            }
            require(draft.daysOfWeek.isNotEmpty()) {
                "Weekly schedule requires at least one day"
            }
        }
        AgentScheduleType.EVERY -> {
            require((draft.intervalMinutes ?: 0L) >= 15L) {
                "Interval must be at least 15 minutes"
            }
        }
    }
}

private fun AgentSchedule.toEntity(): AgentScheduleEntity =
    AgentScheduleEntity(
        id = id,
        name = name,
        prompt = prompt,
        type = type.name,
        runAtEpochMillis = runAt?.toEpochMilli(),
        localTime = localTime?.toString(),
        daysOfWeek = daysOfWeek
            .sortedBy(DayOfWeek::getValue)
            .joinToString(",") { it.name },
        intervalMinutes = intervalMinutes,
        timezone = timezone.id,
        enabled = enabled,
        nextRunAtEpochMillis = nextRunAt?.toEpochMilli(),
        lastResult = lastResult?.name,
        createdAtEpochMillis = createdAt.toEpochMilli(),
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun AgentScheduleEntity.toDomain(): AgentSchedule =
    AgentSchedule(
        id = id,
        name = name,
        prompt = prompt,
        type = AgentScheduleType.valueOf(type),
        runAt = runAtEpochMillis?.let(Instant::ofEpochMilli),
        localTime = localTime?.let(LocalTime::parse),
        daysOfWeek = daysOfWeek
            .split(',')
            .filter(String::isNotBlank)
            .mapTo(mutableSetOf(), DayOfWeek::valueOf),
        intervalMinutes = intervalMinutes,
        timezone = ZoneId.of(timezone),
        enabled = enabled,
        nextRunAt = nextRunAtEpochMillis?.let(Instant::ofEpochMilli),
        lastResult = lastResult?.let(AgentScheduleResult::valueOf),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )
