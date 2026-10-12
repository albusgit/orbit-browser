package com.albustech.orbit.claude.input

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * The rotating bezel. Events arrive through Compose (`onRotaryScrollEvent` on the screen's
 * root) or, while a GeckoView holds focus (a field was tapped), on that view: [attach] listens
 * there too. Whichever path sees an event consumes it, so it's handled once.
 */
class Bezel(view: View) {

    private val scrollFactor = ViewConfiguration.get(view.context).scaledVerticalScrollFactor
    private val accumulator = DetentAccumulator.forScrollFactor(scrollFactor)
    val haptics = Haptics(view)

    /** Whole detents, signed (positive = down the chat). */
    var onDetents: (Int) -> Unit = {}

    /** While false (the menu is open) bezel events go to whatever else wants them. */
    var enabled = true

    /** [deltaPx] in the Compose convention: positive scrolls forward. Returns true if consumed. */
    fun onRotary(deltaPx: Float): Boolean {
        if (!enabled) return false
        val detents = accumulator.add(deltaPx)
        if (detents != 0) {
            haptics.tick()
            onDetents(detents)
        }
        return true
    }

    fun attach(vararg views: View) {
        for (v in views) {
            v.setOnGenericMotionListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    onRotary(-ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor)
                } else {
                    false
                }
            }
        }
    }
}
