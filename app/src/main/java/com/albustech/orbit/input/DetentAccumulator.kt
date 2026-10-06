package com.albustech.orbit.input

import kotlin.math.sign

/**
 * Turns rotary scroll pixels into whole bezel detents.
 *
 * The Galaxy Watch bezel sends one event per click, while the emulator's rotary input and
 * crowns send a stream of small deltas. Both go through here. Reversing direction drops
 * the leftover, so a turn back responds on the first click.
 *
 * @param pxPerDetent pixels that make one detent. Use slightly less than the per-click delta
 *   (see [forScrollFactor]) so every hardware click registers.
 */
class DetentAccumulator(private val pxPerDetent: Float) {

    init {
        require(pxPerDetent > 0f) { "pxPerDetent must be positive" }
    }

    private var pending = 0f

    /** Adds [deltaPx] and returns the whole detents completed (signed; positive = down/forward). */
    fun add(deltaPx: Float): Int {
        if (deltaPx == 0f || !deltaPx.isFinite()) return 0
        if (pending != 0f && sign(pending) != sign(deltaPx)) pending = 0f
        pending += deltaPx
        val detents = (pending / pxPerDetent).toInt()
        pending -= detents * pxPerDetent
        return detents
    }

    fun reset() {
        pending = 0f
    }

    companion object {
        /** Margin under one click's delta, so a single click always registers. */
        private const val CLICK_MARGIN = 0.9f

        /** [scrollFactor] is ViewConfiguration.scaledVerticalScrollFactor: pixels per AXIS_SCROLL unit. */
        fun forScrollFactor(scrollFactor: Float) = DetentAccumulator(scrollFactor * CLICK_MARGIN)
    }
}
