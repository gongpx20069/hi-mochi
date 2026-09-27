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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StandbyClock(time: String, date: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.fillMaxSize().padding(24.dp).testTag("standby-clock"),
        contentAlignment = Alignment.Center,
    ) {
        val landscape = maxWidth > maxHeight && maxWidth >= 480.dp
        val clockWidth = if (landscape) minOf(maxWidth * .62f, 520.dp) else minOf(maxWidth, 400.dp)
        val maxTimeSize = with(LocalDensity.current) {
            minOf(112.dp, clockWidth * .37f, maxHeight * .38f).toSp()
        }
        val maxDateSize = with(LocalDensity.current) { minOf(16.sp.toDp(), 24.dp).toSp() }
        val faceWidth = minOf(
            if (landscape) minOf(maxWidth * .25f, 180.dp) else 148.dp,
            maxHeight * .48f,
        )
        val clock: @Composable () -> Unit = {
            Column(
                Modifier.widthIn(max = clockWidth).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    time, Modifier.fillMaxWidth().testTag("standby-time"),
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light,
                        color = Color(0xFFB8B8B8), textAlign = TextAlign.Center,
                        letterSpacing = (-2).sp, fontFeatureSettings = "tnum",
                    ),
                    maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = maxTimeSize, stepSize = 1.sp),
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    date, Modifier.fillMaxWidth().testTag("standby-date"),
                    style = TextStyle(
                        fontSize = maxDateSize, fontWeight = FontWeight.Normal,
                        color = Color(0xFF777777), textAlign = TextAlign.Center,
                    ),
                    maxLines = 2,
                )
            }
        }
        if (landscape) {
            Row(
                Modifier.widthIn(max = 760.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SleepingStandbyMochi(Modifier.size(faceWidth, faceWidth * .82f))
                clock()
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SleepingStandbyMochi(Modifier.size(faceWidth, faceWidth * .82f))
                Spacer(Modifier.height(22.dp))
                clock()
            }
        }
    }
}

@Composable
private fun SleepingStandbyMochi(modifier: Modifier) {
    Box(modifier.testTag("standby-mochi").drawWithCache {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * .16f, h * .78f)
            cubicTo(w * .06f, h * .64f, w * .14f, h * .38f, w * .24f, h * .27f)
            cubicTo(w * .35f, h * .12f, w * .62f, h * .10f, w * .76f, h * .27f)
            cubicTo(w * .87f, h * .39f, w * .95f, h * .67f, w * .83f, h * .79f)
            cubicTo(w * .69f, h * .88f, w * .29f, h * .88f, w * .16f, h * .78f)
            close()
        }
        val face = Path().apply {
            for (x in listOf(.36f, .64f)) {
                moveTo(w * (x - .065f), h * .49f)
                cubicTo(w * (x - .04f), h * .55f, w * (x + .04f), h * .55f, w * (x + .065f), h * .49f)
            }
            moveTo(w * .455f, h * .605f)
            cubicTo(w * .47f, h * .64f, w * .49f, h * .64f, w * .5f, h * .607f)
            cubicTo(w * .51f, h * .64f, w * .53f, h * .64f, w * .545f, h * .605f)
        }
        val paws = Path().apply {
            moveTo(w * .23f, h * .76f)
            cubicTo(w * .27f, h * .70f, w * .34f, h * .72f, w * .36f, h * .78f)
            moveTo(w * .64f, h * .78f)
            cubicTo(w * .66f, h * .72f, w * .73f, h * .70f, w * .77f, h * .76f)
        }
        val sleep = Path().apply {
            moveTo(w * .80f, h * .17f)
            lineTo(w * .85f, h * .17f)
            lineTo(w * .80f, h * .23f)
            lineTo(w * .85f, h * .23f)
            moveTo(w * .88f, h * .06f)
            lineTo(w * .95f, h * .06f)
            lineTo(w * .88f, h * .14f)
            lineTo(w * .95f, h * .14f)
        }
        onDrawBehind {
            // Black interior leaves most OLED pixels off; only a small silhouette and face are lit.
            drawPath(body, Color(0xFF686868), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            drawPath(face, Color(0xFFB0B0B0), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            drawPath(paws, Color(0xFF686868), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            drawPath(sleep, Color(0xFF555555), style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
            for (x in listOf(.235f, .695f)) {
                drawOval(Color(0xFF383838), Offset(w * x, h * .59f), Size(w * .07f, h * .027f))
            }
        }
    })
}
