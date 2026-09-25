package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.diagnostics.CheckStatus
import com.example.mochi_pet.core.diagnostics.ConfigurationCheck
import com.example.mochi_pet.core.diagnostics.RepairTarget
import com.example.mochi_pet.core.extensions.TermuxTaskView
import com.example.mochi_pet.core.tasks.AgentTaskView
import com.example.mochi_pet.core.tasks.TaskStatus
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import java.time.Instant
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.mochi_pet.ui.theme.MochiTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskCenterUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `task card opens focused details and stopping a child clearly affects its parent`() {
        val child = AgentTaskView("child", "researcher", null, "Researcher", TaskStatus.RUNNING, Instant.EPOCH)
        var stopped: AgentTaskView? = null
        render(TaskCenterUiState(), listOf(child), stop = { stopped = it })
        compose.onNodeWithText("Stop parent task").assertDoesNotExist()
        compose.onNodeWithTag("task-center-list").performScrollToNode(hasText("Researcher"))
        compose.onNodeWithTag("agent:child").performClick()
        compose.onNodeWithText("Stop parent task").assertIsDisplayed()
        compose.onNodeWithText("Stop parent task").performClick()
        assertEquals(child, stopped)
        compose.onNodeWithText("Close details").performClick()
        compose.onNodeWithText("Check setup").assertIsDisplayed()
    }

    @Test fun `large text diagnostics remain scrollable and repair is explicit`() {
        var repaired: RepairTarget? = null
        var closed = false
        val state = TaskCenterUiState(diagnosticsPage = true, diagnosticsStandalone = true, checks = listOf(ConfigurationCheck(
            "termux", "Termux", CheckStatus.ATTENTION,
            "Open Configure Termux to check installation, Android command permission, external access and the Shell helper.",
            RepairTarget.TERMUX,
        )))
        render(state, repair = { repaired = it }, close = { closed = true }, fontScale = 2f)
        compose.onNodeWithTag("configuration-check-list").performScrollToNode(hasText("Open related settings"))
        compose.onNodeWithText("Open related settings").performClick()
        assertEquals(RepairTarget.TERMUX, repaired)
        compose.onNodeWithText("Back").performClick()
        assertTrue(closed)
    }

    @Test fun `extension diagnosis distinguishes trust connection and disabled optional capability`() {
        fun check(provider: ExtensionProviderSummary) = extensionCheck("termux", "Termux", provider, RepairTarget.TERMUX)
        assertEquals(CheckStatus.DISABLED, check(ExtensionProviderSummary()).status)
        assertEquals(CheckStatus.ATTENTION, check(ExtensionProviderSummary(installed = true)).status)
        assertEquals(CheckStatus.ATTENTION, check(ExtensionProviderSummary(installed = true, trusted = true)).status)
        assertEquals(CheckStatus.DISABLED, check(ExtensionProviderSummary(installed = true, trusted = true, connected = true)).status)
    }

    @Test fun `Termux states never assume an unknown task completed`() {
        for ((remote, expected) in mapOf(
            "submitted" to TaskStatus.QUEUED, "running" to TaskStatus.RUNNING, "timed_out" to TaskStatus.FAILED,
            "stopped" to TaskStatus.CANCELLED, "unknown" to TaskStatus.UNKNOWN,
        )) {
            assertEquals(expected, termuxTaskStatus(TermuxTaskView("task", ToolResultEnvelope.success(
                buildJsonObject { put("state", remote) },
            ))))
        }

    }

    @Test fun `finished filter hides active cards without hiding the fixed setup entry`() {
        val agent = AgentTaskView("active", "main", null, "Conversation", TaskStatus.RUNNING, Instant.EPOCH)
        render(TaskCenterUiState(), listOf(agent))
        compose.onNodeWithTag("task-center-list").performScrollToNode(hasText("Activity"))
        compose.onNodeWithTag("task-filter-HISTORY").performScrollTo().performClick()
        compose.onNodeWithTag("task-center-list").performScrollToNode(hasText("Nothing in this view"))
        compose.onNodeWithTag("agent:active").assertDoesNotExist()
        compose.onNodeWithText("Check setup").assertIsDisplayed()
    }

    @Test fun `long Shell output never scrolls Stop away at large font scale`() {
        val task = TermuxTaskView("long-output", ToolResultEnvelope.success(buildJsonObject {
            put("state", "running")
            put("stdout", "Diagnostic output line\n".repeat(150))
        }))
        render(TaskCenterUiState(), termux = listOf(task), fontScale = 2f)
        compose.onNodeWithTag("task-center-list").performScrollToNode(hasText("Shell task"))
        compose.onNodeWithTag("shell:long-output").performClick()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Technical details").performScrollTo()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Refresh output").assertIsDisplayed()
        compose.onNodeWithText("Close details").assertIsDisplayed()
    }

    @Test fun `dashboard visual fixture uses real Mochi theme and no user data`() {
        val child = AgentTaskView("fixture", "researcher", null, "Researcher", TaskStatus.RUNNING,
            Instant.parse("2026-09-25T12:00:00Z"))
        val shell = TermuxTaskView("fixture-shell", ToolResultEnvelope.success(buildJsonObject {
            put("state", "succeeded")
            put("stdout", "Example completed")
        }))
        render(TaskCenterUiState(), listOf(child), listOf(shell))
        compose.onNodeWithText("Check setup").assertIsDisplayed()
        captureFixture("task-dashboard-fixture.png")
    }

    @Test fun `Chinese idle dashboard offers one clear starting action`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        render(TaskCenterUiState())
        compose.onNodeWithText(localizeUiText("Check setup")).assertIsDisplayed()
        compose.onNodeWithTag("task-center-list").performScrollToNode(hasText(localizeUiText("Ask Mochi")))
        compose.onNodeWithText(localizeUiText("Ask Mochi")).assertIsDisplayed()
        compose.onNodeWithTag("task-center-list").performScrollToNode(hasText(localizeUiText("Standing by")))
        captureFixture("task-dashboard-zh-fixture.png")
    }

    private fun captureFixture(name: String) {
        val root = compose.onNodeWithTag("task-dashboard").fetchSemanticsNode().root
        val view = requireNotNull(root as? ViewRootForTest).view.rootView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/$name")
        file.parentFile.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun render(
        state: TaskCenterUiState,
        agents: List<AgentTaskView> = emptyList(),
        termux: List<TermuxTaskView> = emptyList(),
        stop: (AgentTaskView) -> Unit = {},
        repair: (RepairTarget) -> Unit = {},
        close: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MochiTheme {
                    TaskCenterDialog(
                        state, agents, termux, close, {}, {}, {}, {}, repair, stop, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> },
                    )
                }
            }
        }
    }
}
