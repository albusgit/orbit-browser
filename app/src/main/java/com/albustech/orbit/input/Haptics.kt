package com.albustech.orbit.input

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/** The bezel's tactile tick. Rate-limited so a fast spin buzzes rather than rattles. */
class Haptics(private val view: View) {

    private var lastTickMs = 0L

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTickMs < MIN_INTERVAL_MS) return
        lastTickMs = now
        val constant =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        view.performHapticFeedback(constant)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 25L
    }
}
