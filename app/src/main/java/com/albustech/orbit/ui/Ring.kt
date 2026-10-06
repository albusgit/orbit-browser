package com.albustech.orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import com.albustech.orbit.input.DetentAccumulator
import com.albustech.orbit.input.Haptics
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Geometry of [count] wedges on an annulus. Angles are degrees in screen space (0° = 3 o'clock,
 * clockwise); wedge 0 is centred on [startDeg] (-90° = top). Pure math, unit-tested.
 */
class RingGeometry(
    val count: Int,
    val outer: Float,
    val inner: Float,
    val startDeg: Float = -90f,
    val gapDeg: Float = 2f,
) {
    val sweep: Float = 360f / count

    fun centerDeg(i: Int): Float = startDeg + i * sweep

    /** Wedge index under a point given relative to the centre, or null outside the band. */
    fun indexAt(dx: Float, dy: Float): Int? {
        val r = hypot(dx, dy)
        if (r < inner || r > outer) return null
        val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val rel = ((deg - startDeg + sweep / 2f) % 360f + 360f) % 360f
        return (rel / sweep).toInt().coerceIn(0, count - 1)
    }

    fun inCenter(dx: Float, dy: Float): Boolean = hypot(dx, dy) < inner

    /** Arc length of a wedge at its middle radius, gaps excluded: the touch target's width. */
    val midArc: Float get() = ((inner + outer) / 2f) * ((sweep - gapDeg) * PI.toFloat() / 180f)

    /** Radial depth of a wedge: the touch target's height. */
    val depth: Float get() = outer - inner

    /** Point at the middle of wedge [i], relative to the centre. */
    fun midPoint(i: Int): Pair<Float, Float> {
        val a = Math.toRadians(centerDeg(i).toDouble())
        val r = (inner + outer) / 2f
        return (r * cos(a)).toFloat() to (r * sin(a)).toFloat()
    }

    companion object {
        /** Gap between the outer edge and the rim. */
        const val RIM_GAP_DP = 4f

        /** Inner hole radius as a fraction of the screen radius (the design's centre disc). */
        const val HOLE = 0.44f

        fun forScreen(count: Int, radius: Float, density: Float, startDeg: Float = -90f): RingGeometry =
            RingGeometry(count, radius - RIM_GAP_DP * density, radius * HOLE, startDeg)
    }
}

data class RingItem(
    @DrawableRes val icon: Int,
    val label: String,
    val enabled: Boolean = true,
    val onSelect: () -> Unit,
)

/**
 * A round-native menu: [items] as wedges on a ring. The bezel moves the highlight one wedge per
 * click (with a tick), the centre names the highlighted action, and a tap anywhere in the centre
 * confirms it. Wedges can also be tapped directly. Disabled actions are dimmed, never hidden,
 * so positions don't shift. Swipe right (or Back) dismisses.
 */
@Composable
fun RingMenu(
    geometry: RoundGeometry,
    items: List<RingItem>,
    subtitle: String?,
    hint: String,
    title: String? = null,
    onDismiss: () -> Unit,
    startDeg: Float = -90f,
    initial: Int = 0,
) {
    val density = LocalDensity.current.density
    val ring = remember(items.size, geometry, startDeg) {
        RingGeometry.forScreen(items.size, geometry.radiusPx, density, startDeg)
    }
    var highlighted by remember(items.size) { mutableIntStateOf(initial.coerceIn(0, items.lastIndex)) }
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    val accumulator = remember(view) {
        DetentAccumulator.forScrollFactor(android.view.ViewConfiguration.get(view.context).scaledVerticalScrollFactor)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val colors = MaterialTheme.colorScheme
    val wedge = colors.surfaceContainer
    val accent = colors.primary

    fun step(n: Int) {
        if (items.none { it.enabled }) return
        var i = highlighted
        repeat(kotlin.math.abs(n)) {
            do {
                i = (i + (if (n > 0) 1 else -1) + items.size) % items.size
            } while (!items[i].enabled)
        }
        if (i != highlighted) {
            highlighted = i
            haptics.tick()
        }
    }

    fun confirm(i: Int) {
        val item = items.getOrNull(i) ?: return
        if (!item.enabled) return
        haptics.confirm()
        item.onSelect()
    }

    SwipeToDismissBox(onDismissed = onDismiss, backgroundScrimColor = Color.Transparent) { isBackground ->
        if (isBackground) return@SwipeToDismissBox
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent {
                    val n = accumulator.add(it.verticalScrollPixels)
                    if (n != 0) step(n)
                    true
                }
                .focusRequester(focus)
                .focusable()
                .pointerInput(ring) {
                    detectTapGestures { p ->
                        val dx = p.x - geometry.centerXPx
                        val dy = p.y - geometry.centerYPx
                        when {
                            ring.inCenter(dx, dy) -> confirm(highlighted)
                            else -> ring.indexAt(dx, dy)?.let { i ->
                                highlighted = i
                                confirm(i)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(geometry.centerXPx, geometry.centerYPx)
                items.forEachIndexed { i, item ->
                    val color = when {
                        i == highlighted -> accent
                        else -> wedge
                    }
                    drawPath(wedgePath(c, ring, i), color.copy(alpha = if (item.enabled) 1f else 0.5f))
                }
            }
            items.forEachIndexed { i, item ->
                val (x, y) = ring.midPoint(i)
                val tint = when {
                    !item.enabled -> colors.onSurface.copy(alpha = 0.3f)
                    i == highlighted -> colors.onPrimary
                    else -> accent
                }
                Icon(
                    painterResource(item.icon),
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier
                        .offset(x = (x / density).dp, y = (y / density).dp)
                        .size(26.dp),
                )
            }
            Column(
                Modifier.size((ring.inner * 2f / density * 0.92f).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                // Ring menus name the action; link wedges name the link and show the action below.
                val action = items.getOrNull(highlighted)?.label.orEmpty()
                Text(
                    title ?: action,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (title != null) action else hint,
                    style = if (title != null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    overflow = TextOverflow.Ellipsis,
                    color = accent,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** An annular sector for wedge [i], with half the gap trimmed from each side. */
private fun wedgePath(c: Offset, ring: RingGeometry, i: Int): Path {
    val half = ring.sweep / 2f - ring.gapDeg / 2f
    val start = ring.centerDeg(i) - half
    val sweep = half * 2f
    return Path().apply {
        arcTo(Rect(c, ring.outer), start, sweep, forceMoveTo = true)
        arcTo(Rect(c, ring.inner), start + sweep, -sweep, forceMoveTo = false)
        close()
    }
}
