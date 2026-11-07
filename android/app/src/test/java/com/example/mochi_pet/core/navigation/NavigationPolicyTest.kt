package com.example.mochi_pet.core.navigation

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun `voice exit preserves presentation and requires an explicit request without planner arguments`() = runBlocking {
        val decisions = mutableListOf<NavigationDecision>()
        val tool = NavigateMochiUiTool(policy, UiDirectiveSink { decisions += it })
        val args = buildJsonObject {
            put("operate", "end_voice_session")
            put("reason", "explicit_ui_request")
        }
        val request = parseUiDirectiveRequest(args)
        tool.execute(args, context.copy(currentSurface = MochiSurface.DateTime))

        val decision = decisions.single()
        assertEquals(MochiNavigationIntent.EndVoiceSession, decision.intent)
        assertEquals("end_voice_session", decision.directive.surface)
        assertEquals(JsonPrimitive(false), decision.directive.toJson()["follow_up_listening"])
        assertEquals(
            MochiSurface.DateTime,
            MochiNavigationReducer.reduce(decision.intent, MochiSurface.DateTime),
        )
        assertEquals(
            request,
            parseUiDirectiveRequest(buildJsonObject {
                put("surface", "end_voice_session")
                put("reason", "explicit_ui_request")
            }, "surface"),
        )
        for (invalid in listOf(
            request.copy(reason = NavigationReason.CURRENT_TIME_DATE),
            request.copy(date = today),
            request.copy(month = java.time.YearMonth.from(today)),
            request.copy(section = UiSection.TIME),
            request.copy(status = com.example.mochi_pet.core.model.TodoStatus.ACTIVE),
            request.copy(highlightIds = listOf("item")),
        )) {
            assertThrows(ToolInputException::class.java) {
                policy.decide(invalid, context)
            }
        }
    }

    @Test fun `focus operations parse for Tools and final directives and require explicit UI intent`() = runBlocking {
        val decisions = mutableListOf<NavigationDecision>()
        val tool = NavigateMochiUiTool(policy, UiDirectiveSink { decisions += it })
        for ((operation, enabled) in listOf("enter_focus" to true, "exit_focus" to false)) {
            val args = buildJsonObject { put("operate", operation); put("reason", "explicit_ui_request") }
            tool.execute(args, context.copy(currentSurface = MochiSurface.DateTime))
            val decision = decisions.last()
            assertEquals(MochiNavigationIntent.SetFocusMode(enabled), decision.intent)
            assertEquals("date_time", decision.directive.surface)
            assertEquals(JsonPrimitive(enabled), decision.directive.toJson()["focus_mode"])
            assertEquals(
                parseUiDirectiveRequest(args).operation,
                parseUiDirectiveRequest(buildJsonObject {
                    put("surface", operation); put("reason", "explicit_ui_request")
                }, "surface").operation,
            )
            assertThrows(ToolInputException::class.java) {
                policy.decide(parseUiDirectiveRequest(args).copy(reason = NavigationReason.CURRENT_TIME_DATE), context)
            }
            assertThrows(ToolInputException::class.java) {
                policy.decide(parseUiDirectiveRequest(args).copy(date = today), context)
            }
        }
    }

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
