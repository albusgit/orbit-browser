package com.albustech.orbit.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

/** Fraction of the radius where the rim fade begins. */
private const val VIGNETTE_START = 0.86f

/** Opacity of the fade at the rim. */
private const val VIGNETTE_ALPHA = 0.9f

/**
 * Drawn over the page: a radial vignette so content meeting the rim fades out on purpose,
 * and a thin loading ring around the whole edge while [loadingProgress] (0..1) is non-null.
 */
@Composable
fun EdgeOverlay(
    geometry: RoundGeometry,
    loadingProgress: Float?,
    modifier: Modifier = Modifier,
) {
    val ringColor = MaterialTheme.colorScheme.primary
    val progress by animateFloatAsState(targetValue = loadingProgress ?: 1f, label = "loadRing")

    Canvas(modifier.fillMaxSize()) {
        val center = Offset(geometry.centerXPx, geometry.centerYPx)
        val radius = geometry.radiusPx

        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                VIGNETTE_START to Color.Transparent,
                1f to Color.Black.copy(alpha = VIGNETTE_ALPHA),
                center = center,
                radius = radius,
            ),
        )

        if (loadingProgress != null) {
            val stroke = 3.dp.toPx()
            val r = radius - stroke / 2f
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2f, r * 2f)
            drawArc(ringColor.copy(alpha = 0.2f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(
                ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0.02f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}
