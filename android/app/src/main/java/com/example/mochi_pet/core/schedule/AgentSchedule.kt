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
    CANCELLED,
    INTERRUPTED,
}

enum class ScheduleRunState { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, INTERRUPTED, SKIPPED_BUSY }

data class AgentScheduleRun(
    val id: String,
    val scheduleId: String,
    val revision: Long,
    val name: String,
    val prompt: String,
    val manual: Boolean,
    val plannedAt: Instant?,
    val state: ScheduleRunState,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val output: String? = null,
    val error: String? = null,
)

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
    val endsAt: Instant? = null,
    val maxRuns: Int? = null,
    val plannedRuns: Int = 0,
    val revision: Long = 0,
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
    val endsAt: Instant? = null,
    val maxRuns: Int? = null,
    val indefinite: Boolean = false,
    val expectedRevision: Long? = null,
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
    }?.takeIf { draft.endsAt == null || it <= draft.endsAt }
}

fun nextAgentScheduleRun(
    schedule: AgentSchedule,
    after: Instant,
): Instant? =
    if (schedule.maxRuns != null && schedule.plannedRuns >= schedule.maxRuns) null
    else nextAgentScheduleRun(schedule.toDraft(), after)

fun AgentSchedule.toDraft(enabled: Boolean = this.enabled): AgentScheduleDraft =
    AgentScheduleDraft(
        name = name,
        prompt = prompt,
        type = type,
        runAt = runAt,
        localTime = localTime,
        daysOfWeek = daysOfWeek,
        intervalMinutes = intervalMinutes,
        timezone = timezone,
        enabled = enabled,
        endsAt = endsAt,
        maxRuns = maxRuns,
        indefinite = endsAt == null && maxRuns == null,
        expectedRevision = revision,
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
        if (zoneId.rules.getValidOffsets(date.atTime(localTime)).isEmpty()) continue
        val candidate = ZonedDateTime.of(date, localTime, zoneId).toInstant()
        if (candidate.isAfter(after)) {
            return candidate
        }
    }
    error("Could not calculate the next local schedule run")
}
