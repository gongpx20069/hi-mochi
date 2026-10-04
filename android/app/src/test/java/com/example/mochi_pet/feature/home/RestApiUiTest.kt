package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.mochi_pet.core.rest.*
import com.example.mochi_pet.ui.theme.MochiTheme
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
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
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestApiUiTest {
    @get:Rule val compose = createComposeRule()
    private val originalLocale = Locale.getDefault()
    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(originalLocale) }

    @Test fun `add tool chooser keeps MCP and REST separate`() {
        var mcp = false
        var rest = false
        compose.setContent { MochiTheme { AddToolDialog({ mcp = true }, { rest = true }, {}) } }
        compose.onNodeWithText("Connect MCP service").performClick()
        compose.onNodeWithText("Connect REST API").performClick()
        assertTrue(mcp)
        assertTrue(rest)
    }

    @Test fun `connection validation keeps editor open and no test runs on save`() {
        var saved = false
        var dismissed = false
        val actions = RestApiActions({ saved = true }, { _, _, _ -> error("Unexpected network request") })
        compose.setContent { MochiTheme { RestConnectionDialog(null, actions) { dismissed = true } } }
        compose.onNodeWithText("Save connection").performClick()
        compose.onNodeWithText("Enter a connection name (up to 80 characters).").performScrollTo().assertIsDisplayed()
        assertFalse(saved)
        assertFalse(dismissed)
        compose.onNodeWithText("Connection name").performScrollTo().performTextReplacement("Fixture")
        compose.onNodeWithText("Service address (https://api.example.com)").performScrollTo().performTextReplacement("https://api.example.com")
        compose.onNodeWithText("Token", substring = false).performScrollTo().performTextReplacement("fixture-key")
        compose.onNodeWithText("Save connection").performClick()
        assertTrue(saved)
        assertTrue(dismissed)
    }

    @Test fun `tool testing requires explicit real request approval then saves selected fields`() {
        var tests = 0
        var saved: RestApiChange.SaveTool? = null
        val connection = RestConnection(name = "Fixture", baseUrl = "https://api.example.com", auth = RestAuth.NONE, revision = "fixture")
        val tool = RestToolDefinition(name = "Temperature", description = "Read temperature")
        val actions = RestApiActions({ saved = it as RestApiChange.SaveTool }, { _, _, _ ->
            tests++
            RestTestResult(200, listOf(RestFieldSample("/temperature", JsonPrimitive(22), RestValueType.NUMBER)), null)
        })
        compose.setContent { MochiTheme { RestToolDialog(connection, tool, actions, {}) } }
        compose.onNodeWithText("Test API").performScrollTo().performClick()
        assertEquals(0, tests)
        compose.onNodeWithText("Send a real API request?").assertIsDisplayed()
        compose.onNodeWithText("Send request").performClick()
        compose.waitForIdle()
        assertEquals(1, tests)
        compose.onNodeWithText("/temperature = 22").performScrollTo()
        compose.onNodeWithText("Save tool").performClick()
        compose.onNodeWithText("Select at least one response field.").performScrollTo().assertIsDisplayed()
        assertNull(saved)
        compose.onNodeWithText("/temperature = 22").performScrollTo().performClick()
        compose.onNodeWithText("Output name").performScrollTo().performTextReplacement("temperature")
        compose.onNodeWithText("Meaning and units").performScrollTo().performTextReplacement("Celsius")
        compose.onNodeWithText("Save tool").performClick()
        assertEquals("temperature", saved!!.tool.outputs.single().name)
        assertEquals("Celsius", saved!!.tool.outputs.single().description)
        saved = null
        compose.onNodeWithText("API path (for example /v1/temperature)").performScrollTo().performTextReplacement("/changed")
        compose.onNodeWithText("Save tool").performClick()
        compose.onNodeWithText("Test this configuration before saving the tool.").performScrollTo().assertIsDisplayed()
        assertNull(saved)
    }

    @Test fun `Chinese chooser uses localized actions`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        compose.setContent { MochiTheme { AddToolDialog({}, {}, {}) } }
        compose.onNodeWithText("添加工具").assertIsDisplayed()
        compose.onNodeWithText("连接 MCP 服务").assertIsDisplayed()
        compose.onNodeWithText("连接 REST API").assertIsDisplayed()
    }

    @Test fun `Chinese connection editor remains scrollable with large text`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val actions = RestApiActions({}, { _, _, _ -> error("Unexpected request") })
        compose.setContent {
            MochiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    RestConnectionDialog(null, actions, {})
                }
            }
        }
        compose.onNodeWithText("保存连接").assertIsDisplayed()
        compose.onNodeWithText("Token 仅在本机加密保存，不发送给模型。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
    }
}
