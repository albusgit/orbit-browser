package com.albustech.orbit.ui

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The round screen as a layout surface. Built from the real window size at runtime;
 * nothing in Orbit hardcodes pixels.
 *
 * Content that must never be clipped lives in the inscribed square: side = d / √2,
 * offset from each edge by [insetPx] = (d − side) / 2.
 */
data class RoundGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
) {
    val diameterPx: Int = minOf(widthPx, heightPx)
    val radiusPx: Float = diameterPx / 2f
    val centerXPx: Float = widthPx / 2f
    val centerYPx: Float = heightPx / 2f

    val squarePx: Float = diameterPx / SQRT2
    val insetPx: Float = (diameterPx - squarePx) / 2f

    val diameterDp: Float = diameterPx / density
    val squareDp: Float = squarePx / density
    val insetDp: Float = insetPx / density

    /**
     * With `width=device-width` and textZoom 100, one CSS px in the WebView is one dp.
     * These are the values handed to round.js.
     */
    val diameterCss: Float get() = diameterDp
    val squareCss: Float get() = squareDp
    val insetCss: Float get() = insetDp

    /** True if (x, y) in px lies inside the visible circle. */
    fun isInsideCircle(x: Float, y: Float): Boolean = hypot(x - centerXPx, y - centerYPx) <= radiusPx

    companion object {
        val SQRT2: Float = sqrt(2f)
    }
}
