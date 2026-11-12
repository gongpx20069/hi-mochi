package com.example.mochi_pet.feature.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.core.update.AppUpdate
import com.example.mochi_ui.MochiTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val locale = Locale.getDefault()
    private val release = "https://github.com/gongpx20069/hi-mochi/releases/tag/v9.0.0"
    private val apk = "https://github.com/gongpx20069/hi-mochi/releases/download/v9.0.0/Mochi-v9.0.0-arm64-v8a.apk"
    @After fun restoreLocale() { Locale.setDefault(locale) }

    @Test fun `download action opens the selected APK rather than the release page`() {
        Locale.setDefault(Locale.ENGLISH)
        var opened: String? = null
        var dismissed = false
        compose.setContent {
            MochiTheme {
                AppUpdateDialog(AppUpdate("9.0.0", release, "", apk), { dismissed = true }, { opened = it })
            }
        }
        compose.onNodeWithText("Later").performClick()
        assertTrue(dismissed)
        assertNull(opened)
        compose.onNodeWithText("Download APK").performClick()
        assertEquals(apk, opened)
    }

    @Test fun `missing APK explains release page fallback in Chinese with large text`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        var opened: String? = null
        compose.setContent {
            MochiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    AppUpdateDialog(AppUpdate("9.0.0", release, "Release notes\n".repeat(80), null), {}, { opened = it })
                }
            }
        }
        compose.onNodeWithText(localizeUiText(
            "No compatible APK was found in this release. Open the release page to choose a download.",
        )).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查看发布页面").assertIsDisplayed().performClick()
        assertEquals(release, opened)
    }
}
