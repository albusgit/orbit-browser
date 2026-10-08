package com.albustech.orbit.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength
import kotlin.math.sign

/**
 * Bezel scrolling and zooming, and cursor clicks, for a GeckoView page, through Gecko's own async pan/zoom (APZ):
 * each detent asks for a smooth scroll of one step, and APZ chains the animations, so a click
 * during a scroll extends it. A fast spin becomes one long smooth scroll.
 *
 * Gecko doesn't report the scroll range synchronously; bridge.js does ([position], [max], in CSS
 * px), which is enough to tell when the page is already at an end.
 */
class GeckoScroller(private val panZoom: () -> PanZoomController?) {

    private val momentum = MomentumTracker()

    /** Latest scroll position and range from the page, or -1 until it reports. */
    var position: Int = 0
    var max: Int = -1

    /**
     * Scrolls by [detents] steps of [stepPx] screen pixels. Returns true if the page is already
     * at the end in that direction (nothing moved): the caller gives an edge bump.
     */
    fun onDetents(detents: Int, stepPx: Int, eventTimeMs: Long): Boolean {
        if (detents == 0) return false
        val dir = sign(detents.toFloat()).toInt()
        if (max >= 0 && ((dir > 0 && position >= max - 1) || (dir < 0 && position <= 0))) return true
        val speed = momentum.record(dir, eventTimeMs)
        val distance = if (speed != null) dir * speed * stepPx * FLING_SECONDS else (detents * stepPx).toFloat()
        panZoom()?.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(distance.toDouble()), PanZoomController.SCROLL_BEHAVIOR_SMOOTH)
        return false
    }

    fun stop() = momentum.reset()

    /** Scrolls by [dy] screen pixels at once (the cursor pushed past the edge of the circle). */
    fun scrollBy(dy: Float) {
        panZoom()?.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(dy.toDouble()), PanZoomController.SCROLL_BEHAVIOR_AUTO)
    }

    /**
     * Zooms by [factor] around the centre of [view] with a short synthetic two-finger pinch:
     * GeckoView has no zoom call, but APZ handles a pinch like any other.
     */
    fun zoom(view: View, factor: Float) {
        val cx = view.width / 2f
        val cy = view.height / 2f
        val from = view.width * PINCH_START
        val to = (from * factor).coerceIn(view.width * 0.04f, view.width * 0.9f)
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        Touches(panZoom() ?: return).apply {
            send(MotionEvent.ACTION_DOWN, cy, cx - from / 2)
            send(MotionEvent.ACTION_POINTER_DOWN or second, cy, cx - from / 2, cx + from / 2)
            for (i in 1..PINCH_STEPS) {
                val span = from + (to - from) * i / PINCH_STEPS
                send(MotionEvent.ACTION_MOVE, cy, cx - span / 2, cx + span / 2)
            }
            send(MotionEvent.ACTION_POINTER_UP or second, cy, cx - to / 2, cx + to / 2)
            send(MotionEvent.ACTION_UP, cy, cx - to / 2)
        }
    }

    /** A synthetic one-finger tap at ([x], [y]) in view pixels: Gecko clicks whatever is there. */
    fun tap(x: Float, y: Float) {
        Touches(panZoom() ?: return).apply {
            send(MotionEvent.ACTION_DOWN, y, x)
            send(MotionEvent.ACTION_UP, y, x)
        }
    }

    /** One synthetic gesture: fingers at [xs] on the row [y], a frame apart per event. */
    private class Touches(private val pzc: PanZoomController) {
        private val down = SystemClock.uptimeMillis()
        private var t = down

        fun send(action: Int, y: Float, vararg xs: Float) {
            val props = Array(xs.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(xs.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = xs[i]
                    this.y = y
                    pressure = 1f
                    size = 1f
                }
            }
            val ev = MotionEvent.obtain(down, t, action, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            pzc.onTouchEvent(ev)
            ev.recycle()
            t += FRAME_MS
        }
    }

    private companion object {
        /** A fling covers this many seconds' worth of the spin. Tune on the watch. */
        const val FLING_SECONDS = 0.45f
        const val PINCH_START = 0.3f
        const val PINCH_STEPS = 6
        const val FRAME_MS = 16L
    }
}
