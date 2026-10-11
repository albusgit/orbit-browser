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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import kotlin.math.hypot

/**
 * Cursor mode: a cursor fixed in the middle of the screen, with the page moving under it.
 *
 * - One finger drags the page (both ways, so a zoomed-in page pans like a map) and the cursor
 *   stays centred for easy aim ([onPan], view px, finger direction).
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
    onPan: (dx: Float, dy: Float) -> Unit,
    forward: (MotionEvent) -> Unit,
) {
    val context = LocalContext.current
    val slop = remember { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    val holdMs = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(handler) { onDispose { handler.removeCallbacksAndMessages(null) } }
    val hold by rememberUpdatedState(onHold)

    val cursor = Offset(geometry.centerXPx, geometry.centerYPx)
    val gesture = remember { CursorGesture() }

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
                        onPan(dx, dy)
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
        // A soft grey disc with a thin light rim: visible on light and dark pages alike.
        val r = 11f * geometry.density
        drawCircle(Color(0xFF3C3C43).copy(alpha = 0.38f), radius = r, center = cursor)
        drawCircle(Color.White.copy(alpha = 0.85f), radius = r, center = cursor, style = Stroke(1.5f * geometry.density))
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
