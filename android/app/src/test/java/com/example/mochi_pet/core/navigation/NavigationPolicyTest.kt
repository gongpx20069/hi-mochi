package com.example.mochi_pet.core.navigation

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NavigationPolicyTest {
    private val today = LocalDate.of(2026, 7, 31)
    private val context = ToolExecutionContext(
        currentDate = today,
        currentSurface = MochiSurface.Face,
    )
    private val policy = NavigationPolicy()

    @Test
    fun `current time opens Home date time presentation`() {
        val decision = policy.decide(
            UiDirectiveRequest(
                operation = NavigationOperation.SHOW_DATE_TIME,
                reason = NavigationReason.CURRENT_TIME_DATE,
                section = UiSection.TIME,
            ),
            context,
        )

        assertEquals(MochiNavigationIntent.ShowDateTime, decision.intent)
        assertEquals("date_time", decision.directive.surface)
        assertEquals(today, decision.directive.date)
    }

    @Test
    fun `legacy current time today request is redirected to Home`() {
        val decision = policy.decide(
            UiDirectiveRequest(
                operation = NavigationOperation.SHOW_TODAY,
                reason = NavigationReason.CURRENT_TIME_DATE,
                section = UiSection.TIME,
            ),
            context,
        )

        assertEquals(MochiNavigationIntent.ShowDateTime, decision.intent)
        assertEquals("date_time", decision.directive.surface)
    }

    @Test
    fun `current weather opens Home weather presentation`() {
        val decision = policy.decide(
            UiDirectiveRequest(
                operation = NavigationOperation.SHOW_WEATHER,
                reason = NavigationReason.CURRENT_WEATHER,
            ),
            context,
        )

        assertEquals(MochiNavigationIntent.ShowWeather, decision.intent)
        assertEquals("weather", decision.directive.surface)
        assertEquals(UiSection.WEATHER, decision.directive.section)
    }

    @Test
    fun `non-today planner question opens calendar day`() {
        val tomorrow = today.plusDays(1)

        val decision = policy.decide(
            UiDirectiveRequest(
                operation = NavigationOperation.SHOW_CALENDAR_DAY,
                reason = NavigationReason.OTHER_DATE,
                date = tomorrow,
                section = UiSection.AGENDA,
            ),
            context,
        )

        assertEquals(
            MochiNavigationIntent.ShowCalendarDay(tomorrow),
            decision.intent,
        )
        assertEquals("calendar_day", decision.directive.surface)
        assertEquals(tomorrow, decision.directive.date)
    }

    @Test
    fun `generic calendar knowledge does not navigate`() {
        assertThrows(ToolInputException::class.java) {
            policy.decide(
                UiDirectiveRequest(
                    operation = NavigationOperation.SHOW_CALENDAR_DAY,
                    reason = NavigationReason.GENERIC_KNOWLEDGE,
                    date = today.plusDays(1),
                ),
                context,
            )
        }
    }

    @Test
    fun `today planner context must use today surface`() {
        val error = assertThrows(ToolInputException::class.java) {
            policy.decide(
                UiDirectiveRequest(
                    operation = NavigationOperation.SHOW_CALENDAR_DAY,
                    reason = NavigationReason.ITEM_MUTATION,
                    date = today,
                ),
                context,
            )
        }

        assertEquals(
            "Use show_today for today's planner context",
            error.message,
        )
    }
}
