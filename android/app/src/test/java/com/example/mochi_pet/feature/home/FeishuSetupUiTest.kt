package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.ui.theme.MochiTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeishuSetupUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    @After fun restore() { Locale.setDefault(locale) }

    @Test fun `guided setup scrolls at large font and connects only after explicit action`() {
        Locale.setDefault(Locale.ENGLISH)
        val actions = mutableListOf<FeishuUiAction>()
        var dismissed = false
        compose.setContent {
            MochiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    FeishuSetupDialog(onAction = { actions.add(it) }, onDismiss = { dismissed = true })
                }
            }
        }
        compose.onNodeWithText("Authorize Feishu").assertIsNotEnabled()
        compose.onNodeWithText("Open Feishu console").performScrollTo().performClick()
        assertEquals(FeishuUiAction.OpenConsole, actions.single())
        compose.onNodeWithText("Copy redirect URL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("App ID").performScrollTo().performTextInput("cli_fixture")
        compose.onNodeWithText("App Secret").performScrollTo().performTextInput("synthetic-secret")
        compose.onNodeWithText("Authorize Feishu").performClick()
        val action = actions.last() as FeishuUiAction.Connect
        assertEquals("cli_fixture", action.appId)
        assertEquals("synthetic-secret", action.appSecret)
        assertTrue(dismissed)
    }

    @Test fun `Chinese setup retains reachable guide and cancel actions`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        var dismissed = false
        compose.setContent {
            MochiTheme { FeishuSetupDialog(onAction = {}, onDismiss = { dismissed = true }) }
        }
        compose.onNodeWithText("连接飞书").assertIsDisplayed()
        compose.onNodeWithText("飞书官方配置指南").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
    }
}
