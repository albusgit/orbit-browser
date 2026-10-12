package com.albustech.orbit.claude.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Orbit's black and greys, with a warm accent for the chat app. */
private val Accent = Color(0xFFD97757)

private val Colors = ColorScheme(
    primary = Accent,
    primaryDim = Color(0xFFB8613F),
    primaryContainer = Color(0xFF5A2A1A),
    onPrimary = Color.Black,
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFAEAEB2),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFF2F2F7),
    tertiary = Color(0xFFFF9F0A),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    onSurface = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF38383A),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F7),
)

/** Floating controls over the page: dark glass that reads on any page. */
val GlassFill = Color(0xEB1C1C1E)
val GlassEdge = Color(0x2EFFFFFF)

@Composable
fun ClaudeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
