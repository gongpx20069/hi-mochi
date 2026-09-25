package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_ui.ExtensionSetupScreen
import com.example.mochi_ui.MochiTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtensionSetupUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @Before fun english() { Locale.setDefault(Locale.ENGLISH) }
    @After fun restore() { Locale.setDefault(locale) }

    @Test
    fun `not now grants nothing and discloses disabled tool restoration`() {
        var enabled = false
        var dismissed = false
        compose.setContent {
            MochiTheme {
                ExtensionSetupCompletionDialog(
                    ExtensionSetupKind.TERMUX, { enabled = true }, { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("This also enables any disabled tools required by the Skill. Other tool choices are preserved.").assertExists()
        compose.onNodeWithText("Background Shell authorization is unchanged and must be granted separately.").assertExists()
        compose.onNodeWithText("Not now").performClick()
        assertFalse(enabled)
        assertEquals(true, dismissed)
    }

    @Test
    fun `primary action remains reachable on narrow window with large text`() {
        var clicked = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MochiTheme {
                    Box(Modifier.size(320.dp, 480.dp)) {
                        ExtensionSetupScreen(
                            "Termux", listOf("Prepare", "Authorize", "Verify"), 1,
                            "Back", {}, "Check connection", { clicked = true },
                        ) {
                            repeat(20) { Text("A long setup instruction that wraps onto multiple lines.") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Check connection").assertIsDisplayed().performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun `completion rejects unknown packages and untrusted snapshots`() {
        assertNull(ExtensionSetupKind.fromPackage("com.termux"))
        assertEquals(ExtensionSetupKind.TERMUX, ExtensionSetupKind.fromPackage("com.example.mochi_pet.extension.termux"))
        assertFalse(ExtensionSetupKind.TERMUX.connected(ToolCatalogSummary(
            termux = ExtensionProviderSummary(connected = true, trusted = false),
        )))
        assertFalse(ExtensionSetupKind.MIJIA.connected(ToolCatalogSummary()))
        assertFalse(ExtensionSetupKind.AGENTLINK.connected(ToolCatalogSummary()))
    }
}
