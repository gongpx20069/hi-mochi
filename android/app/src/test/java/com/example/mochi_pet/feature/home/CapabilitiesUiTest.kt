package com.example.mochi_pet.feature.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.MochiNavigationIntent
import com.example.mochi_pet.core.skills.MochiSkill
import com.example.mochi_pet.core.tools.AmapProviderSummary
import com.example.mochi_pet.core.tools.BuiltInToolSummary
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_pet.core.tools.skillReadiness
import com.example.mochi_ui.MochiTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h740dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapabilitiesUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `navigation has four destinations and both legacy entries select capabilities`() {
        var destination: MochiNavigationIntent? = null
        compose.setContent { MochiTheme { NavigationBar(MochiSurface.Skills, { destination = it }) } }
        listOf("Home", "Talk", "Planner", "Capabilities").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Skills").assertDoesNotExist()
        compose.onNodeWithText("Tools").assertDoesNotExist()
        compose.onNodeWithText("Capabilities").performClick()
        assertEquals(MochiNavigationIntent.ShowTools, destination)
    }

    @Test fun `catalog preserves parent background and query while exposing standalone tools`() {
        val catalog = ToolCatalogSummary(builtInTools = listOf(BuiltInToolSummary("run_sandboxed_javascript", "Local calculation", "Calculate locally", true)))
        var background = Color.Unspecified
        compose.setContent {
            MochiTheme {
                background = MaterialTheme.colorScheme.surface
                Surface(Modifier.fillMaxSize(), color = background) {
                    CapabilityCatalogSurface(
                        SkillsUiState(skills = listOf(capabilitySkill("builtin:memory-recall", "Memory Recall")), isLoading = false),
                        ToolsUiState(catalog = catalog, isLoading = false), onExplore = {}, onCheckUpdates = {},
                        skillContent = { Text(it.name) }, toolContent = { id, footer -> Text(id); footer() },
                    )
                }
            }
        }
        compose.onNodeWithTag("capability-skill-builtin:memory-recall").assertIsDisplayed()
        capture("capability-list", "capabilities-catalog.png", background)
        compose.onNodeWithTag("capability-search").performTextInput("calculation")
        compose.onNodeWithTag("capability-tool-builtin:run_sandboxed_javascript").performClick()
        compose.onNodeWithText("builtin:run_sandboxed_javascript").assertIsDisplayed()
        compose.onNodeWithTag("capabilities-back").performClick()
        compose.onNodeWithTag("capability-search").assertTextContains("calculation")
        compose.onNodeWithTag("capability-tool-builtin:run_sandboxed_javascript").assertIsDisplayed()
    }

    @Test fun `configure a dependency and return to same skill with live readiness`() {
        var skill by mutableStateOf(capabilitySkill("builtin:merchant-discovery", "Merchant discovery"))
        var catalog by mutableStateOf(ToolCatalogSummary(amap = AmapProviderSummary(connected = true, enabled = false,
            tools = listOf("amap_search_poi", "amap_get_poi").map { BuiltInToolSummary(it, it, "", true) })))
        compose.setContent {
            MochiTheme {
                CapabilityCatalogSurface(
                    SkillsUiState(skills = listOf(skill), isLoading = false), ToolsUiState(catalog = catalog, isLoading = false),
                    onExplore = {}, onCheckUpdates = {},
                    skillContent = {
                        InstalledSkillCard(it, catalog.skillReadiness(it), {}, { _, enabled -> skill = skill.copy(enabled = enabled) }, {}, {})
                    },
                    toolContent = { id, footer -> ToolFixture(catalog, id, listOf(skill), footer) { catalog = catalog.copy(amap = catalog.amap.copy(enabled = it)) } },
                )
            }
        }
        compose.onNodeWithTag("capability-skill-${skill.id}").performClick()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithTag("dependency-amap").performScrollTo().performClick()
        compose.onNode(isToggleable()).performClick()
        assertTrue(catalog.amap.enabled)
        assertFalse(skill.enabled)
        compose.onNodeWithTag("related-${skill.id}").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().assertIsEnabled().performClick()
        assertTrue(skill.enabled)
        capture("capability-detail", "capabilities-skill.png")
        compose.onNodeWithTag("dependency-amap").performScrollTo().performClick()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Pause dependent Skills?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(catalog.amap.enabled)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Continue").performClick()
        assertFalse(catalog.amap.enabled)
        assertTrue(skill.enabled)
        compose.onNodeWithTag("capabilities-back").performClick()
        compose.onNodeWithText("Built-in · Suspended").assertIsDisplayed()
    }

    @Test
    fun `Chinese catalog and managed memory control remain usable with large text`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val skill = capabilitySkill("builtin:memory-recall", "Memory Recall")
        var settings = false
        compose.setContent {
            MochiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                CapabilityCatalogSurface(
                    SkillsUiState(skills = listOf(skill), isLoading = false), ToolsUiState(isLoading = false),
                    onExplore = {}, onCheckUpdates = {},
                    skillContent = { InstalledSkillCard(it, null, {}, { _, _ -> fail("Managed setting must not toggle") }, {}, {}, { settings = true }) },
                    toolContent = { _, _ -> },
                )
                }
            }
        }
        compose.onNodeWithText("能力").assertIsDisplayed()
        compose.onNodeWithTag("capability-skill-${skill.id}").performClick()
        compose.onNodeWithText(localizeUiText("Daily journal")).performScrollTo().performClick()
        compose.onNodeWithText(localizeUiText("Go to settings")).performClick()
        assertTrue(settings)
    }

    @androidx.compose.runtime.Composable
    private fun ToolFixture(catalog: ToolCatalogSummary, id: String, skills: List<MochiSkill>, footer: @androidx.compose.runtime.Composable () -> Unit, onEnabled: (Boolean) -> Unit) {
        ToolCapabilityDetail(
            state = ToolsUiState(catalog = catalog, isLoading = false), detailGroup = id, skills = skills, footer = footer,
            onSetBuiltInEnabled = { _, _ -> }, onConnectNotion = {}, onDisconnectNotion = {},
            onFeishuAction = {}, onOpenTencentDocsTokenPage = {}, onConfigureTencentDocs = {},
            onDisconnectTencentDocs = {}, onOpenAmapConsole = {}, onConfigureAmap = { _, _ -> },
            onDisconnectAmap = {}, onSetAmapEnabled = onEnabled, onSetAgentBrowserEnabled = {},
            onSetMediaEnabled = {}, onManageBrowser = {}, onAgentLinkAction = {},
            onInstallMijia = {}, onConfigureMijia = {}, onDisconnectMijia = {},
            onSetMijiaEnabled = {}, onSetMijiaToolEnabled = { _, _ -> }, onTermuxAction = {}, onWebdavAction = {},
            onAddServer = {}, restActions = RestApiActions({}, { _, _, _ -> error("Not used") }),
            onRemoveServer = {}, onSetServerEnabled = { _, _ -> }, onSetToolEnabled = { _, _, _ -> },
        )
    }

    private fun capture(tag: String, name: String, expectedBackground: Color? = null) {
        val root = compose.onNodeWithTag(tag).fetchSemanticsNode().root as ViewRootForTest
        val bitmap = Bitmap.createBitmap(root.view.width, root.view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.view.draw(android.graphics.Canvas(bitmap)) }
        expectedBackground?.let { assertEquals("Catalog must preserve its parent surface", it.toArgb(), bitmap.getPixel(1, 1)) }
        val file = File("build/reports/$name")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
