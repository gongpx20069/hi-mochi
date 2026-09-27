package com.example.mochi_pet.feature.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.example.mochi_pet.R
import androidx.compose.ui.unit.Density
import com.example.mochi_pet.ui.theme.MochiTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusStandbyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `portrait keeps time date and cute monochrome mascot with sparse pixels`() {
        render("23:48", "9月27日\n星期日")
        checkFixture("standby-portrait.png")
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land")
    fun `landscape keeps full twenty four hour time and English date`() {
        render("23:48", "Sunday\nSeptember 27")
        checkFixture("standby-landscape.png")
    }

    @Test fun `large fonts still show full time and date on narrow screens`() {
        render("23:48", "Sunday\nSeptember 27", 2f)
        checkFixture("standby-large-font.png")
    }

    @Test
    @Config(qualifiers = "w568dp-h240dp-land")
    fun `short landscape fits large text and the longest English date`() {
        render("12:58", "Wednesday\nSeptember 30", 2f)
        checkFixture("standby-short-landscape.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet keeps the mascot and clock compact instead of stretching across the display`() {
        render("23:48", "Wednesday\nSeptember 30")
        checkFixture("standby-tablet.png")
    }

    private fun render(time: String, date: String, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MochiTheme {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { StandbyClock(time, date) }
                }
            }
        }
        compose.onNodeWithText(time).assertIsDisplayed()
        compose.onNodeWithText(date).assertIsDisplayed()
        compose.onNodeWithTag("standby-mochi").assertIsDisplayed()
        val clock = compose.onNodeWithTag("standby-clock").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("standby-time", "standby-date", "standby-mochi")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag clipped", bounds.left >= clock.left && bounds.right <= clock.right &&
                bounds.top >= clock.top && bounds.bottom <= clock.bottom)
        }
        val fontSizes = mutableListOf<Float>()
        for (tag in listOf("standby-time", "standby-date")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$tag text overflows: ${layouts.map { "${it.size}, ${it.multiParagraph.width}, ${it.multiParagraph.height}" }}",
                layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
            val input = layouts.single().layoutInput
            assertEquals(if (tag == "standby-date") 2 else 1, layouts.single().lineCount)
            if (tag == "standby-time") {
                assertEquals(FontWeight.Bold, input.style.fontWeight)
                assertTrue("Clock must have a thick outline", input.style.drawStyle is Stroke)
            } else {
                assertEquals(FontWeight.Normal, input.style.fontWeight)
                assertEquals(FontFamily(Font(R.font.aoyagi_reisho)), input.style.fontFamily)
                assertEquals("Date must remain solid", Fill, input.style.drawStyle)
            }
            fontSizes += with(input.density) { input.style.fontSize.toPx() }
        }
        assertEquals("Date stays smaller at forty percent of the clock font size", fontSizes[0] * .4f, fontSizes[1], .1f)
    }

    private fun checkFixture(name: String) {
        val node = compose.onNodeWithTag("standby-clock").fetchSemanticsNode()
        val view = requireNotNull(node.root as? ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        var illuminated = 0
        var intensity = 0L
        val bounds = node.boundsInRoot
        var count = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                assertTrue("Standby must be monochrome", r == android.graphics.Color.green(pixel) &&
                    r == android.graphics.Color.blue(pixel))
                if (r > 0) illuminated++
                intensity += r
                count++
            }
        }
        val file = File("build/reports/$name")
        file.parentFile.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        checkHollowClock(bitmap)
        bitmap.recycle()
        assertTrue("Fixture must contain visible clock and mascot", illuminated > count * .005)
        assertTrue("At least 72% of pixels must remain black", illuminated < count * .28)
        assertTrue("Average channel intensity ${intensity.toDouble() / count} must stay below 30/255", intensity.toDouble() / count < 30.0)
    }

    private fun checkHollowClock(screen: Bitmap) {
        val node = compose.onNodeWithTag("standby-time").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("standby-time").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val filled = Bitmap.createBitmap(layout.size.width, layout.size.height, Bitmap.Config.ARGB_8888)
        layout.multiParagraph.paint(
            Canvas(filled.asImageBitmap()), color = layout.layoutInput.style.color, drawStyle = Fill,
        )
        var outlineIntensity = 0L
        var fillIntensity = 0L
        val bounds = node.boundsInRoot
        for (y in 0 until filled.height) {
            for (x in 0 until filled.width) {
                outlineIntensity += android.graphics.Color.red(screen.getPixel(bounds.left.toInt() + x, bounds.top.toInt() + y))
                fillIntensity += android.graphics.Color.red(filled.getPixel(x, y))
            }
        }
        filled.recycle()
        assertTrue("Outline must remain visible", outlineIntensity > 0)
        assertTrue("Hollow digits must light substantially less than the same filled font",
            outlineIntensity < fillIntensity * .8)
    }
}
