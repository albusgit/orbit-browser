package com.albustech.orbit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import com.albustech.orbit.R

/** Orbit's look: black, one blue accent, quiet greys, Geist. Every screen reads it from here. */
private val Accent = Color(0xFF0A84FF)

private val OrbitColors = ColorScheme(
    primary = Accent,
    primaryDim = Color(0xFF0A6FD6),
    primaryContainer = Color(0xFF0B3A6B),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFFD6E8FF),
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

private val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/** Orbit's floating chrome (top pill, mode bar, counters): dark glass that reads on any page. */
val GlassFill = Color(0xEB1C1C1E)
val GlassEdge = Color(0x2EFFFFFF)

@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OrbitColors, typography = Typography(defaultFontFamily = Geist), content = content)
}
