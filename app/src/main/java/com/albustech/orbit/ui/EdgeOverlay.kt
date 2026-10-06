package com.albustech.orbit.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

/** Fraction of the radius where the rim fade begins. */
private const val VIGNETTE_START = 0.86f

/** Opacity of the fade at the rim. */
private const val VIGNETTE_ALPHA = 0.9f

/** Scroll arc: upper right, where the thumb doesn't cover it (centred on 1:30). */
private const val SCROLL_ARC_CENTER = -45f
private const val SCROLL_ARC = 50f
private const val SCROLL_THUMB = 12f

/** What the edge shows besides the vignette. */
sealed interface EdgeIndicator {
    data object None : EdgeIndicator

    /** Round Scroll / Zoom: a short arc at the upper right with a thumb at [fraction]. */
    data class Scroll(val fraction: Float) : EdgeIndicator

    /** Reader: progress around the whole rim, page [page] (0-based) of [total]. */
    data class Pages(val page: Int, val total: Int) : EdgeIndicator
}

/**
 * Drawn over the page: a radial vignette so content meeting the rim fades out on purpose,
 * a thin loading ring around the whole edge while loading, and the position indicator.
 *
 * Progress and position are read through lambdas inside the draw phase: scrolling and loading
 * redraw this one canvas without recomposing the screen.
 */
@Composable
fun EdgeOverlay(
    geometry: RoundGeometry,
    loadingProgress: () -> Float?,
    indicator: () -> EdgeIndicator,
    indicatorVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val indicatorAlpha by animateFloatAsState(if (indicatorVisible) 1f else 0.35f, label = "indicator")
    val vignette = remember(geometry) {
        Brush.radialGradient(
            0f to Color.Transparent,
            VIGNETTE_START to Color.Transparent,
            1f to Color.Black.copy(alpha = VIGNETTE_ALPHA),
            center = Offset(geometry.centerXPx, geometry.centerYPx),
            radius = geometry.radiusPx,
        )
    }

    Canvas(modifier.fillMaxSize()) {
        val center = Offset(geometry.centerXPx, geometry.centerYPx)
        val radius = geometry.radiusPx
        drawRect(vignette)

        val loading = loadingProgress()
        val thin = 2.dp.toPx()
        when (val ind = indicator()) {
            is EdgeIndicator.Scroll -> {
                val inset = 5.dp.toPx()
                val start = SCROLL_ARC_CENTER - SCROLL_ARC / 2
                arc(center, radius - inset, start, SCROLL_ARC, track.copy(alpha = 0.6f * indicatorAlpha), 3.dp.toPx())
                val thumb = start + (SCROLL_ARC - SCROLL_THUMB) * ind.fraction.coerceIn(0f, 1f)
                arc(center, radius - inset, thumb, SCROLL_THUMB, accent.copy(alpha = indicatorAlpha), 3.dp.toPx())
            }
            is EdgeIndicator.Pages -> if (loading == null && ind.total > 0) {
                val fraction = (ind.page + 1f) / ind.total
                arc(center, radius - thin, -90f, 360f, track.copy(alpha = 0.35f * indicatorAlpha), thin, round = false)
                arc(center, radius - thin, -90f, 360f * fraction, accent.copy(alpha = 0.8f * indicatorAlpha), thin)
            }
            EdgeIndicator.None -> Unit
        }

        if (loading != null) {
            val stroke = 3.dp.toPx()
            arc(center, radius - stroke / 2f, 0f, 360f, accent.copy(alpha = 0.2f), stroke, round = false)
            arc(center, radius - stroke / 2f, -90f, 360f * loading.coerceIn(0.02f, 1f), accent, stroke)
        }
    }
}

private fun DrawScope.arc(
    center: Offset,
    r: Float,
    start: Float,
    sweep: Float,
    color: Color,
    stroke: Float,
    round: Boolean = true,
) {
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(stroke, cap = if (round) StrokeCap.Round else StrokeCap.Butt),
    )
}
