package com.albustech.orbit.input

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
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
 * Bezel and touch input for the page.
 *
 * Bezel events normally arrive through Compose (`onRotaryScrollEvent` on the browser root,
 * see BrowserScreen). When the WebView itself holds focus (a form field was tapped) they go
 * to the view instead, so [attach] listens there too. Whichever path sees an event consumes
 * it, so it is handled once. Detents go to [onDetents]; what they do depends on the bezel mode.
 */
class BezelInput(
    private val webView: WebView,
    private val geometry: RoundGeometry,
) {
    private val scrollFactor = ViewConfiguration.get(webView.context).scaledVerticalScrollFactor
    private val accumulator = DetentAccumulator.forScrollFactor(scrollFactor)
    val haptics = Haptics(webView)

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

    /** A double tap (Zoom view fits the tapped block into the square). */
    var onDoubleTap: () -> Unit = {}

    /** A long press on a link: open the radial link menu. */
    var onLinkLongPress: (url: String, title: String?) -> Unit = { _, _ -> }

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
        webView.setOnGenericMotionListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                onRotary(-ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor, ev.eventTime)
            } else {
                false
            }
        }
        // No text selection or context menu on a watch: long presses are ours.
        webView.setOnLongClickListener { true }
        webView.isLongClickable = true

        val gestures = GestureDetector(webView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                onInteraction()
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val hit = webView.hitTestResult.type
                if (hit == WebView.HitTestResult.UNKNOWN_TYPE && geometry.isNearCenter(e.x, e.y)) onCenterTap()
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                this@BezelInput.onDoubleTap()
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                val hit = webView.hitTestResult
                when (hit.type) {
                    WebView.HitTestResult.SRC_ANCHOR_TYPE -> hit.extra?.let { onLinkLongPress(it, null) }
                    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> requestHref()
                    else -> if (geometry.isNearCenter(e.x, e.y)) onCenterLongPress()
                }
            }
        })
        webView.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            false
        }
    }

    /** For an image inside a link, the href comes asynchronously. */
    private fun requestHref() {
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val url = msg.data.getString("url") ?: return
                onLinkLongPress(url, msg.data.getString("title"))
            }
        }
        webView.requestFocusNodeHref(handler.obtainMessage())
    }

    private companion object {
        const val TAG = "OrbitBezel"
    }
}
