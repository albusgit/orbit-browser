package com.albustech.orbit.browser.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CosmeticsTest {

    // The shape compile.mjs writes: generic = key, excluded sites, selector;
    // specific/exceptions = sites, selector.
    private val cosmetics = Cosmetics.parse(
        """
        ! header
        #generic
        	quiet.example	div[id^="div-gpt-ad"]
        .ad-slot		.ad-slot
        .ad-slot	plain.example	.ad-slot > .label
        #sponsor		#sponsor
        #specific
        news.example	.promo-rail
        news.example|~live.news.example	.paywall-teaser
        #exceptions
        shop.example	.ad-slot
        """.trimIndent(),
    )

    @Test
    fun `site rules and unkeyed generic rules come first`() {
        val css = cosmetics.siteCss("www.news.example", 0)
        assertTrue(css, ".promo-rail{display:none!important}" in css)
        assertTrue(".paywall-teaser" in css)
        assertTrue("div[id^=\"div-gpt-ad\"]" in css)
        assertFalse(".ad-slot" in css) // keyed: only once the page reports the class
        assertFalse(".paywall-teaser" in cosmetics.siteCss("live.news.example", 0))
        assertFalse("div-gpt-ad" in cosmetics.siteCss("quiet.example", 0))
    }

    @Test
    fun `generic rules only for reported ids and classes`() {
        val css = cosmetics.genericCss("a.example", 0, ids = listOf("sponsor"), classes = listOf("ad-slot", "story"))
        assertTrue("#sponsor{" in css)
        assertTrue(".ad-slot{" in css)
        assertTrue(".ad-slot > .label{" in css)
        assertEquals("", cosmetics.genericCss("a.example", 0, emptyList(), listOf("story")))
        assertFalse(".label" in cosmetics.genericCss("plain.example", 0, emptyList(), listOf("ad-slot")))
    }

    @Test
    fun `exceptions and page flags`() {
        assertFalse(".ad-slot{" in cosmetics.genericCss("www.shop.example", 0, emptyList(), listOf("ad-slot")))
        assertEquals("", cosmetics.genericCss("a.example", Types.GENERICHIDE, listOf("sponsor"), emptyList()))
        assertFalse("div-gpt-ad" in cosmetics.siteCss("news.example", Types.GENERICHIDE))
        assertTrue(".promo-rail" in cosmetics.siteCss("news.example", Types.GENERICHIDE))
        assertEquals("", cosmetics.siteCss("news.example", Types.ELEMHIDE))
    }

    @Test
    fun `shipped EasyList cosmetic rules load`() {
        val start = System.nanoTime()
        val real = Cosmetics.parse(File("src/main/assets/filters/cosmetic.txt").readText())
        println("cosmetics: parse ${(System.nanoTime() - start) / 1_000_000}ms, ${real.size} rules")
        assertTrue(real.size > 10_000)
        assertTrue(real.genericCss("example.org", 0, emptyList(), listOf("adsbygoogle")).isNotEmpty())
    }
}
