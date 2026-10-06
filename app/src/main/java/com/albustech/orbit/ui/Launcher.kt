package com.albustech.orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import com.albustech.orbit.input.DetentAccumulator
import com.albustech.orbit.input.Haptics
import kotlin.math.cos
import kotlin.math.sin

/** One satellite on the launcher: an icon (on a tinted or accent disc) or a site's letter avatar. */
data class LauncherSlot(
    val label: String,
    @DrawableRes val icon: Int? = null,
    val host: String? = null,
    val accent: Boolean = false,
    val onClick: () -> Unit,
)

/** Clockwise from upper left; top and bottom stay free for the clock and the label. */
private val SLOT_ANGLES = floatArrayOf(-135f, -45f, 0f, 45f, 135f, 180f)

/** Sweep of the bottom label: the gap between the satellites at 45° and 135°. */
private const val LABEL_SWEEP = 76f

/**
 * The orbit launcher (design 3a): the mic owns the centre; up to six actions orbit it. One bezel
 * click moves the highlight one slot and the highlighted name curves along the bottom edge; tap
 * a satellite (or anywhere off the mic, to confirm the highlighted one).
 */
@Composable
fun Launcher(
    geometry: RoundGeometry,
    slots: List<LauncherSlot>,
    micLabel: String,
    hint: String?,
    onSpeak: () -> Unit,
    onBezelUsed: () -> Unit,
) {
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    val accumulator = remember(view) {
        DetentAccumulator.forScrollFactor(android.view.ViewConfiguration.get(view.context).scaledVerticalScrollFactor)
    }
    var highlighted by remember(slots.size) { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val colors = MaterialTheme.colorScheme
    val radiusDp = geometry.diameterDp / 2f
    val micDp = (geometry.diameterDp * 0.34f).coerceAtLeast(64f)
    val orbitDp = radiusDp * 0.66f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent {
                val n = accumulator.add(it.verticalScrollPixels)
                if (n != 0 && slots.isNotEmpty()) {
                    val from = highlighted ?: if (n > 0) -1 else slots.size
                    highlighted = ((from + n) % slots.size + slots.size) % slots.size
                    haptics.tick()
                    onBezelUsed()
                }
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(slots) {
                detectTapGestures { highlighted?.let { slots.getOrNull(it)?.onClick?.invoke() } }
            },
        contentAlignment = Alignment.Center,
    ) {
        FilledIconButton(onClick = onSpeak, modifier = Modifier.size(micDp.dp)) {
            Icon(painterResource(com.albustech.orbit.R.drawable.ic_mic), contentDescription = micLabel, modifier = Modifier.size((micDp * 0.45f).dp))
        }
        slots.take(SLOT_ANGLES.size).forEachIndexed { i, slot ->
            val a = Math.toRadians(SLOT_ANGLES[i].toDouble())
            val selected = highlighted == i
            Box(
                Modifier
                    .offset(x = (orbitDp * cos(a)).toFloat().dp, y = (orbitDp * sin(a)).toFloat().dp)
                    .size(SATELLITE_SIZE)
                    .then(if (selected) Modifier.border(3.dp, colors.primary, CircleShape) else Modifier)
                    .padding(if (selected) 5.dp else 0.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = slot.label }
                    .clickable { slot.onClick() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    slot.host != null -> SiteAvatar(slot.host, if (selected) SATELLITE_SIZE - 10.dp else SATELLITE_SIZE)
                    slot.icon != null -> Box(
                        Modifier.fillMaxSize().background(if (slot.accent) colors.primary else colors.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(slot.icon),
                            contentDescription = null,
                            tint = if (slot.accent) colors.onPrimary else colors.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        // Kept between the two lower satellites so the label never runs under them.
        BottomCurvedText(highlighted?.let { slots.getOrNull(it)?.label } ?: hint, maxSweepAngle = LABEL_SWEEP)
    }
}
