package com.matheus.darkui.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC7C7FF),
    onPrimary = Color(0xFF25255B),
    primaryContainer = Color(0xFF34346C),
    secondary = Color(0xFFCBC8D8),
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF111114),
    surfaceVariant = Color(0xFF1B1B20),
    onBackground = Color(0xFFF1F1F5),
    onSurface = Color(0xFFF1F1F5)
)

@Composable
fun DarkUITheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
