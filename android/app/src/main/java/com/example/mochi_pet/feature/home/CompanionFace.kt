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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mochi_pet.core.wake.WakeCaptureStatus
import com.example.mochi_pet.core.wake.WakeRuntimeState
import kotlin.math.roundToInt

@Composable
internal fun CompanionFocusFrame(
    pipelineState: ChatPipelineUiState,
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
            FullscreenToggleButton(
                fullscreen = true,
                onClick = onExit,
                modifier = Modifier.testTag("companion-exit-fullscreen"),
            )
        }
        Box(Modifier.weight(1f)) {
            CompanionPipelineOverlay(pipelineState, content = content)
        }
    }
}

@Composable
internal fun CompanionPipelineOverlay(
    pipelineState: ChatPipelineUiState,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        content()
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .heightIn(max = maxHeight / 3)
                .verticalScroll(rememberScrollState())
                .testTag("companion-pipeline-overlay"),
        ) {
            ChatPipelineIndicator(pipelineState)
        }
    }
}

@Composable
internal fun MochiFace(
    pipelineState: ChatPipelineUiState,
    wakeState: WakeRuntimeState,
    focusMode: Boolean,
    onFocus: () -> Unit,
) {
    val inviting = !pipelineState.isActive && wakeState.status == WakeCaptureStatus.LISTENING
    val label = localizeUiText("Say \u201c${wakeState.keyword.phrase}\u201d")
    val bubbleText = buildAnnotatedString {
        append(label)
        val start = label.indexOf(wakeState.keyword.phrase)
        if (start >= 0) {
            addStyle(
                SpanStyle(color = Color(0xFFEDE4DC), fontWeight = FontWeight.Medium),
                start, start + wakeState.keyword.phrase.length,
            )
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("companion-face")
            .padding(horizontal = 12.dp, vertical = 16.dp),
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
                        (if (inviting) Modifier.testTag("companion-bubble") else Modifier.clearAndSetSemantics {})
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
                val room = (availableHeight.roundToPx() - bubble.height - buttonSpace).coerceAtLeast(0)
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
                    if (inviting) bubble.placeRelative(bubbleX, 0)
                }
            }
            if (!focusMode) {
                Spacer(Modifier.height(28.dp))
                FullscreenToggleButton(
                    fullscreen = false,
                    onClick = onFocus,
                    modifier = Modifier.testTag("companion-fullscreen"),
                )
            }
        }
    }
}

@Composable
internal fun FullscreenToggleButton(
    fullscreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 168.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF75665D)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDDC8BB)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Canvas(Modifier.size(15.dp).testTag(if (fullscreen) "fullscreen-collapse-icon" else "fullscreen-expand-icon")) {
                val stroke = 1.4.dp.toPx()
                val edge = size.width
                val arm = edge * .3f
                for (x in listOf(0f, edge)) {
                    for (y in listOf(0f, edge)) {
                        val innerX = if (x == 0f) arm else edge - arm
                        val innerY = if (y == 0f) arm else edge - arm
                        val corner = if (fullscreen) Offset(innerX, innerY) else Offset(x, y)
                        drawLine(Color(0xFFDDC8BB), corner, Offset(innerX, y), stroke)
                        drawLine(Color(0xFFDDC8BB), corner, Offset(x, innerY), stroke)
                    }
                }
            }
            Text(if (fullscreen) "Exit full screen" else "Full screen", style = MaterialTheme.typography.labelLarge)
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
        when (stage) {
            ChatPipelineStage.LISTENING -> 1.15f
            ChatPipelineStage.SKILLING -> .95f
            ChatPipelineStage.THINKING -> .85f
            ChatPipelineStage.SUBAGENT -> 1.05f
            ChatPipelineStage.TOOL -> .75f
            ChatPipelineStage.SUMMARY -> .3f
            ChatPipelineStage.SPEAKING -> .9f
            ChatPipelineStage.IDLE -> 1f
        },
        tween(280), label = "companion-eyes",
    )
    val gaze by animateFloatAsState(
        when (stage) {
            ChatPipelineStage.SKILLING -> -7f
            ChatPipelineStage.THINKING -> 7f
            ChatPipelineStage.SUBAGENT -> -3f
            ChatPipelineStage.TOOL -> 2f
            else -> 0f
        },
        tween(360), label = "companion-gaze",
    )
    val tilt by animateFloatAsState(
        when (stage) {
            ChatPipelineStage.SKILLING -> -4f
            ChatPipelineStage.THINKING -> 4f
            ChatPipelineStage.SUBAGENT -> -2f
            ChatPipelineStage.TOOL -> 2f
            else -> 0f
        },
        tween(420), label = "companion-head-tilt",
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
        modifier.semantics {
            contentDescription = "Mochi"
            if (stage != ChatPipelineStage.IDLE) stateDescription = localizeUiText(stage.displayText())
        }
            .graphicsLayer { scaleX = breath; scaleY = breath; rotationZ = tilt }
            .drawWithCache {
                val body = PathParser().parsePathString(
                    "M44 154C28 126 34 76 64 52C85 34 113 30 140 31C176 29 209 41 230 69" +
                        "C248 95 251 133 236 161C224 185 193 203 142 204C97 205 60 190 44 154Z",
                ).toPath()
                val leftPaw = PathParser().parsePathString("M61 174C48 176 45 190 54 198C63 207 84 207 91 198Z").toPath()
                val rightPaw = PathParser().parsePathString("M187 198C198 208 218 204 225 196C232 187 225 176 215 174Z").toPath()
                val raisedLeftPaw = PathParser().parsePathString("M63 176C42 174 36 150 48 143C62 136 77 157 74 173Z").toPath()
                val raisedRightPaw = PathParser().parsePathString("M217 176C238 174 244 150 232 143C218 136 203 157 206 173Z").toPath()
                val highlight = PathParser().parsePathString("M66 65Q95 43 127 43").toPath()
                val smile = PathParser().parsePathString("M125 154Q132 167 140 155Q148 167 155 154").toPath()
                val grin = PathParser().parsePathString("M123 153Q140 162 157 153Q154 178 140 178Q126 178 123 153Z").toPath()
                val curiousMouth = PathParser().parsePathString("M127 156Q137 171 153 155").toPath()
                val thoughtfulMouth = PathParser().parsePathString("M126 160Q135 154 144 160").toPath()
                val bodyBrush = Brush.radialGradient(
                    listOf(Color(0xFFFFFDF5), Color(0xFFF3E8DC), Color(0xFFD3B5A6)),
                    center = Offset(118f, 60f), radius = 230f,
                )
                val eyeBrush = Brush.verticalGradient(listOf(Color(0xFF4B3833), Color(0xFF261F22)), 80f, 145f)
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
                        drawPath(
                            if (stage == ChatPipelineStage.SKILLING || stage == ChatPipelineStage.SUBAGENT) raisedLeftPaw else leftPaw,
                            Color(0xFFE8D6C8),
                        )
                        if (stage == ChatPipelineStage.THINKING) {
                            drawOval(Color(0xFFE8D6C8), Offset(174f, 153f), Size(30f, 37f))
                            drawArc(Color(0xFFBDA18E), 195f, 100f, false, Offset(181f, 160f), Size(15f, 20f), style = Stroke(2f))
                        } else {
                            drawPath(if (stage == ChatPipelineStage.SUBAGENT) raisedRightPaw else rightPaw, Color(0xFFE8D6C8))
                        }
                        for ((index, x) in listOf(98f + gaze, 182f + gaze).withIndex()) {
                            val radius = if (stage == ChatPipelineStage.LISTENING) 23f else 20f
                            val eyeHalfHeight = 28f * openness * if (stage == ChatPipelineStage.THINKING && index == 1) .6f else 1f
                            if (stage == ChatPipelineStage.SUMMARY) {
                                drawArc(
                                    Color(0xFF4B3833), 180f, 180f, false, Offset(x - 19f, 106f), Size(38f, 26f),
                                    style = Stroke(6f, cap = StrokeCap.Round),
                                )
                            } else {
                                drawOval(eyeBrush, Offset(x - radius, 113f - eyeHalfHeight), Size(radius * 2, eyeHalfHeight * 2))
                                drawOval(
                                    Color.White.copy(alpha = .94f), Offset(x - 12f, 119f - eyeHalfHeight),
                                    Size(14f, minOf(18f, eyeHalfHeight * .65f)),
                                )
                                drawCircle(Color(0xFFD9B8A4), 3.5f, Offset(x + 8f, 113f + eyeHalfHeight - 8f))
                            }
                            if (stage == ChatPipelineStage.LISTENING || stage == ChatPipelineStage.SKILLING || stage == ChatPipelineStage.THINKING) {
                                val browY = when (stage) {
                                    ChatPipelineStage.LISTENING -> 70f
                                    ChatPipelineStage.SKILLING -> if (index == 0) 73f else 83f
                                    else -> if (index == 0) 83f else 73f
                                }
                                drawArc(
                                    Color(0xFF9D7B6C), 190f, 150f, false, Offset(x - 18f, browY), Size(36f, 16f),
                                    style = Stroke(3f, cap = StrokeCap.Round),
                                )
                            } else if (stage == ChatPipelineStage.TOOL) {
                                drawLine(
                                    Color(0xFF9D7B6C), Offset(x - 16f, if (index == 0) 85f else 90f),
                                    Offset(x + 16f, if (index == 0) 90f else 85f), 3f, StrokeCap.Round,
                                )
                            }
                        }
                        for (x in listOf(71f, 209f)) {
                            drawOval(
                                Brush.radialGradient(
                                    listOf(Color(0x70E99C86), Color.Transparent), Offset(x, 151f), 27f,
                                ),
                                Offset(x - 27f, 135f), Size(54f, 32f),
                            )
                        }
                        when (stage) {
                            ChatPipelineStage.SPEAKING -> {
                                val height = 14f + speech * 16f
                                drawOval(Color(0xFF7A5145), Offset(126f, 149f), Size(28f, height))
                                drawOval(Color(0xFFE5A092), Offset(133f, 149f + height * .58f), Size(14f, height * .3f))
                            }
                            ChatPipelineStage.LISTENING -> {
                                drawOval(Color(0xFF7A5145), Offset(130f, 150f), Size(20f, 24f))
                                drawOval(Color(0xFFE5A092), Offset(135f, 164f), Size(10f, 6f))
                            }
                            ChatPipelineStage.SUMMARY, ChatPipelineStage.SUBAGENT -> {
                                drawPath(grin, Color(0xFF7A5145))
                                drawOval(Color(0xFFE5A092), Offset(132f, 168f), Size(16f, 8f))
                            }
                            ChatPipelineStage.THINKING ->
                                drawPath(thoughtfulMouth, Color(0xFF7A5145), style = Stroke(3.5f, cap = StrokeCap.Round))
                            ChatPipelineStage.SKILLING ->
                                drawPath(curiousMouth, Color(0xFF7A5145), style = Stroke(3.5f, cap = StrokeCap.Round))
                            ChatPipelineStage.IDLE, ChatPipelineStage.TOOL -> {
                                drawPath(smile, Color(0xFF7A5145), style = Stroke(3.5f, cap = StrokeCap.Round))
                                if (stage == ChatPipelineStage.TOOL) {
                                    drawOval(Color(0xFFE5A092), Offset(144f, 161f), Size(10f, 9f))
                                }
                            }
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
