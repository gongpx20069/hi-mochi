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
}
