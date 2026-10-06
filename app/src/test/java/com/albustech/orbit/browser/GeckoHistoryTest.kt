package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeckoHistoryTest {

    private val readerPrefix = "moz-extension://x/reader.html#u="
    private fun docOf(u: String?) = if (u != null && u.startsWith(readerPrefix)) u.removePrefix(readerPrefix) else u
    private fun same(a: String?, b: String?) = a != null && b != null && a.substringBefore('#') == b.substringBefore('#')
    private fun back(entries: List<String?>, current: Int, reading: String?) =
        GeckoHistory.backSteps(entries, current, reading, ::docOf, ::same)

    @Test
    fun `plain pages go back one`() {
        assertEquals(-1, back(listOf("https://a", "https://b"), 1, null))
        assertNull(back(listOf("https://a"), 0, null))
    }

    @Test
    fun `reader skips its own article behind it`() {
        val entries = listOf("https://news/list", "https://news/story", "https://news/story#p2", readerPrefix + "https://news/story")
        assertEquals(-3, back(entries, 3, "https://news/story"))
    }

    @Test
    fun `blank tab entries are skipped and the start is the start`() {
        assertNull(back(listOf("about:blank", "https://a"), 1, null))
        assertEquals(-2, back(listOf("https://a", "about:blank", "https://b"), 2, null))
        assertNull(back(listOf("https://story", readerPrefix + "https://story"), 1, "https://story"))
    }

    @Test
    fun `out of range is no history`() {
        assertNull(back(emptyList(), 0, null))
        assertNull(back(listOf("https://a"), 3, null))
    }
}
