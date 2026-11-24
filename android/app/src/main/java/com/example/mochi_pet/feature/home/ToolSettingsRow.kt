package com.example.mochi_pet.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ToolSettingsRow(
    title: String, detail: String, icon: ToolGlyph, enabled: Boolean, tag: String, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolGlyphIcon(icon, modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ToolGlyphIcon(ToolGlyph.CHEVRON, modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal enum class ToolGlyph { APPS, PLAYER, ASSIST, RETURN, CHEVRON, CLOSE, SEARCH, WEBSITES, SEND, STOP, SPEAKER, MUTED, MOCHI, CALENDAR, SKILLS, TOOLS }

@Composable
internal fun ToolGlyphIcon(glyph: ToolGlyph, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val path = Path()
            when (glyph) {
                ToolGlyph.MOCHI -> {
                    path.moveTo(4f, 10f); path.lineTo(4f, 3f); path.lineTo(9f, 6f)
                    path.quadraticTo(12f, 5f, 15f, 6f)
                    path.lineTo(20f, 3f); path.lineTo(20f, 10f)
                    path.cubicTo(24f, 23f, 0f, 23f, 4f, 10f)
                    drawCircle(color, 1f, Offset(8f, 12f))
                    drawCircle(color, 1f, Offset(16f, 12f))
                    path.moveTo(10f, 16f); path.quadraticTo(12f, 18f, 14f, 16f)
                }
                ToolGlyph.CALENDAR -> {
                    drawRoundRect(color, Offset(3f, 5f), Size(18f, 16f), CornerRadius(3f), style = stroke)
                    path.moveTo(7f, 3f); path.lineTo(7f, 7f)
                    path.moveTo(17f, 3f); path.lineTo(17f, 7f)
                    path.moveTo(3f, 10f); path.lineTo(21f, 10f)
                    path.moveTo(7f, 14f); path.lineTo(10f, 14f)
                    path.moveTo(14f, 17f); path.lineTo(17f, 17f)
                }
                ToolGlyph.SKILLS -> {
                    path.moveTo(4f, 4f); path.lineTo(9f, 4f)
                    path.cubicTo(7f, -1f, 17f, -1f, 15f, 4f)
                    path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                    path.cubicTo(15f, 7f, 15f, 17f, 20f, 15f)
                    path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                    path.cubicTo(17f, 15f, 7f, 15f, 9f, 20f)
                    path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                    path.cubicTo(9f, 17f, 9f, 7f, 4f, 9f)
                    path.close()
                }
                ToolGlyph.TOOLS -> {
                    drawRoundRect(color, Offset(3f, 7f), Size(18f, 14f), CornerRadius(3f), style = stroke)
                    path.moveTo(8f, 7f); path.lineTo(8f, 3f); path.lineTo(16f, 3f); path.lineTo(16f, 7f)
                    path.moveTo(3f, 12f); path.lineTo(21f, 12f)
                    path.moveTo(9f, 11f); path.lineTo(9f, 15f)
                    path.moveTo(15f, 11f); path.lineTo(15f, 15f)
                }
                ToolGlyph.APPS -> for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) {
                    drawRoundRect(color, Offset(x, y), Size(7f, 7f), CornerRadius(1.5f), style = stroke)
                }
                ToolGlyph.PLAYER -> {
                    path.moveTo(10f, 17f); path.lineTo(10f, 5f); path.lineTo(20f, 3f); path.lineTo(20f, 15f)
                    drawCircle(color, 3f, Offset(7f, 17f), style = stroke)
                    drawCircle(color, 3f, Offset(17f, 15f), style = stroke)
                }
                ToolGlyph.ASSIST -> {
                    drawRoundRect(color, Offset(6f, 2f), Size(12f, 20f), CornerRadius(2f), style = stroke)
                    drawCircle(color, 2f, Offset(12f, 11f), style = stroke)
                    path.moveTo(11f, 18f); path.lineTo(13f, 18f)
                }
                ToolGlyph.RETURN -> {
                    drawRoundRect(color, Offset(10f, 3f), Size(11f, 18f), CornerRadius(2f), style = stroke)
                    path.moveTo(3f, 12f); path.lineTo(16f, 12f)
                    path.moveTo(12f, 8f); path.lineTo(16f, 12f); path.lineTo(12f, 16f)
                }
                ToolGlyph.CHEVRON -> { path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f) }
                ToolGlyph.CLOSE -> {
                    path.moveTo(6f, 6f); path.lineTo(18f, 18f); path.moveTo(18f, 6f); path.lineTo(6f, 18f)
                }
                ToolGlyph.SEARCH -> {
                    drawCircle(color, 6.5f, Offset(10f, 10f), style = stroke)
                    path.moveTo(15f, 15f); path.lineTo(21f, 21f)
                }
                ToolGlyph.WEBSITES -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
                    drawOval(color, Offset(8f, 3f), Size(8f, 18f), style = stroke)
                    path.moveTo(3f, 12f); path.lineTo(21f, 12f)
                }
                ToolGlyph.SEND -> {
                    path.moveTo(12f, 20f); path.lineTo(12f, 4f)
                    path.moveTo(5f, 11f); path.lineTo(12f, 4f); path.lineTo(19f, 11f)
                }
                ToolGlyph.STOP -> drawRoundRect(color, Offset(6f, 6f), Size(12f, 12f), CornerRadius(2f))
                ToolGlyph.SPEAKER, ToolGlyph.MUTED -> {
                    path.moveTo(3f, 9f); path.lineTo(7f, 9f); path.lineTo(12f, 5f)
                    path.lineTo(12f, 19f); path.lineTo(7f, 15f); path.lineTo(3f, 15f); path.close()
                    if (glyph == ToolGlyph.SPEAKER) {
                        drawArc(color, -55f, 110f, false, Offset(10f, 6f), Size(10f, 12f), style = stroke)
                    } else {
                        path.moveTo(17f, 9f); path.lineTo(22f, 15f)
                        path.moveTo(22f, 9f); path.lineTo(17f, 15f)
                    }
                }
            }
            drawPath(path, color, style = stroke)
        }
    }
}
