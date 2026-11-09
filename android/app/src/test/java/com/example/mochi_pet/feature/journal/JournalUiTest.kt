package com.example.mochi_pet.feature.journal

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.core.journal.DailyJournal
import com.example.mochi_pet.core.journal.JournalSettings
import com.example.mochi_pet.core.journal.JournalStatus
import com.example.mochi_pet.ui.theme.MochiTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
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
class JournalUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    private val today = LocalDate.of(2026, 11, 10)
    private val entry = DailyJournal(today.minusDays(1), ZoneOffset.UTC, JournalStatus.READY,
        "Launch decision", "Chose the launch date.", null, Instant.EPOCH)
    @Before fun setup() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `enabling requires disclosure and confirmation at large font size`() {
        var enabled = false
        compose.setContent { MochiTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                JournalSettingsCard(JournalSettings(), false, null) { enabled = it }
            }
        } }
        compose.onNode(isToggleable()).performClick()
        assertFalse(enabled)
        compose.onNodeWithText("Enable Daily journal?").assertIsDisplayed()
        compose.onNodeWithText("Starting today,", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Enable", substring = false).performClick()
        assertTrue(enabled)
    }

    @Test fun `today future and deleted entries never render`() {
        compose.setContent { MochiTheme {
            JournalCard(entry.copy(date = today), today, true, false, {}, {})
            JournalCard(entry.copy(date = today.plusDays(1)), today, true, false, {}, {})
            JournalCard(entry.copy(status = JournalStatus.DELETED), today, true, false, {}, {})
        } }
        compose.onNodeWithText("Daily journal").assertDoesNotExist()
    }

    @Test fun `agenda consent uses shared settings and date hints only appear while enabled`() {
        val settings = mutableStateOf(JournalSettings())
        val date = mutableStateOf(today)
        val inPlanner = mutableStateOf(true)
        compose.setContent { MochiTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                if (inPlanner.value) JournalDayContent(date.value, today, null, settings.value, false, null, true,
                    { settings.value = settings.value.copy(enabled = it) }, {}, {})
                else JournalSettingsCard(settings.value, false, null) { settings.value = settings.value.copy(enabled = it) }
            }
        } }
        compose.onNodeWithText("Keep what matters in a short journal.").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Enable Daily journal?").assertIsDisplayed()
        compose.onNodeWithText("Earlier dates are not automatically backfilled.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(settings.value.enabled)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Enable", substring = false).performClick()
        compose.onNodeWithText("Keep what matters in a short journal.").assertDoesNotExist()
        compose.onNodeWithText("Today's journal will be prepared after the day ends.").assertIsDisplayed()
        compose.runOnIdle { date.value = today.plusDays(1) }
        compose.onNodeWithText("Journals only record days that have passed.").assertIsDisplayed()
        compose.onNodeWithText("Today's journal will be prepared after the day ends.").assertDoesNotExist()
        compose.runOnIdle { date.value = today.minusDays(1) }
        compose.onNodeWithText("No journal for this day").assertIsDisplayed()
        compose.runOnIdle { inPlanner.value = false }
        compose.onNode(isToggleable()).assertIsOn().performClick()
        assertFalse(settings.value.enabled)
        compose.runOnIdle { inPlanner.value = true }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Keep what matters in a short journal.").assertIsDisplayed()
        compose.onNodeWithText("No journal for this day").assertDoesNotExist()
    }

    @Test fun `switching off preserves the selected past journal below the introduction`() {
        val settings = mutableStateOf(JournalSettings(enabled = true))
        compose.setContent { MochiTheme {
            JournalDayContent(entry.date, today, entry, settings.value, false, null, true,
                { settings.value = settings.value.copy(enabled = it) }, {}, {})
        } }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Keep what matters in a short journal.").assertIsDisplayed()
        compose.onNodeWithText(entry.text).assertIsDisplayed()
        compose.onNodeWithText("No journal for this day").assertDoesNotExist()
    }

    @Test fun `loading and failed settings do not promise journal generation`() {
        val settings = mutableStateOf<JournalSettings?>(null)
        val error = mutableStateOf<String?>(null)
        compose.setContent { MochiTheme {
            JournalDayContent(today, today, null, settings.value, true, error.value, true, {}, {}, {})
        } }
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithText("Today's journal will be prepared after the day ends.").assertDoesNotExist()
        compose.runOnIdle {
            settings.value = JournalSettings(enabled = true)
            error.value = "Could not update Daily journal. Please retry."
        }
        compose.onNodeWithText("Could not update Daily journal. Please retry.").assertIsDisplayed()
        compose.onNodeWithText("Today's journal will be prepared after the day ends.").assertDoesNotExist()
    }

    @Test fun `empty day shows factual empty label`() {
        compose.setContent { MochiTheme {
            JournalCard(entry.copy(status = JournalStatus.EMPTY, title = "", text = "", conversationCount = 2), today, true, false, {}, {})
        } }
        compose.onNodeWithText("Nothing important to record").assertIsDisplayed()
    }

    @Test fun `failed regeneration retains body and delete requires confirmation`() {
        var deleted = false
        compose.setContent { MochiTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                JournalCard(entry.copy(status = JournalStatus.FAILED, error = "Journal generation timed out"),
                    today, false, false, {}, { deleted = true })
            }
        } }
        compose.onNodeWithText(entry.text).assertIsDisplayed()
        compose.onNodeWithText("Journal generation timed out").assertIsDisplayed()
        compose.onNodeWithText(entry.title).performClick()
        compose.onNodeWithText("Regenerate journal").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        assertFalse(deleted)
        compose.onNodeWithText("This day will not be generated again automatically.").assertIsDisplayed()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        assertTrue(deleted)
    }

    @Test fun `missing evidence is not presented as a model judgment`() {
        compose.setContent { MochiTheme {
            JournalCard(entry.copy(status = JournalStatus.NO_DATA, title = "", text = "", conversationCount = 0,
                userScheduleCount = 0, taskCount = 0), today, true, false, {}, {})
        } }
        compose.onNodeWithText("No saved records for this day").assertIsDisplayed().performClick()
        compose.onNodeWithText("No saved sources were available; no AI request was made.").assertIsDisplayed()
        compose.onNodeWithText("Nothing important to record").assertDoesNotExist()
    }

    @Test fun `legacy empty entries do not imply a known model judgment`() {
        compose.setContent { MochiTheme {
            JournalCard(entry.copy(status = JournalStatus.EMPTY, title = "", text = ""), today, true, false, {}, {})
        } }
        compose.onNodeWithText("No journal content; source information unavailable").performClick()
        compose.onNodeWithText("Source counts unavailable for this entry").assertIsDisplayed()
        compose.onNodeWithText("AI-generated from saved records.").assertDoesNotExist()
    }
}
