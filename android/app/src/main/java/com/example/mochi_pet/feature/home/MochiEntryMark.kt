package com.example.mochi_pet.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MochiEntryMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(width = 48.dp, height = 36.dp)) {
            scale(size.width / 48f, size.height / 36f, pivot = Offset.Zero) {
                val mark = Path().apply {
                    moveTo(7f, 29f)
                    lineTo(7f, 9f)
                    quadraticTo(7f, 6f, 9f, 8f)
                    lineTo(24f, 23f)
                    lineTo(39f, 8f)
                    quadraticTo(41f, 6f, 41f, 9f)
                    lineTo(41f, 29f)
                }
                drawPath(mark, color, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            "Mochi",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
    }
}
