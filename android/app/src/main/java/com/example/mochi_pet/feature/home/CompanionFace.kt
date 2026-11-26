package com.example.mochi_pet.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeRuntimeState
import kotlin.math.roundToInt

@Composable
internal fun MochiFace(
    pipelineState: ChatPipelineUiState,
    wakeState: WakeRuntimeState,
    focusMode: Boolean,
    onFocus: () -> Unit,
) {
    val inviting = !pipelineState.isActive && wakeState.status == WakeCaptureStatus.LISTENING
    val detail = pipelineState.detail?.takeIf { pipelineState.isActive && it.isNotBlank() }?.take(120)
    val label = localizeUiText(
        if (pipelineState.isActive) {
            pipelineState.stage.displayText()
        } else {
            when (wakeState.status) {
                WakeCaptureStatus.LISTENING -> "Say \u201c${wakeState.keyword.phrase}\u201d"
                WakeCaptureStatus.DISABLED -> "Wake word off"
                WakeCaptureStatus.STARTING -> "Preparing wake word"
                WakeCaptureStatus.PAUSED -> "Wake word paused"
                WakeCaptureStatus.ERROR -> "Wake unavailable"
            }
        },
    )
    val bubbleText = buildAnnotatedString {
        append(label)
        if (inviting) {
            val start = label.indexOf(wakeState.keyword.phrase)
            if (start >= 0) {
                addStyle(
                    SpanStyle(color = Color(0xFFEDE4DC), fontWeight = FontWeight.Medium),
                    start, start + wakeState.keyword.phrase.length,
                )
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("companion-face")
            .padding(start = 12.dp, end = 12.dp, top = if (focusMode) 80.dp else 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val availableHeight = maxHeight
        val fontScale = LocalDensity.current.fontScale
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Layout(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                content = {
                    CompanionCharacter(pipelineState.stage, Modifier.testTag("companion-character"))
                    Box(
                        Modifier.testTag("companion-bubble")
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .drawWithCache {
                                val inset = 1.dp.toPx()
                                val w = size.width - inset
                                val h = size.height - 20.dp.toPx()
                                val r = 24.dp.toPx().coerceAtMost(h / 2)
                                val path = Path().apply {
                                    moveTo(inset + r, inset)
                                    lineTo(w - r, inset)
                                    quadraticTo(w, inset, w, inset + r)
                                    lineTo(w, h - r)
                                    quadraticTo(w, h, w - r, h)
                                    lineTo(w * .49f, h)
                                    lineTo(w * .30f, size.height - inset)
                                    lineTo(w * .36f, h)
                                    lineTo(inset + r, h)
                                    quadraticTo(inset, h, inset, h - r)
                                    lineTo(inset, inset + r)
                                    quadraticTo(inset, inset, inset + r, inset)
                                    close()
                                }
                                onDrawBehind {
                                    drawPath(path, Color(0xFF302D30))
                                    drawPath(path, Color(0xFF756B65).copy(alpha = .65f), style = Stroke(.8.dp.toPx()))
                                }
                            }.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 38.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            bubbleText, Modifier.testTag("companion-bubble-text"),
                            color = Color(0xFFBCB3AD), fontSize = 14.sp, lineHeight = 24.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
            ) { measurables, constraints ->
                val bubbleWidth = minOf(constraints.maxWidth, (224.dp * fontScale).roundToPx())
                val bubble = measurables[1].measure(Constraints.fixedWidth(bubbleWidth))
                val buttonSpace = if (focusMode) 0 else (80.dp * fontScale).roundToPx()
                val detailSpace = if (detail == null) 0 else (64.dp * fontScale).roundToPx()
                val room = (availableHeight.roundToPx() - bubble.height - buttonSpace - detailSpace).coerceAtLeast(0)
                val characterWidth = minOf(
                    constraints.maxWidth,
                    (if (focusMode) 288.dp else 248.dp).roundToPx(),
                    (room / (240f / 280f - .17f)).roundToInt().coerceAtLeast(100.dp.roundToPx()),
                )
                val character = measurables[0].measure(
                    Constraints.fixed(characterWidth, (characterWidth * 240f / 280f).roundToInt()),
                )
                // Only the crown overlaps; the tail stays above the eyes even when text wraps.
                val characterY = bubble.height - (characterWidth * .17f).roundToInt()
                layout(constraints.maxWidth, characterY + character.height) {
                    character.placeRelative((constraints.maxWidth - character.width) / 2, characterY)
                    val bubbleX = ((constraints.maxWidth - bubble.width) / 2 + 42.dp.roundToPx())
                        .coerceAtMost(constraints.maxWidth - bubble.width)
                    bubble.placeRelative(bubbleX, 0)
                }
            }
            if (detail != null) {
                MaterialText(
                    detail,
                    Modifier.widthIn(max = 360.dp).padding(top = 12.dp).testTag("companion-detail"),
                    color = Color(0xFFBCB3AD), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!focusMode) {
                Spacer(Modifier.height(28.dp))
                OutlinedButton(
                    onClick = onFocus,
                    modifier = Modifier.testTag("companion-fullscreen").heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF75665D)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDDC8BB)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Canvas(Modifier.size(15.dp)) {
                            val stroke = 1.4.dp.toPx()
                            val edge = size.width
                            val arm = edge * .3f
                            for (x in listOf(0f, edge)) {
                                for (y in listOf(0f, edge)) {
                                    drawLine(Color(0xFFDDC8BB), Offset(x, y), Offset(if (x == 0f) arm else edge - arm, y), stroke)
                                    drawLine(Color(0xFFDDC8BB), Offset(x, y), Offset(x, if (y == 0f) arm else edge - arm), stroke)
                                }
                            }
                        }
                        Text("Full screen", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionCharacter(stage: ChatPipelineStage, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "companion-breath")
    val breath by transition.animateFloat(
        initialValue = .985f, targetValue = 1.018f,
        animationSpec = infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "companion-breath-scale",
    )
    val openness by animateFloatAsState(
        if (stage == ChatPipelineStage.SUMMARY) .4f else if (stage == ChatPipelineStage.SPEAKING) .8f else 1f,
        tween(280), label = "companion-eyes",
    )
    val gaze by animateFloatAsState(
        when (stage) {
            ChatPipelineStage.SKILLING -> -5f
            ChatPipelineStage.THINKING -> 5f
            ChatPipelineStage.SUBAGENT, ChatPipelineStage.TOOL -> 3f
            else -> 0f
        },
        tween(360), label = "companion-gaze",
    )
    val speech = if (stage == ChatPipelineStage.SPEAKING) {
        val speaking = rememberInfiniteTransition(label = "companion-speaking")
        val amount by speaking.animateFloat(
            .35f, 1f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "companion-mouth",
        )
        amount
    } else {
        0f
    }
    Box(
        modifier.semantics { contentDescription = "Mochi" }
            .graphicsLayer { scaleX = breath; scaleY = breath }
            .drawWithCache {
                val body = PathParser().parsePathString(
                    "M44 154C28 126 34 76 64 52C85 34 113 30 140 31C176 29 209 41 230 69" +
                        "C248 95 251 133 236 161C224 185 193 203 142 204C97 205 60 190 44 154Z",
                ).toPath()
                val leftPaw = PathParser().parsePathString("M61 174C48 176 45 190 54 198C63 207 84 207 91 198Z").toPath()
                val rightPaw = PathParser().parsePathString("M187 198C198 208 218 204 225 196C232 187 225 176 215 174Z").toPath()
                val highlight = PathParser().parsePathString("M66 65Q95 43 127 43").toPath()
                val smile = PathParser().parsePathString("M131 145Q140 156 149 145").toPath()
                val bodyBrush = Brush.radialGradient(
                    listOf(Color(0xFFFFFDF5), Color(0xFFF3E8DC), Color(0xFFD3B5A6)),
                    center = Offset(118f, 60f), radius = 230f,
                )
                val eyeBrush = Brush.verticalGradient(listOf(Color(0xFF4B3833), Color(0xFF261F22)), 91f, 137f)
                onDrawBehind {
                    withTransform({ scale(size.width / 280f, size.height / 240f, Offset.Zero) }) {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color(0x14EFB18D), Color.Transparent), Offset(140f, 120f), 138f,
                            ),
                            138f, Offset(140f, 120f),
                        )
                        drawOval(Color.Black.copy(alpha = .15f), Offset(66f, 207f), Size(148f, 20f))
                        drawPath(body, bodyBrush)
                        drawPath(leftPaw, Color(0xFFE8D6C8))
                        drawPath(rightPaw, Color(0xFFE8D6C8))
                        for (x in listOf(101f + gaze, 180f + gaze)) {
                            val radius = if (stage == ChatPipelineStage.LISTENING) 18f else 16f
                            drawOval(eyeBrush, Offset(x - radius, 114f - 23f * openness), Size(radius * 2, 46f * openness))
                            if (openness > .55f) {
                                drawOval(Color.White.copy(alpha = .94f), Offset(x - 11f, 97f), Size(12f, 16f))
                                drawCircle(Color(0xFFD9B8A4), 3f, Offset(x + 6f, 126f))
                            }
                        }
                        for (x in listOf(71f, 209f)) {
                            drawOval(
                                Brush.radialGradient(
                                    listOf(Color(0x70E99C86), Color.Transparent), Offset(x, 145f), 26f,
                                ),
                                Offset(x - 26f, 129f), Size(52f, 32f),
                            )
                        }
                        when (stage) {
                            ChatPipelineStage.SPEAKING ->
                                drawOval(Color(0xFF7A5145), Offset(131f, 141f), Size(18f, 8f + speech * 12f))
                            ChatPipelineStage.LISTENING ->
                                drawCircle(Color(0xFF7A5145), 6f, Offset(140f, 149f))
                            ChatPipelineStage.IDLE, ChatPipelineStage.SUMMARY ->
                                drawPath(smile, Color(0xFF7A5145), style = Stroke(3f, cap = StrokeCap.Round))
                            else ->
                                drawLine(Color(0xFF7A5145), Offset(133f, 148f), Offset(147f, 148f), 3f, StrokeCap.Round)
                        }
                        for (y in listOf(135f, 144f)) {
                            drawLine(Color(0xA6B38D7B), Offset(61f, y), Offset(48f, y + if (y == 135f) -3f else 2f), 2f, StrokeCap.Round)
                            drawLine(Color(0xA6B38D7B), Offset(219f, y), Offset(232f, y + if (y == 135f) -3f else 2f), 2f, StrokeCap.Round)
                        }
                        drawPath(highlight, Color.White.copy(alpha = .36f), style = Stroke(6f, cap = StrokeCap.Round))
                    }
                }
            },
    )
}
