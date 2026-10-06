package com.albustech.orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.albustech.orbit.browser.Domains

/**
 * Site avatars: the site's first letter on a dark tint picked from its host. No favicon is
 * fetched, so result cards and the launcher cost no extra requests over Bluetooth.
 */
object Avatars {
    /** (background, letter) pairs: dark tints for AMOLED, light letters for contrast. */
    private val PALETTE = listOf(
        Color(0xFF1E3A2B) to Color(0xFFA8E6B6),
        Color(0xFF4A1F1F) to Color(0xFFFFB4AB),
        Color(0xFF3D3014) to Color(0xFFFFD07A),
        Color(0xFF1C2D4A) to Color(0xFFA9C7FF),
        Color(0xFF33224A) to Color(0xFFD9BAFF),
        Color(0xFF123B3A) to Color(0xFF8FE3DB),
        Color(0xFF2E2F31) to Color(0xFFE3E3E3),
    )

    fun colorsFor(host: String): Pair<Color, Color> =
        PALETTE[(host.hashCode() and Int.MAX_VALUE) % PALETTE.size]

    /**
     * The site's own name: the registrable domain's first label.
     * "news.ycombinator.com" → "ycombinator", "en.wikipedia.org" → "wikipedia", "bbc.co.uk" → "bbc".
     */
    fun siteName(host: String): String = Domains.registrable(host).substringBefore('.')

    /** "news.ycombinator.com" → "Y", "bbc.co.uk" → "B", "oceanservice.noaa.gov" → "N". */
    fun letterFor(host: String): String =
        siteName(host).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
}

@Composable
fun SiteAvatar(host: String, size: Dp, modifier: Modifier = Modifier) {
    val (bg, fg) = Avatars.colorsFor(host)
    Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(Avatars.letterFor(host), color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.46f).sp)
    }
}

/** Avatar size inside a 48dp launcher satellite. */
val SATELLITE_SIZE = 48.dp
