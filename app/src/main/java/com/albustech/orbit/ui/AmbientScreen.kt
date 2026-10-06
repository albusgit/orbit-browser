package com.albustech.orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText

/**
 * Ambient (always-on) screen: almost all black, dim grey text only, no fills. The page
 * underneath is untouched, so waking shows it again immediately at the same position.
 */
@Composable
fun AmbientScreen(geometry: RoundGeometry, title: String?, position: String?) {
    val dim = Color(0xFF8A8A8A)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        TimeText(backgroundColor = Color.Black)
        Column(Modifier.width(geometry.squareDp.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) {
                Text(
                    title,
                    color = dim,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (position != null) {
                Text(position, color = dim, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}
