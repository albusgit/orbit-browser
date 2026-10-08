package com.albustech.orbit.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import kotlin.math.hypot

/** How far the cursor moves per pixel the finger moves. */
private const val GAIN = 1.6f

/**
 * Cursor mode: the screen is a trackpad over the page.
 *
 * - One finger drags the cursor (relative, so the finger never covers what it points at). Pushed
 *   past the top or bottom of the circle, it scrolls the page by the overshoot ([onScroll]).
 * - A tap clicks under the cursor ([onClick], view px); a hold opens the ring ([onHold]).
 * - A second finger hands the whole gesture to the page ([forward]), so pinch zoom still works.
 *
 * Drawn and hit-tested over the page only; Orbit's chrome above it keeps its own touches.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CursorLayer(
    geometry: RoundGeometry,
    onClick: (x: Float, y: Float) -> Unit,
    onHold: () -> Unit,
    onMove: () -> Unit,
    onScroll: (dy: Float) -> Unit,
    forward: (MotionEvent) -> Unit,
) {
    val context = LocalContext.current
    val slop = remember { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    val holdMs = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(handler) { onDispose { handler.removeCallbacksAndMessages(null) } }
    val hold by rememberUpdatedState(onHold)

    var cursor by remember { mutableStateOf(Offset(geometry.centerXPx, geometry.centerYPx)) }
    val gesture = remember { CursorGesture() }
    val margin = 6f * geometry.density

    /** Keeps [p] inside the circle; returns the clamped point and the vertical overshoot. */
    fun clamp(p: Offset): Pair<Offset, Float> {
        val dx = p.x - geometry.centerXPx
        val dy = p.y - geometry.centerYPx
        val r = hypot(dx, dy)
        val max = geometry.radiusPx - margin
        if (r <= max) return p to 0f
        val inside = Offset(geometry.centerXPx + dx / r * max, geometry.centerYPx + dy / r * max)
        return inside to (p.y - inside.y)
    }

    Canvas(
        Modifier.fillMaxSize().pointerInteropFilter { ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture.start(ev.x, ev.y)
                    handler.postDelayed({
                        if (!gesture.moved && !gesture.forwarding) {
                            gesture.held = true
                            hold()
                        }
                    }, holdMs)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!gesture.forwarding && !gesture.held) {
                        gesture.forwarding = true
                        val down = firstFingerDown(ev)
                        forward(down)
                        down.recycle()
                    }
                    if (gesture.forwarding) forward(ev)
                }
                MotionEvent.ACTION_MOVE -> if (gesture.forwarding) {
                    forward(ev)
                } else if (!gesture.held) {
                    val dx = ev.x - gesture.lastX
                    val dy = ev.y - gesture.lastY
                    if (!gesture.moved && hypot(ev.x - gesture.downX, ev.y - gesture.downY) > slop) {
                        gesture.moved = true
                        handler.removeCallbacksAndMessages(null)
                    }
                    if (gesture.moved) {
                        val (p, over) = clamp(Offset(cursor.x + dx * GAIN, cursor.y + dy * GAIN))
                        cursor = p
                        if (over != 0f) onScroll(over)
                        onMove()
                    }
                    gesture.lastX = ev.x
                    gesture.lastY = ev.y
                }
                MotionEvent.ACTION_POINTER_UP -> if (gesture.forwarding) forward(ev)
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    when {
                        gesture.forwarding -> forward(ev)
                        !gesture.moved && !gesture.held -> onClick(cursor.x, cursor.y)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    if (gesture.forwarding) forward(ev)
                }
            }
            true
        },
    ) {
        val r = 9f * geometry.density
        drawCircle(Color.Black.copy(alpha = 0.55f), radius = r + 2f * geometry.density, center = cursor, style = Stroke(5f * geometry.density))
        drawCircle(Color.White, radius = r, center = cursor, style = Stroke(2.5f * geometry.density))
        drawCircle(Color.White, radius = 2f * geometry.density, center = cursor)
    }
}

/** One touch sequence on the cursor layer. */
private class CursorGesture {
    var downX = 0f
    var downY = 0f
    var lastX = 0f
    var lastY = 0f
    var moved = false
    var held = false
    var forwarding = false

    fun start(x: Float, y: Float) {
        downX = x
        downY = y
        lastX = x
        lastY = y
        moved = false
        held = false
        forwarding = false
    }
}

/** The page never saw this gesture's first finger go down: a DOWN for it where it is now. */
private fun firstFingerDown(ev: MotionEvent): MotionEvent {
    val props = MotionEvent.PointerProperties().also { ev.getPointerProperties(0, it) }
    val coords = MotionEvent.PointerCoords().also { ev.getPointerCoords(0, it) }
    return MotionEvent.obtain(
        ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, 1, arrayOf(props), arrayOf(coords),
        ev.metaState, ev.buttonState, 1f, 1f, ev.deviceId, 0, ev.source, ev.flags,
    )
}
