package com.example.mochi_pet.core.navigation

import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.model.TodoStatus
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class MochiNavigationReducerTest {
    @Test fun `focus preserves Home presentation while enter from another page opens Home and exit stays put`() {
        for (surface in listOf(MochiSurface.Face, MochiSurface.DateTime, MochiSurface.Weather, MochiSurface.Card)) {
            for (enabled in listOf(true, false)) {
                assertEquals(surface, MochiNavigationReducer.reduce(MochiNavigationIntent.SetFocusMode(enabled), surface))
            }
        }
        assertEquals(MochiSurface.Face,
            MochiNavigationReducer.reduce(MochiNavigationIntent.SetFocusMode(true), MochiSurface.Settings))
        assertEquals(MochiSurface.Settings,
            MochiNavigationReducer.reduce(MochiNavigationIntent.SetFocusMode(false), MochiSurface.Settings))
    }

    @Test
    fun `show date time keeps presentation on Home`() {
        assertEquals(
            MochiSurface.DateTime,
            MochiNavigationReducer.reduce(
                MochiNavigationIntent.ShowDateTime,
            ),
        )
    }

    @Test
    fun `show weather keeps presentation on Home`() {
        assertEquals(
            MochiSurface.Weather,
            MochiNavigationReducer.reduce(
                MochiNavigationIntent.ShowWeather,
            ),
        )
    }

    @Test
    fun `show generated card keeps presentation on Home`() {
        assertEquals(
            MochiSurface.Card,
            MochiNavigationReducer.reduce(
                MochiNavigationIntent.ShowCard,
            ),
        )
    }

    @Test
    fun `show today selects today surface`() {
        assertEquals(
            MochiSurface.Today,
            MochiNavigationReducer.reduce(MochiNavigationIntent.ShowToday),
        )
    }

    @Test
    fun `show tools selects tools surface`() {
        assertEquals(
            MochiSurface.Tools,
            MochiNavigationReducer.reduce(MochiNavigationIntent.ShowTools),
        )
    }

    @Test
    fun `show calendar day preserves resolved date`() {
        val date = LocalDate.of(2026, 8, 5)

        assertEquals(
            MochiSurface.CalendarDay(date),
            MochiNavigationReducer.reduce(
                MochiNavigationIntent.ShowCalendarDay(date),
            ),
        )
    }

    @Test
    fun `show month preserves selected month`() {
        val month = YearMonth.of(2026, 8)

        assertEquals(
            MochiSurface.CalendarMonth(month),
            MochiNavigationReducer.reduce(
                MochiNavigationIntent.ShowCalendarMonth(month),
            ),
        )
    }

    @Test
    fun `show todo preserves date and status filter`() {
        val date = LocalDate.of(2026, 8, 5)

        assertEquals(
            MochiSurface.Todo(date, TodoStatus.ACTIVE),
            MochiNavigationReducer.reduce(
                MochiNavigationIntent.ShowTodo(date, TodoStatus.ACTIVE),
            ),
        )
    }
}
