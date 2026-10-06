package com.albustech.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MomentumTrackerTest {

    @Test
    fun `slow clicks never fling`() {
        val m = MomentumTracker(window = 4, fastIntervalMs = 55)
        var t = 0L
        repeat(10) { assertNull(m.record(1, t)); t += 200 }
    }

    @Test
    fun `fast spin reports speed`() {
        val m = MomentumTracker(window = 4, fastIntervalMs = 55)
        assertNull(m.record(1, 0))
        assertNull(m.record(1, 40))
        assertNull(m.record(1, 80))
        val speed = m.record(1, 120)
        assertNotNull(speed)
        assertEquals(25f, speed!!, 0.01f) // 3 intervals over 120 ms
    }

    @Test
    fun `direction change restarts the window`() {
        val m = MomentumTracker(window = 4, fastIntervalMs = 55)
        m.record(1, 0); m.record(1, 30); m.record(1, 60)
        assertNull(m.record(-1, 90))
        assertNull(m.record(-1, 120))
        assertNull(m.record(-1, 150))
        assertNotNull(m.record(-1, 180))
    }
}
