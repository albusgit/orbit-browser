package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlResolverTest {

    private val ddg = "https://html.duckduckgo.com/html/?q=%s"
    private fun resolve(s: String) = UrlResolver.resolve(s, ddg)

    @Test
    fun `blank input gives null`() {
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    @Test
    fun `full urls pass through`() {
        assertEquals("https://example.com/a?b=c", resolve("https://example.com/a?b=c"))
        assertEquals("http://neverssl.com", resolve("  http://neverssl.com "))
        assertEquals("HTTPS://EXAMPLE.COM", resolve("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `bare hosts get https`() {
        assertEquals("https://news.ycombinator.com", resolve("news.ycombinator.com"))
        assertEquals("https://en.wikipedia.org/wiki/Watch", resolve("en.wikipedia.org/wiki/Watch"))
        assertEquals("https://localhost:8080/x", resolve("localhost:8080/x"))
        assertEquals("https://192.168.1.10", resolve("192.168.1.10"))
    }

    @Test
    fun `spoken hosts are normalised`() {
        assertEquals("https://wikipedia.org", resolve("Wikipedia dot org"))
        assertEquals("https://news.ycombinator.com", resolve("news dot ycombinator dot com"))
    }

    @Test
    fun `everything else is a search`() {
        assertEquals("https://html.duckduckgo.com/html/?q=weather+today", resolve("weather today"))
        assertEquals("https://html.duckduckgo.com/html/?q=kotlin", resolve("kotlin"))
        assertEquals("https://html.duckduckgo.com/html/?q=a%26b%3Dc", resolve("a&b=c"))
        assertEquals("https://html.duckduckgo.com/html/?q=javascript%3Aalert%281%29", resolve("javascript:alert(1)"))
        assertEquals("https://html.duckduckgo.com/html/?q=file%3A%2F%2F%2Fetc", resolve("file:///etc"))
    }

    @Test
    fun `template without placeholder appends the query`() {
        assertEquals("https://s.example/?q=hi+there", UrlResolver.searchUrl("hi there", "https://s.example/?q="))
    }
}
