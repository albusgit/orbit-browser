package com.albustech.orbit.input

import android.annotation.SuppressLint
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import com.albustech.orbit.BuildConfig
import com.albustech.orbit.ui.RoundGeometry
import kotlin.math.roundToInt

/**
 * Routes bezel and touch input for the page.
 *
 * Bezel events normally arrive through Compose (`onRotaryScrollEvent` on the browser root,
 * see BrowserScreen). When the WebView itself holds focus (a text field was tapped), the
 * event goes to the view instead, so [attach] also listens there. Whichever path sees the
 * event consumes it, so it is handled once.
 */
class BezelInput(
    private val webView: WebView,
    private val geometry: RoundGeometry,
) {
    private val scrollFactor = ViewConfiguration.get(webView.context).scaledVerticalScrollFactor
    private val accumulator = DetentAccumulator.forScrollFactor(scrollFactor)
    private val haptics = Haptics(webView)
    private val scroller = WebViewScroller(webView)

    /** Scroll distance per detent: a quarter of the readable square. */
    private val stepPx: Int get() = (geometry.squarePx / 4f).roundToInt().coerceAtLeast(1)

    /** Called on any page interaction (bezel or touch scroll); the UI hides its chrome. */
    var onInteraction: () -> Unit = {}

    /** Called for a plain tap near the centre of the screen (not on a link or field). */
    var onCenterTap: () -> Unit = {}

    /** While false (e.g. a menu is open) bezel events pass through to whatever else wants them. */
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
            scroller.onDetents(detents, stepPx, eventTimeMs)
            onInteraction()
        }
        return true
    }

    fun stop() {
        scroller.stop()
        accumulator.reset()
    }

    @SuppressLint("ClickableViewAccessibility") // We observe touches and never consume them.
    fun attach() {
        webView.setOnGenericMotionListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                onRotary(-ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor, ev.eventTime)
            } else {
                false
            }
        }

        val gestures = GestureDetector(webView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.stop() // a finger on the glass stops a bezel fling
                return false
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                onInteraction()
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val hit = webView.hitTestResult.type
                if (hit == WebView.HitTestResult.UNKNOWN_TYPE && geometry.isNearCenter(e.x, e.y)) {
                    onCenterTap()
                }
                return false
            }
        })
        webView.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            false
        }
    }

    private companion object {
        const val TAG = "OrbitBezel"
    }
}
