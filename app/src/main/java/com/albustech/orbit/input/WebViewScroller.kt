package com.albustech.orbit.input

import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.OverScroller
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Smooth bezel scrolling for the WebView: each detent eases the page by a step, and a fast
 * spin turns into a fling that decays on its own.
 */
class WebViewScroller(private val webView: WebView) {

    private val scroller = OverScroller(webView.context, DecelerateInterpolator(1.5f))
    private val momentum = MomentumTracker()

    private val frame = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val before = webView.scrollY
            webView.scrollTo(webView.scrollX, scroller.currY)
            // WebView clamps scrollTo at the ends; stop instead of spinning on the edge.
            if (webView.scrollY == before && scroller.currY != before) {
                scroller.forceFinished(true)
                return
            }
            webView.postOnAnimation(this)
        }
    }

    /**
     * @param detents signed detent count from [DetentAccumulator].
     * @param stepPx scroll distance for one detent.
     */
    fun onDetents(detents: Int, stepPx: Int, eventTimeMs: Long) {
        if (detents == 0) return
        val dir = sign(detents.toFloat()).toInt()
        val maxY = maxScrollY()
        val speed = momentum.record(dir, eventTimeMs)

        if (speed != null) {
            val velocity = (dir * speed * stepPx * FLING_GAIN).roundToInt()
            scroller.fling(0, webView.scrollY, 0, velocity, 0, 0, 0, maxY)
        } else {
            // Chain steps: a click during an animation extends it instead of restarting from here.
            val reversing = !scroller.isFinished && sign((scroller.finalY - webView.scrollY).toFloat()).toInt() != dir
            val base = if (scroller.isFinished || reversing) webView.scrollY else scroller.finalY
            val target = (base + detents * stepPx).coerceIn(0, maxY)
            scroller.forceFinished(true)
            scroller.startScroll(0, webView.scrollY, 0, target - webView.scrollY, STEP_DURATION_MS)
        }
        webView.removeCallbacks(frame)
        webView.postOnAnimation(frame)
    }

    fun stop() {
        scroller.forceFinished(true)
        momentum.reset()
        webView.removeCallbacks(frame)
    }

    @Suppress("DEPRECATION") // WebView.getScale: no replacement that gives the content scale synchronously.
    private fun maxScrollY(): Int =
        ((webView.contentHeight * webView.scale).roundToInt() - webView.height).coerceAtLeast(0)

    private companion object {
        const val STEP_DURATION_MS = 140
        /** Fling velocity per (detent/s × step px). Tuned for the Watch6 bezel; revisit on device. */
        const val FLING_GAIN = 1.6f
    }
}
