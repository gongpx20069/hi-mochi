package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.core.model.CalendarEventDraft
import com.example.mochi_pet.core.schedule.AgentScheduleRun
import com.example.mochi_pet.core.schedule.ScheduleRunState
import com.example.mochi_pet.ui.theme.MochiTheme
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlannerUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @Before fun setup() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }
    private fun actions(save: suspend (CalendarEventDraft) -> String? = { null }) = PlannerUiActions(
        saveUser = { _, _, draft, _ -> save(draft) }, completeUser = {}, deleteUser = { _, _, _ -> },
        saveTask = { _, _ -> }, results = { _, _ -> emptyList() },
        result = { error("Unexpected result request") }, showResults = {}, changes = emptyFlow(),
    )

    @Test fun `editor leaves reminders off and saves only after explicit action at large text size`() {
        var saved: CalendarEventDraft? = null
        var closed = false
        compose.setContent {
            MochiTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    UserScheduleEditor(LocalDate.of(2026, 11, 9), null, null, actions { saved = it; null }) { closed = true }
                }
            }
        }
        assertNull(saved)
        compose.onNodeWithText("Content").performScrollTo().performTextReplacement("Exercise")
        compose.onNodeWithText("Save").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals("Exercise", saved?.title)
        assertNull(saved?.reminderAt)
        assertTrue(closed)
    }

    @Test fun `saved reminder warning stays visible without creating the record again`() {
        var saves = 0
        var closed = false
        compose.setContent { MochiTheme {
            UserScheduleEditor(LocalDate.of(2026, 11, 9), null, null,
                actions { saves++; "Notifications disabled" }) { closed = true }
        } }
        compose.onNodeWithText("Content").performScrollTo().performTextReplacement("Read")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Notifications disabled").performScrollTo().assertIsDisplayed()
        assertFalse(closed)
        compose.onNodeWithText("Close").performScrollTo().performClick()
        assertEquals(1, saves)
        assertTrue(closed)
    }

    @Test fun `results display exact persisted run rather than conversation history`() {
        val run = AgentScheduleRun("run", "plan", 2, "Weather", "Read", false, null,
            ScheduleRunState.SUCCESS, Instant.parse("2026-11-09T00:00:00Z"), output = "Synthetic weather result")
        compose.setContent { MochiTheme {
            ScheduleResultsSheet(PlannerResultSelection("plan", "Weather", "run"), actions().copy(result = {
                assertEquals("run", it)
                run
            })) {}
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Synthetic weather result").assertIsDisplayed()
        compose.onNodeWithText("Scheduled execution").assertIsDisplayed()
    }

    @Test fun `unified week navigation changes date without Today Calendar tabs`() {
        val today = LocalDate.of(2026, 11, 9)
        compose.setContent { MochiTheme {
            var selected by remember { mutableStateOf(today) }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                PlannerAgenda(selected, today, false, PlannerSurfaceState(date = selected),
                    { selected = it }, {}, { selected = today }, actions(), {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText("Calendar").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()
        compose.onNodeWithContentDescription("2026-11-10", substring = true).performClick()
        compose.onNodeWithText("Back to today").assertIsDisplayed().performClick()
        compose.onNodeWithText("Back to today").assertDoesNotExist()
        compose.onNodeWithText("+ New").performClick()
        compose.onNodeWithText("New arrangement").assertIsDisplayed()
    }

    @Test fun `history uses one task card and opens exact persisted result`() {
        val today = LocalDate.of(2026, 11, 9)
        val day = today.minusDays(1)
        val run = AgentScheduleRun("history", "deleted-plan", 0, "Historical task", "Read", false, null,
            ScheduleRunState.SUCCESS, Instant.parse("2026-11-08T08:00:00Z"), output = "Saved output")
        var opened: AgentScheduleRun? = null
        compose.setContent { MochiTheme {
            PlannerAgenda(day, today, false, PlannerSurfaceState(date = day, taskDays = listOf(
                com.example.mochi_pet.core.schedule.ScheduleDay(null, null, listOf(run, run.copy(id = "history2"))))),
                {}, {}, {}, actions().copy(showRun = { opened = it }), {}, {}, {}, {})
        } }
        compose.onNodeWithText("Historical task").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Historical task").assertCountEquals(1)
        compose.onNodeWithText("Results").performScrollTo().performClick()
        assertEquals("history2", opened?.id)
    }

    @Test fun `agenda interleaves tasks and user events with all day entries first`() {
        val base = Instant.parse("2026-11-09T00:00:00Z")
        val event = com.example.mochi_pet.core.model.CalendarEvent("late", "Late", null, base.plusSeconds(7200),
            null, false, java.time.ZoneOffset.UTC, null, null, null, base, base)
        val allDay = event.copy(id = "all-day", title = "All day", allDay = true)
        val run = AgentScheduleRun("run", "plan", 0, "Middle", "Read", false, null,
            ScheduleRunState.SUCCESS, base.plusSeconds(3600))
        val state = PlannerSurfaceState(events = listOf(event, allDay, event.copy(id = "early", startAt = base)),
            taskDays = listOf(com.example.mochi_pet.core.schedule.ScheduleDay(null, null, listOf(run))))
        val sorted = chronologicalAgenda(state)
        assertEquals(listOf("all-day", "early", "plan", "late"), sorted.map {
            when (it) {
                is AgendaRow.Event -> it.value.id
                is AgendaRow.Task -> it.value.id
                is AgendaRow.Todo -> it.value.id
            }
        })
    }
}
