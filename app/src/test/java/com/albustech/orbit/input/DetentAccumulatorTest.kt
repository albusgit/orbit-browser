package com.albustech.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Test

class DetentAccumulatorTest {

    @Test
    fun `one hardware click is one detent`() {
        val acc = DetentAccumulator.forScrollFactor(64f)
        assertEquals(1, acc.add(64f))
        assertEquals(-1, acc.add(-64f))
        assertEquals(1, acc.add(64f))
    }

    @Test
    fun `small deltas accumulate`() {
        val acc = DetentAccumulator(10f)
        assertEquals(0, acc.add(4f))
        assertEquals(0, acc.add(4f))
        assertEquals(1, acc.add(4f))
        assertEquals(0, acc.add(4f)) // 2 + 4 = 6
        assertEquals(1, acc.add(4f))
    }

    @Test
    fun `large deltas give several detents`() {
        val acc = DetentAccumulator(10f)
        assertEquals(3, acc.add(35f))
        assertEquals(0, acc.add(4f)) // 5 + 4 = 9
        assertEquals(1, acc.add(1f))
    }

    @Test
    fun `reversing drops the leftover`() {
        val acc = DetentAccumulator(10f)
        assertEquals(0, acc.add(9f))
        assertEquals(-1, acc.add(-10f))
    }

    @Test
    fun `zero and non-finite deltas are ignored`() {
        val acc = DetentAccumulator(10f)
        assertEquals(0, acc.add(0f))
        assertEquals(0, acc.add(Float.NaN))
        assertEquals(1, acc.add(10f))
    }
}
