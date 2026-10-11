package com.albustech.mikraot

import kotlin.math.sign

/**
 * Turns rotary scroll pixels into whole bezel clicks (the same approach as Orbit's).
 * The Galaxy Watch bezel sends one event per click; the emulator and crowns send small deltas.
 * Reversing direction drops the leftover, so a turn back responds on the first click.
 */
class DetentAccumulator(private val pxPerDetent: Float) {

    private var pending = 0f

    /** Adds [deltaPx] and returns the whole clicks completed (positive = clockwise). */
    fun add(deltaPx: Float): Int {
        if (deltaPx == 0f || !deltaPx.isFinite() || pxPerDetent <= 0f) return 0
        if (pending != 0f && sign(pending) != sign(deltaPx)) pending = 0f
        pending += deltaPx
        val detents = (pending / pxPerDetent).toInt()
        pending -= detents * pxPerDetent
        return detents
    }
}
