package com.albustech.orbit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.angularSize
import androidx.wear.compose.foundation.background
import androidx.wear.compose.foundation.curvedBox
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.radialSize
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import com.albustech.orbit.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Each bezel-mode segment spans this many degrees of the bottom edge. */
private const val SEGMENT_DEG = 36f
private const val SEGMENT_GAP_DEG = 2f
private val BAND = 30.dp

/**
 * The bezel's mode, visible with the chrome (design 2b/3b): curved segments along the bottom
 * edge, tappable. This is how modes switch: a tap on the page brings the chrome back.
 */
@Composable
fun BoxScope.BezelModeArc(
    geometry: RoundGeometry,
    labels: List<String>,
    selected: Int,
    visible: Boolean,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(Modifier.fillMaxSize()) {
            CurvedLayout(
                modifier = Modifier.fillMaxSize(),
                anchor = 90f,
                angularDirection = CurvedDirection.Angular.CounterClockwise,
            ) {
                curvedRow {
                    labels.forEachIndexed { i, label ->
                        val on = i == selected
                        curvedBox(
                            modifier = CurvedModifier
                                .angularSize(SEGMENT_DEG)
                                .radialSize(BAND)
                                .background(if (on) colors.primary else colors.surfaceContainer),
                        ) {
                            curvedText(
                                label,
                                color = if (on) colors.onPrimary else colors.onSurface,
                                fontSize = 14.sp,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (i < labels.lastIndex) curvedBox(modifier = CurvedModifier.angularSize(SEGMENT_GAP_DEG)) {}
                    }
                }
            }
            // One touch target per segment, centred on it, so the page keeps the rest of the
            // screen. 90° is straight down; segments run right-to-left in reading order because
            // the text is laid out counter-clockwise.
            val select by rememberUpdatedState(onSelect)
            val r = geometry.radiusPx - BAND.value * geometry.density / 2f
            val sidePx = 2f * r * sin(Math.toRadians(SEGMENT_DEG / 2.0)).toFloat()
            val total = labels.size * SEGMENT_DEG + (labels.size - 1) * SEGMENT_GAP_DEG
            labels.indices.forEach { i ->
                val deg = (90f + total / 2f) - (i * (SEGMENT_DEG + SEGMENT_GAP_DEG) + SEGMENT_DEG / 2f)
                val rad = Math.toRadians(deg.toDouble())
                val x = geometry.centerXPx + r * cos(rad).toFloat() - sidePx / 2f
                val y = geometry.centerYPx + r * sin(rad).toFloat() - sidePx / 2f
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size((sidePx / geometry.density).dp)
                        .pointerInput(i) { detectTapGestures { select(i) } },
                )
            }
        }
    }
}

/**
 * Link mode (design 2c): a counter curved along the top and one explicit "Open link" button.
 * Taps elsewhere only bring back the chrome, so links can't open by accident.
 */
@Composable
fun BoxScope.LinkModeControls(
    geometry: RoundGeometry,
    counter: String?,
    hasLink: Boolean,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
) {
    if (counter != null) TopCurvedText(counter, MaterialTheme.colorScheme.primary)
    val widthDp = (geometry.diameterDp * 0.76f)
    Button(
        onClick = onOpen,
        onLongClick = onOptions,
        enabled = hasLink,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = (geometry.diameterDp / 2f * 0.36f).dp)
            .width(widthDp.dp)
            .padding(horizontal = 2.dp),
        icon = { Icon(painterResource(R.drawable.ic_forward), contentDescription = null) },
        secondaryLabel = { Text(androidx.compose.ui.res.stringResource(R.string.hold_for_options), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(androidx.compose.ui.res.stringResource(R.string.open_link), maxLines = 1) },
    )
}

/** Transparent layer that turns any tap into "show the chrome" and a hold into the ring. */
@Composable
fun TapToShowChrome(onTap: () -> Unit, onHold: () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onLongPress = { onHold() },
            )
        },
    )
}
