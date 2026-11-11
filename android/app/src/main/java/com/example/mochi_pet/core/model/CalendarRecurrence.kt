package com.example.mochi_pet.core.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class OccurrenceScope { THIS, FUTURE, ALL }

data class CalendarRecurrence(
    val frequency: String,
    val interval: Int,
    val days: Set<DayOfWeek>,
    val monthDay: Int?,
    val count: Int?,
    val until: Instant?,
) {
    fun matches(date: LocalDate, first: LocalDate): Boolean {
        val cycle = when (frequency) {
            "DAILY" -> ChronoUnit.DAYS.between(first, date)
            "WEEKLY" -> ChronoUnit.WEEKS.between(
                first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            )
            "MONTHLY" -> ChronoUnit.MONTHS.between(first.withDayOfMonth(1), date.withDayOfMonth(1))
            else -> (date.year - first.year).toLong()
        }
        if (cycle % interval != 0L) return false
        if (frequency == "WEEKLY" && date.dayOfWeek !in days.ifEmpty { setOf(first.dayOfWeek) }) return false
        if (frequency == "YEARLY" && date.month != first.month) return false
        if (frequency in setOf("MONTHLY", "YEARLY")) {
            val requestedDay = monthDay ?: first.dayOfMonth
            val day = if (requestedDay < 0) date.lengthOfMonth() + requestedDay + 1 else requestedDay
            if (date.dayOfMonth != day) return false
        }
        return days.isEmpty() || date.dayOfWeek in days
    }

    companion object {
        fun parse(text: String, zone: ZoneId): CalendarRecurrence {
            val parts = text.removePrefix("RRULE:").uppercase().split(';').map {
                val pair = it.split('=', limit = 2)
                require(pair.size == 2 && pair[1].isNotBlank()) { "Invalid recurrence rule" }
                pair[0] to pair[1]
            }
            require(parts.map { it.first }.distinct().size == parts.size) { "Duplicate recurrence field" }
            val fields = parts.toMap()
            require(fields.keys.all { it in setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "COUNT", "UNTIL") }) {
                "Unsupported recurrence field; use FREQ, INTERVAL, BYDAY, BYMONTHDAY, COUNT or UNTIL"
            }
            val frequency = fields["FREQ"]
            require(frequency in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) { "Unsupported recurrence frequency" }
            val interval = fields["INTERVAL"]?.toInt() ?: 1
            require(interval in 1..100) { "Recurrence interval must be between 1 and 100" }
            val days = fields["BYDAY"]?.split(',')?.mapTo(mutableSetOf()) { value ->
                DayOfWeek.entries.firstOrNull { it.name.take(2) == value }
                    ?: throw IllegalArgumentException("BYDAY requires MO,TU,WE,TH,FR,SA,SU; ordinal weekdays are unsupported")
            }.orEmpty()
            val monthDay = fields["BYMONTHDAY"]?.toInt()
            require(monthDay == null || (monthDay in -31..31 && monthDay != 0)) { "Invalid BYMONTHDAY" }
            require(monthDay == null || frequency in setOf("MONTHLY", "YEARLY")) { "BYMONTHDAY requires MONTHLY or YEARLY" }
            val count = fields["COUNT"]?.toInt()
            require(count == null || count in 1..100_000) { "COUNT must be between 1 and 100000" }
            require(count == null || fields["UNTIL"] == null) { "Use COUNT or UNTIL, not both" }
            val until = fields["UNTIL"]?.let {
                if (it.length == 8) {
                    LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE)
                        .plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
                } else {
                    Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").parse(it))
                }
            }
            return CalendarRecurrence(requireNotNull(frequency), interval, days, monthDay, count, until)
        }
    }
}

fun CalendarEventDraft.withDefaultRecurrenceEnd(): CalendarEventDraft {
    val rule = recurrenceRule ?: return this
    val parsed = CalendarRecurrence.parse(rule, timezone)
    if (parsed.until != null || parsed.count != null || indefiniteRecurrence) return this
    val first = startAt.atZone(timezone)
    var second = first.toLocalDate().plusDays(1)
    var examined = 0
    while (!parsed.matches(second, first.toLocalDate()) ||
        timezone.rules.getValidOffsets(second.atTime(first.toLocalTime())).isEmpty()) {
        require(++examined <= 366_000) { "No next occurrence within the supported recurrence range" }
        second = second.plusDays(1)
    }
    val end = maxOf(first.plusDays(89).toLocalDate(), second)
    return copy(recurrenceRule = "$rule;UNTIL=${end.format(DateTimeFormatter.BASIC_ISO_DATE)}")
}

fun CalendarEvent.occurrences(rangeStart: Instant, rangeEnd: Instant): List<CalendarEvent> {
    require(rangeEnd > rangeStart && Duration.between(rangeStart, rangeEnd) <= Duration.ofDays(367)) {
        "Query a positive calendar range of at most 367 days"
    }
    val rule = recurrenceRule?.let { CalendarRecurrence.parse(it, timezone) }
        ?: return listOf(this).filter { it.overlaps(rangeStart, rangeEnd) }
    val first = startAt.atZone(timezone)
    val duration = endAt?.let { Duration.between(startAt, it) } ?: Duration.ZERO
    var date = if (rule.count == null) {
        maxOf(first.toLocalDate(), rangeStart.minus(duration).atZone(timezone).toLocalDate().minusDays(1))
    } else {
        first.toLocalDate()
    }
    val last = minOf(
        rangeEnd.atZone(timezone).toLocalDate(),
        rule.until?.atZone(timezone)?.toLocalDate() ?: LocalDate.MAX,
    )
    var count = 0
    var examined = 0
    return buildList {
        while (date <= last) {
            require(++examined <= 366_000) { "Recurrence range exceeds calculation limit" }
            val local = date.atTime(first.toLocalTime())
            if (rule.matches(date, first.toLocalDate()) && timezone.rules.getValidOffsets(local).isNotEmpty()) {
                val instant = local.atZone(timezone).toInstant()
                if (rule.until != null && instant > rule.until) break
                if (rule.count != null && ++count > rule.count) break
                val occurrence = atOccurrence(instant)
                if (occurrence.overlaps(rangeStart, rangeEnd)) add(occurrence)
            }
            date = date.plusDays(1)
        }
    }
}

fun CalendarEvent.atOccurrence(instant: Instant): CalendarEvent {
    val shift = Duration.between(startAt, instant)
    return copy(
        startAt = instant,
        endAt = endAt?.let {
            if (allDay) {
                instant.atZone(timezone).plusDays(
                    ChronoUnit.DAYS.between(startAt.atZone(timezone).toLocalDate(), it.atZone(timezone).toLocalDate()),
                ).toInstant()
            } else {
                it.plus(shift)
            }
        },
        reminderAt = reminderAt?.let {
            if (allDay) {
                instant.atZone(timezone).toLocalDateTime()
                    .plus(Duration.between(startAt.atZone(timezone).toLocalDateTime(), it.atZone(timezone).toLocalDateTime()))
                    .atZone(timezone).toInstant()
            } else {
                it.plus(shift)
            }
        },
        occurrenceStartAt = instant,
    )
}

fun CalendarEvent.overlaps(start: Instant, end: Instant): Boolean =
    startAt < end && (endAt ?: startAt.plusMillis(1)) > start

fun moveRecurrenceAnchor(rule: String?, from: Instant, to: Instant, zone: ZoneId): String? {
    if (rule == null) return null
    val oldDate = from.atZone(zone).toLocalDate()
    val date = to.atZone(zone).toLocalDate()
    if (oldDate == date) return rule
    val parsed = CalendarRecurrence.parse(rule, zone)
    return rule.split(';').joinToString(";") { part ->
        when {
            parsed.frequency == "WEEKLY" && part.startsWith("BYDAY=") -> {
                val shift = ChronoUnit.DAYS.between(oldDate, date)
                "BYDAY=" + parsed.days.map { it.plus(shift) }.sortedBy { it.value }.joinToString(",") { it.name.take(2) }
            }
            part.startsWith("BYMONTHDAY=") && !(parsed.monthDay == -1 && date.dayOfMonth == date.lengthOfMonth()) ->
                "BYMONTHDAY=${date.dayOfMonth}"
            else -> part
        }
    }
}
