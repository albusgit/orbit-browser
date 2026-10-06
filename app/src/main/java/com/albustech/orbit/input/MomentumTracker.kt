package com.albustech.orbit.input

/**
 * Detects a fast spin of the bezel, which should fling the page instead of stepping it.
 *
 * Remembers the last [window] detent times in one direction. When they arrive faster than
 * [fastIntervalMs] each on average, [record] returns the spin speed in detents per second.
 */
class MomentumTracker(
    private val window: Int = 4,
    private val fastIntervalMs: Long = 55,
) {
    private val times = LongArray(window)
    private var count = 0
    private var direction = 0

    /** Records one detent in [dir] (+1/−1) at [timeMs]. Returns detents/s if spinning fast, else null. */
    fun record(dir: Int, timeMs: Long): Float? {
        if (dir != direction) {
            direction = dir
            count = 0
        }
        // Shift left and append; the window is tiny.
        if (count == window) {
            System.arraycopy(times, 1, times, 0, window - 1)
            times[window - 1] = timeMs
        } else {
            times[count++] = timeMs
        }
        if (count < window) return null
        val span = times[window - 1] - times[0]
        val intervals = window - 1
        if (span <= 0 || span > fastIntervalMs * intervals) return null
        return intervals * 1000f / span
    }

    fun reset() {
        count = 0
        direction = 0
    }
}
