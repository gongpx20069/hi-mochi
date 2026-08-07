package com.example.mochi_pet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MochiColorScheme = darkColorScheme(
    primary = Color(0xFFFFB7A5),
    onPrimary = Color(0xFF3B1810),
    secondary = Color(0xFFD6C2FF),
    onSecondary = Color(0xFF24143D),
    background = Color(0xFF0E0D12),
    onBackground = Color(0xFFF7F1F2),
    surface = Color(0xFF1A1820),
    onSurface = Color(0xFFF7F1F2),
    surfaceVariant = Color(0xFF27242E),
    onSurfaceVariant = Color(0xFFD4CDD6),
    outline = Color(0xFF57515F),
    error = Color(0xFFFF8A8A),
)

@Composable
fun MochiTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MochiColorScheme,
        content = content,
    )
}
