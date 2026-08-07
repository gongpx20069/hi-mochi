package com.example.mochi_pet.core.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class AgentScheduleType {
    ONCE,
    DAILY,
    WEEKLY,
    EVERY,
}

enum class AgentScheduleResult {
    SUCCESS,
    FAILED,
}

data class AgentSchedule(
    val id: String,
    val name: String,
    val prompt: String,
    val type: AgentScheduleType,
    val runAt: Instant?,
    val localTime: LocalTime?,
    val daysOfWeek: Set<DayOfWeek>,
    val intervalMinutes: Long?,
    val timezone: ZoneId,
    val enabled: Boolean,
    val nextRunAt: Instant?,
    val lastResult: AgentScheduleResult?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AgentScheduleDraft(
    val name: String,
    val prompt: String,
    val type: AgentScheduleType,
    val runAt: Instant? = null,
    val localTime: LocalTime? = null,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val intervalMinutes: Long? = null,
    val timezone: ZoneId = ZoneId.systemDefault(),
    val enabled: Boolean = true,
)

fun nextAgentScheduleRun(
    draft: AgentScheduleDraft,
    after: Instant,
): Instant? {
    if (!draft.enabled) {
        return null
    }
    return when (draft.type) {
        AgentScheduleType.ONCE ->
            draft.runAt?.takeIf { it.isAfter(after) }
        AgentScheduleType.DAILY ->
            nextLocalRun(
                after = after,
                zoneId = draft.timezone,
                localTime = requireNotNull(draft.localTime),
            ) { true }
        AgentScheduleType.WEEKLY ->
            nextLocalRun(
                after = after,
                zoneId = draft.timezone,
                localTime = requireNotNull(draft.localTime),
            ) { date -> date.dayOfWeek in draft.daysOfWeek }
        AgentScheduleType.EVERY ->
            after.plusSeconds(requireNotNull(draft.intervalMinutes) * 60L)
    }
}

fun nextAgentScheduleRun(
    schedule: AgentSchedule,
    after: Instant,
): Instant? =
    nextAgentScheduleRun(
        AgentScheduleDraft(
            name = schedule.name,
            prompt = schedule.prompt,
            type = schedule.type,
            runAt = schedule.runAt,
            localTime = schedule.localTime,
            daysOfWeek = schedule.daysOfWeek,
            intervalMinutes = schedule.intervalMinutes,
            timezone = schedule.timezone,
            enabled = schedule.enabled,
        ),
        after,
    )

private fun nextLocalRun(
    after: Instant,
    zoneId: ZoneId,
    localTime: LocalTime,
    dateMatches: (LocalDate) -> Boolean,
): Instant {
    val localAfter = after.atZone(zoneId)
    for (offset in 0..8) {
        val date = localAfter.toLocalDate().plusDays(offset.toLong())
        if (!dateMatches(date)) {
            continue
        }
        val candidate = ZonedDateTime.of(date, localTime, zoneId).toInstant()
        if (candidate.isAfter(after)) {
            return candidate
        }
    }
    error("Could not calculate the next local schedule run")
}
