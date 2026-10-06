package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlocklistTest {

    private val list = Blocklist.parse(
        """
        # comment
        doubleclick.net
        Ads.Example.com   # trailing comment
        localhost
        com
        """.trimIndent(),
    )

    @Test
    fun `hosts and subdomains match`() {
        assertTrue(list.isBlocked("doubleclick.net"))
        assertTrue(list.isBlocked("stats.g.doubleclick.net"))
        assertTrue(list.isBlocked("ads.example.com"))
        assertTrue(list.isBlocked("x.ADS.example.com."))
    }

    @Test
    fun `parents and lookalikes do not`() {
        assertFalse(list.isBlocked("example.com"))
        assertFalse(list.isBlocked("notdoubleclick.net"))
        assertFalse(list.isBlocked("doubleclick.net.evil.org"))
        assertFalse(list.isBlocked(null))
        assertFalse(list.isBlocked(""))
    }

    @Test
    fun `single-label entries are ignored`() {
        assertEquals(2, list.size)
        assertFalse(list.isBlocked("example.com"))
    }

    @Test
    fun `bundled list parses and stays small`() {
        val file = listOf("src/main/assets/blocklist.txt", "app/src/main/assets/blocklist.txt").map(::File).first { it.exists() }
        val bundled = Blocklist.parse(file.readText())
        assertTrue(bundled.size in 30..200)
        assertTrue(bundled.isBlocked("www.google-analytics.com"))
        assertFalse(bundled.isBlocked("en.wikipedia.org"))
        assertFalse(bundled.isBlocked("news.ycombinator.com"))
    }
}
