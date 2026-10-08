package com.albustech.orbit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RoundGeometryTest {

    @Test
    fun `47mm screen square and inset`() {
        val g = RoundGeometry(480, 480, 2f)
        assertEquals(480, g.diameterPx)
        assertEquals(339.41f, g.squarePx, 0.01f)
        assertEquals(70.29f, g.insetPx, 0.01f)
        assertEquals(240f, g.diameterCss, 0.001f)
        assertEquals(169.71f, g.squareCss, 0.01f)
    }

    @Test
    fun `43mm screen square and inset`() {
        val g = RoundGeometry(432, 432, 2f)
        assertEquals(305.47f, g.squarePx, 0.01f)
        assertEquals(63.26f, g.insetPx, 0.01f)
    }

    @Test
    fun `square corners touch the circle`() {
        for (d in listOf(432, 480)) {
            val g = RoundGeometry(d, d, 1.875f)
            val cornerDistance = hypot(g.squarePx / 2f, g.squarePx / 2f)
            assertEquals(g.radiusPx, cornerDistance, 0.01f)
            assertEquals(d.toFloat(), g.squarePx + 2 * g.insetPx, 0.01f)
        }
    }

    @Test
    fun `non-square window uses the shorter side`() {
        val g = RoundGeometry(480, 500, 2f)
        assertEquals(480, g.diameterPx)
        assertEquals(250f, g.centerYPx)
    }

    @Test
    fun `circle hit test`() {
        val g = RoundGeometry(480, 480, 2f)
        assertTrue(g.isInsideCircle(240f, 1f))
        assertFalse(g.isInsideCircle(5f, 5f))
    }
}
