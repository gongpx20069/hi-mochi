package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.R

@OptIn(ExperimentalTextApi::class)
private val StandbyFont = FontFamily(
    Font(
        R.font.nunito,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
)

@Composable
internal fun StandbyClock(time: String, date: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.fillMaxSize().padding(24.dp).testTag("standby-clock"),
        contentAlignment = Alignment.Center,
    ) {
        val landscape = maxWidth > maxHeight && maxWidth >= 480.dp
        val faceWidth = minOf(
            if (landscape) minOf(maxWidth * .28f, 220.dp) else 196.dp,
            maxHeight * (if (landscape) .8f else .32f),
        )
        val faceHeight = faceWidth * .82f
        val clockWidth = if (landscape) minOf(maxWidth - faceWidth - 28.dp, 520.dp) else minOf(maxWidth, 440.dp)
        val clockHeight = if (landscape) maxHeight else maxHeight - faceHeight - 20.dp
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        // Fit the pair together: shrinking only the date would break its half-clock size contract.
        val styles = remember(time, date, clockWidth, clockHeight, density, measurer) {
            with(density) {
                val constraints = Constraints(maxWidth = clockWidth.roundToPx())
                (128 downTo 12 step 2).firstNotNullOf { size ->
                    val timeStyle = TextStyle(
                        fontFamily = StandbyFont, fontWeight = FontWeight.ExtraBold,
                        fontSize = size.dp.toSp(), lineHeight = (size * 1.12f).dp.toSp(),
                        textAlign = TextAlign.Center, fontFeatureSettings = "tnum",
                        color = Color(0xFFCCCCCC),
                    )
                    val dateStyle = timeStyle.copy(
                        fontSize = (size / 2f).dp.toSp(), lineHeight = (size * .62f).dp.toSp(),
                        color = Color(0xFFAAAAAA),
                    )
                    val timeLayout = measurer.measure(time, timeStyle, softWrap = false, maxLines = 1, constraints = constraints)
                    val dateLayout = measurer.measure(date, dateStyle, maxLines = 2, constraints = constraints)
                    if (!timeLayout.hasVisualOverflow && !dateLayout.hasVisualOverflow &&
                        timeLayout.size.height + dateLayout.size.height + 8.dp.roundToPx() <= clockHeight.roundToPx()
                    ) {
                        timeStyle to dateStyle
                    } else {
                        null
                    }
                }
            }
        }
        val clock: @Composable () -> Unit = {
            Column(
                Modifier.widthIn(max = clockWidth).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    time, Modifier.fillMaxWidth().testTag("standby-time"),
                    style = styles.first,
                    maxLines = 1, softWrap = false,
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    date, Modifier.fillMaxWidth().testTag("standby-date"),
                    style = styles.second,
                    maxLines = 2,
                )
            }
        }
        if (landscape) {
            Row(
                Modifier.widthIn(max = 800.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatStandbyMochi(Modifier.size(faceWidth, faceHeight))
                clock()
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CatStandbyMochi(Modifier.size(faceWidth, faceHeight))
                Spacer(Modifier.height(20.dp))
                clock()
            }
        }
    }
}

@Composable
private fun CatStandbyMochi(modifier: Modifier) {
    Box(modifier.testTag("standby-mochi").drawWithCache {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * .13f, h * .70f)
            cubicTo(w * .06f, h * .43f, w * .19f, h * .13f, w * .40f, h * .12f)
            cubicTo(w * .60f, h * .08f, w * .80f, h * .15f, w * .86f, h * .40f)
            cubicTo(w * .93f, h * .57f, w * .92f, h * .76f, w * .78f, h * .83f)
            cubicTo(w * .62f, h * .91f, w * .35f, h * .91f, w * .21f, h * .82f)
            cubicTo(w * .16f, h * .80f, w * .14f, h * .75f, w * .13f, h * .70f)
            close()
        }
        val mouth = Path().apply {
            moveTo(w * .435f, h * .65f)
            cubicTo(w * .45f, h * .70f, w * .485f, h * .70f, w * .50f, h * .645f)
            cubicTo(w * .515f, h * .70f, w * .55f, h * .70f, w * .565f, h * .65f)
        }
        val nose = Path().apply {
            moveTo(w * .48f, h * .60f)
            quadraticTo(w * .50f, h * .585f, w * .52f, h * .60f)
            quadraticTo(w * .50f, h * .64f, w * .48f, h * .60f)
            close()
        }
        val whiskers = Path().apply {
            for (y in listOf(.58f, .66f)) {
                moveTo(w * .08f, h * (y - .025f))
                lineTo(w * .23f, h * y)
                moveTo(w * .77f, h * y)
                lineTo(w * .92f, h * (y - .025f))
            }
        }
        onDrawBehind {
            val outline = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
            drawPath(body, Color(0xFF181818))
            drawPath(body, Color(0xFF909090), style = outline)
            for (x in listOf(.29f, .59f)) {
                drawOval(Color(0xFFC8C8C8), Offset(w * x, h * .405f), Size(w * .12f, h * .16f))
                drawOval(Color(0xFF181818), Offset(w * (x + .025f), h * .42f), Size(w * .053f, h * .066f))
                drawCircle(Color(0xFF181818), w * .013f, Offset(w * (x + .085f), h * .52f))
            }
            drawPath(nose, Color(0xFFB0B0B0))
            drawPath(mouth, Color(0xFFC8C8C8), style = outline)
            drawPath(whiskers, Color(0xFF777777), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            for (x in listOf(.21f, .69f)) {
                drawOval(Color(0xFF454545), Offset(w * x, h * .68f), Size(w * .10f, h * .04f))
            }
            for (x in listOf(.25f, .59f)) {
                val position = Offset(w * x, h * .77f)
                val pawSize = Size(w * .16f, h * .15f)
                drawOval(Color(0xFF181818), position, pawSize)
                drawOval(Color(0xFF909090), position, pawSize, style = outline)
                for (toe in listOf(.055f, .10f)) {
                    drawLine(
                        Color(0xFF777777), Offset(w * (x + toe), h * .855f),
                        Offset(w * (x + toe), h * .89f), 1.3.dp.toPx(), StrokeCap.Round,
                    )
                }
            }
        }
    })
}
