package com.example.mochi_pet.core.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ScheduleDay(
    val plan: AgentSchedule?,
    val plannedAt: Instant?,
    val runs: List<AgentScheduleRun>,
) {
    val id: String get() = plan?.id ?: runs.first().scheduleId
    val time: Instant get() = runs.firstOrNull()?.createdAt ?: requireNotNull(plannedAt)
    val name: String get() = runs.lastOrNull()?.name ?: requireNotNull(plan).name
}

/** Past dates are projections of the current rule, never proof that a task ran. */
fun AgentSchedule.projectedTime(date: LocalDate, zone: ZoneId, now: Instant): Instant? {
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant()
    if (createdAt >= end || (endsAt != null && endsAt < start)) return null
    val past = end <= now
    if (!past && !enabled) return null
    val lower = maxOf(start, createdAt, if (past) createdAt else nextRunAt ?: return null)
    if (lower >= end) return null
    val anchor = if (past) {
        if (type == AgentScheduleType.EVERY) createdAt.plusSeconds(requireNotNull(intervalMinutes) * 60) else createdAt
    } else requireNotNull(nextRunAt)
    var candidate = when (type) {
        AgentScheduleType.ONCE -> runAt ?: return null
        AgentScheduleType.EVERY -> {
            val seconds = requireNotNull(intervalMinutes) * 60
            val steps = maxOf(0L, Math.floorDiv(lower.epochSecond - anchor.epochSecond, seconds))
            anchor.plusSeconds(steps * seconds).let { if (it < lower) it.plusSeconds(seconds) else it }
        }
        else -> nextAgentScheduleRun(toDraft(enabled = true), lower.minusNanos(1)) ?: return null
    }
    if (!past && maxRuns != null) {
        var remaining = maxRuns - plannedRuns
        var occurrence = anchor
        while (occurrence < candidate && remaining > 0) {
            remaining--
            occurrence = nextAgentScheduleRun(toDraft(enabled = true), occurrence) ?: return null
        }
        if (remaining <= 0) return null
        candidate = occurrence
    }
    return candidate.takeIf { it >= lower && it < end && (endsAt == null || it <= endsAt) }
}
