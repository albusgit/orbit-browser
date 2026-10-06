package com.albustech.orbit.input

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.albustech.orbit.BuildConfig
import com.albustech.orbit.ui.RoundGeometry
import kotlin.math.roundToInt

/**
 * Bezel and touch input for the page.
 *
 * Bezel events normally arrive through Compose (`onRotaryScrollEvent` on the browser root,
 * see BrowserScreen). When the GeckoView itself holds focus (a form field was tapped) they go
 * to the view instead, so [attach] listens there too. Whichever path sees an event consumes
 * it, so it is handled once. Detents go to [onDetents]; what they do depends on the bezel mode.
 *
 * Gecko has no synchronous hit test, so taps and long presses near the centre wait a moment
 * for the page's answer: [wasInteractiveTap] (bridge.js saw the tap land on a link or control)
 * and [hadContextMenu] (Gecko reported a long press on a link). Only if neither came does the
 * centre gesture open Orbit's ring or switch the bezel mode.
 */
class BezelInput(
    private val view: View,
    private val geometry: RoundGeometry,
) {
    private val scrollFactor = ViewConfiguration.get(view.context).scaledVerticalScrollFactor
    private val accumulator = DetentAccumulator.forScrollFactor(scrollFactor)
    private val handler = Handler(Looper.getMainLooper())
    val haptics = Haptics(view)

    /** Scroll distance per detent in Round Scroll: a quarter of the readable square. */
    val stepPx: Int get() = (geometry.squarePx / 4f).roundToInt().coerceAtLeast(1)

    /** Whole detents, signed (positive = forward/down). */
    var onDetents: (detents: Int, eventTimeMs: Long) -> Unit = { _, _ -> }

    /** Any page interaction (bezel or touch scroll); the UI hides its chrome. */
    var onInteraction: () -> Unit = {}

    /** A plain tap near the centre (not on a link or field). */
    var onCenterTap: () -> Unit = {}

    /** A long press near the centre (not on a link): switch the bezel mode. */
    var onCenterLongPress: () -> Unit = {}

    /** A long press on a link (reported by Gecko): open the link wedges. */
    var onLinkLongPress: (url: String, title: String?) -> Unit = { _, _ -> }

    /** True if the page reported the last tap as landing on a link or control. */
    var wasInteractiveTap: () -> Boolean = { false }

    /** True if Gecko just reported a long press on a link. */
    var hadContextMenu: () -> Boolean = { false }

    /** While false (a menu is open) bezel events pass through to whatever else wants them. */
    var enabled: Boolean = true

    /**
     * Handles one rotary event. [deltaPx] uses the Compose convention: positive scrolls the
     * content forward (down the page). Returns true if consumed.
     */
    fun onRotary(deltaPx: Float, eventTimeMs: Long): Boolean {
        if (!enabled) return false
        val detents = accumulator.add(deltaPx)
        if (BuildConfig.DEBUG) Log.v(TAG, "rotary deltaPx=$deltaPx detents=$detents factor=$scrollFactor")
        if (detents != 0) {
            haptics.tick()
            onDetents(detents, eventTimeMs)
            onInteraction()
        }
        return true
    }

    fun reset() = accumulator.reset()

    @SuppressLint("ClickableViewAccessibility") // We observe touches and never consume them.
    fun attach() {
        view.setOnGenericMotionListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                onRotary(-ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor, ev.eventTime)
            } else {
                false
            }
        }

        val gestures = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                onInteraction()
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (geometry.isNearCenter(e.x, e.y)) {
                    handler.postDelayed({ if (!wasInteractiveTap()) onCenterTap() }, PAGE_ANSWER_MS)
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (geometry.isNearCenter(e.x, e.y)) {
                    handler.postDelayed({ if (!hadContextMenu()) onCenterLongPress() }, PAGE_ANSWER_MS)
                }
            }
        })
        view.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            false
        }
    }

    fun detach() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val TAG = "OrbitBezel"

        /** How long a centre gesture waits for the page to say it hit something. */
        const val PAGE_ANSWER_MS = 180L
    }
}
