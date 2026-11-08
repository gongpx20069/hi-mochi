package com.example.mochi_pet.feature.journal

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
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

    @Test fun `empty day shows factual empty label`() {
        compose.setContent { MochiTheme {
            JournalCard(entry.copy(status = JournalStatus.EMPTY, title = "", text = ""), today, true, false, {}, {})
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
}
