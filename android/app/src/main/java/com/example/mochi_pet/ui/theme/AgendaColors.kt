package com.example.mochi_pet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class AgendaKind { USER, MOCHI, JOURNAL }

@Composable
fun AgendaIcon(kind: AgendaKind) {
    val color = agendaColor(kind)
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 20
        val stroke = Stroke(1.5f * u)
        drawRoundRect(color, Offset(3 * u, 4 * u), Size(14 * u, 13 * u), CornerRadius(3 * u), style = stroke)
        when (kind) {
            AgendaKind.USER -> {
                drawLine(color, Offset(3 * u, 8 * u), Offset(17 * u, 8 * u), 1.5f * u)
                for (x in listOf(7f, 13f)) drawLine(color, Offset(x * u, 2 * u), Offset(x * u, 6 * u), 1.5f * u)
                drawCircle(color, 1.2f * u, Offset(7 * u, 12 * u))
            }
            AgendaKind.MOCHI -> {
                for (x in listOf(7f, 13f)) drawCircle(color, u, Offset(x * u, 10 * u))
                drawLine(color, Offset(8 * u, 13 * u), Offset(12 * u, 13 * u), 1.5f * u)
                drawLine(color, Offset(10 * u, u), Offset(10 * u, 4 * u), 1.5f * u)
            }
            AgendaKind.JOURNAL -> {
                drawLine(color, Offset(6 * u, 4 * u), Offset(6 * u, 17 * u), 1.5f * u)
                for (y in listOf(8f, 12f)) drawLine(color, Offset(9 * u, y * u), Offset(14 * u, y * u), 1.5f * u)
            }
        }
    }
}

@Composable
fun agendaColor(kind: AgendaKind): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (kind) {
        AgendaKind.USER -> if (dark) Color(0xFF9ABEFF) else Color(0xFF2459BD)
        AgendaKind.MOCHI -> if (dark) Color(0xFFCAB2FF) else Color(0xFF7042BA)
        AgendaKind.JOURNAL -> if (dark) Color(0xFFEAC178) else Color(0xFF885C15)
    }
}
