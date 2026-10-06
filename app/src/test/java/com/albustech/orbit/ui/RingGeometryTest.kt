package com.albustech.orbit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingGeometryTest {

    private val ring8 = RingGeometry(count = 8, outer = 100f, inner = 44f)

    @Test
    fun `wedge 0 is at the top and indices go clockwise`() {
        assertEquals(0, ring8.indexAt(0f, -70f)) // top
        assertEquals(2, ring8.indexAt(70f, 0f)) // right
        assertEquals(4, ring8.indexAt(0f, 70f)) // bottom
        assertEquals(6, ring8.indexAt(-70f, 0f)) // left
        assertEquals(1, ring8.indexAt(50f, -50f)) // top-right
        assertEquals(7, ring8.indexAt(-50f, -50f)) // top-left
    }

    @Test
    fun `centre and outside are not wedges`() {
        assertNull(ring8.indexAt(0f, 10f))
        assertTrue(ring8.inCenter(0f, 10f))
        assertNull(ring8.indexAt(0f, 120f))
    }

    @Test
    fun `quadrant wedges for the link menu`() {
        val q = RingGeometry(count = 4, outer = 100f, inner = 44f)
        assertEquals(0, q.indexAt(0f, -70f))
        assertEquals(1, q.indexAt(70f, 0f))
        assertEquals(2, q.indexAt(0f, 70f))
        assertEquals(3, q.indexAt(-70f, 0f))
    }

    /** Every ring target is at least 48dp wide and deep on both Watch6 sizes at any likely density. */
    @Test
    fun `targets are at least 48dp`() {
        for (px in listOf(432, 480)) {
            for (density in listOf(1.75f, 2.0f, 2.25f)) {
                for (count in listOf(4, 8)) {
                    val g = RoundGeometry(px, px, density)
                    val r = RingGeometry.forScreen(count, g.radiusPx, density)
                    val arcDp = r.midArc / density
                    val depthDp = r.depth / density
                    assertTrue("$px@$density x$count arc $arcDp", arcDp >= 48f)
                    assertTrue("$px@$density x$count depth $depthDp", depthDp >= 48f)
                }
            }
        }
    }
}

class AvatarTest {
    @org.junit.Test
    fun `letters come from the site's own name`() {
        org.junit.Assert.assertEquals("Y", Avatars.letterFor("news.ycombinator.com"))
        org.junit.Assert.assertEquals("W", Avatars.letterFor("en.wikipedia.org"))
        org.junit.Assert.assertEquals("B", Avatars.letterFor("bbc.co.uk"))
        org.junit.Assert.assertEquals("B", Avatars.letterFor("www.bbc.co.uk"))
        org.junit.Assert.assertEquals("N", Avatars.letterFor("oceanservice.noaa.gov"))
        org.junit.Assert.assertEquals("A", Avatars.letterFor("abc.net.au"))
        org.junit.Assert.assertEquals("L", Avatars.letterFor("localhost"))
    }
}
