package com.example.mochi_pet.feature.home

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerDayRangeTest {
    @Test
    fun `day range uses local midnight boundaries`() {
        val range = dayRange(
            date = LocalDate.of(2026, 8, 5),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(Instant.parse("2026-08-04T16:00:00Z"), range.start)
        assertEquals(Instant.parse("2026-08-05T16:00:00Z"), range.end)
    }

    @Test
    fun `day range preserves daylight saving transition`() {
        val range = dayRange(
            date = LocalDate.of(2026, 3, 8),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(Duration.ofHours(23), Duration.between(range.start, range.end))
    }
}
