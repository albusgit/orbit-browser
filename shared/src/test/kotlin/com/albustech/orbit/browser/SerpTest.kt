package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SerpTest {

    @Test
    fun `google web results are recognised`() {
        assertEquals(SerpPage(SearchSite.GOOGLE, "how do tides work", 0), Serp.detect("https://www.google.com/search?q=how+do+tides+work&hl=en"))
        assertEquals(SerpPage(SearchSite.GOOGLE, "tides", 20), Serp.detect("https://www.google.co.uk/search?q=tides&start=20"))
        assertEquals(SearchSite.GOOGLE, Serp.detect("https://google.com.au/search?q=x&udm=14")?.site)
    }

    @Test
    fun `google non-web tabs and other pages are not`() {
        assertNull(Serp.detect("https://www.google.com/search?q=tides&tbm=isch"))
        assertNull(Serp.detect("https://www.google.com/search?q=tides&udm=2"))
        assertNull(Serp.detect("https://www.google.com/maps?q=tides"))
        assertNull(Serp.detect("https://www.google.com/search"))
        assertNull(Serp.detect("https://notgoogle.com/search?q=x"))
        assertNull(Serp.detect("https://www.google.com/sorry/index?continue=x&q=abc"))
    }

    @Test
    fun `duckduckgo and bing`() {
        assertEquals(SerpPage(SearchSite.DUCKDUCKGO, "tides", 0), Serp.detect("https://html.duckduckgo.com/html/?q=tides"))
        assertEquals(SerpPage(SearchSite.DUCKDUCKGO, "tides", 30), Serp.detect("https://html.duckduckgo.com/html/?q=tides&s=30&dc=31"))
        assertEquals(SearchSite.DUCKDUCKGO, Serp.detect("https://lite.duckduckgo.com/lite/?q=tides")?.site)
        assertNull(Serp.detect("https://duckduckgo.com/?q=tides")) // the JS app, not the HTML list
        assertEquals(SerpPage(SearchSite.BING, "tides", 10), Serp.detect("https://www.bing.com/search?q=tides&first=11"))
    }

    @Test
    fun `redirects are unwrapped`() {
        assertEquals(
            "https://oceanservice.noaa.gov/facts/tides.html",
            Serp.decodeResultUrl("https://www.google.com/url?q=https://oceanservice.noaa.gov/facts/tides.html&sa=U&ved=abc", SearchSite.GOOGLE),
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Tide",
            Serp.decodeResultUrl("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FTide&rut=x", SearchSite.DUCKDUCKGO),
        )
        // "a1" + base64url("https://www.bbc.co.uk/tides")
        val bing = "https://www.bing.com/ck/a?!&&p=abc&u=a1aHR0cHM6Ly93d3cuYmJjLmNvLnVrL3RpZGVz&ntb=1"
        assertEquals("https://www.bbc.co.uk/tides", Serp.decodeResultUrl(bing, SearchSite.BING))
        assertEquals("https://noaa.gov/x", Serp.decodeResultUrl("https://noaa.gov/x", SearchSite.GOOGLE))
    }

    @Test
    fun `engine-internal and non-web links are dropped`() {
        assertNull(Serp.decodeResultUrl("https://www.google.com/search?q=tides&tbm=isch", SearchSite.GOOGLE))
        assertNull(Serp.decodeResultUrl("https://www.google.com/url?q=javascript:alert(1)", SearchSite.GOOGLE))
        assertNull(Serp.decodeResultUrl("javascript:alert(1)", SearchSite.GOOGLE))
        assertNull(Serp.decodeResultUrl("https://duckduckgo.com/?q=more", SearchSite.DUCKDUCKGO))
        assertNull(Serp.decodeResultUrl(null, SearchSite.BING))
    }

    @Test
    fun `next pages`() {
        val g = SerpPage(SearchSite.GOOGLE, "how do tides", 10)
        assertEquals("https://www.google.com/search?q=how+do+tides&start=20", Serp.nextUrl(g, 9))
        assertEquals("https://html.duckduckgo.com/html/?q=t&s=30&dc=31", Serp.nextUrl(SerpPage(SearchSite.DUCKDUCKGO, "t", 0), 30))
        assertEquals("https://www.bing.com/search?q=t&first=11", Serp.nextUrl(SerpPage(SearchSite.BING, "t", 0), 10))
        assertNull(Serp.nextUrl(g, 0))
        assertEquals("noaa.gov", Serp.displayHost("https://www.noaa.gov/a"))
    }
}
