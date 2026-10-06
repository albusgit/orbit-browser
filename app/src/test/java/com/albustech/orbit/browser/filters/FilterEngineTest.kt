package com.albustech.orbit.browser.filters

import com.albustech.orbit.browser.Domains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterEngineTest {

    // The shape compile.mjs writes: kind, party, type mask (hex), domains, pattern.
    private val engine = FilterEngine.parse(
        """
        ! header
        #hosts
        doubleclick.net
        ads.example.com
        com
        #hosts3p
        cdn-tracker.io
        #filters
        b	0	3ff		/ads/banner_
        b	0	1		||widgets.example.org/track^
        b	3	2		||pics.example^
        b	0	3ff	news.example|~sport.news.example	/sidebar-promo.
        b	0	3ff		.gif?utm_|
        a	0	3ff		||ads.example.com/consent/
        a	0	3ff	shop.example	/ads/banner_
        B	0	3ff		||hard.example^
        a	0	3ff		||hard.example^
        a	0	400		||trusted.example^
        a	0	1000		||plain.example^
        """.trimIndent(),
    )

    private fun blocked(url: String, page: String? = "page.example", type: Int = Types.UNKNOWN) =
        engine.shouldBlock(url, page, type)

    @Test
    fun `hosts match themselves and subdomains, never lookalikes or a bare TLD`() {
        assertTrue(blocked("https://doubleclick.net/x"))
        assertTrue(blocked("https://stats.g.doubleclick.net/collect?v=1"))
        assertTrue(blocked("https://ADS.Example.com/a.js"))
        assertFalse(blocked("https://notdoubleclick.net/"))
        assertFalse(blocked("https://example.com/"))
        assertFalse(blocked("https://anything.com/"))
    }

    @Test
    fun `third-party-only hosts`() {
        assertTrue(blocked("https://cdn-tracker.io/t.js", page = "news.example"))
        assertFalse(blocked("https://cdn-tracker.io/t.js", page = "www.cdn-tracker.io"))
    }

    @Test
    fun `patterns with anchors, separators and wildcards`() {
        assertTrue(blocked("https://site.example/static/ads/banner_300.png"))
        assertFalse(blocked("https://site.example/static/ads/banner-300.png"))
        assertTrue(blocked("https://widgets.example.org/track?id=1", type = Types.SCRIPT))
        assertTrue(blocked("https://widgets.example.org/track", type = Types.SCRIPT))
        assertFalse(blocked("https://widgets.example.org/tracker", type = Types.SCRIPT))
        assertFalse(blocked("https://widgets.example.org/track?id=1", type = Types.IMAGE))
        assertTrue(blocked("https://x.example/p.gif?utm_"))
        assertFalse(blocked("https://x.example/p.gif?utm_source=a"))
    }

    @Test
    fun `party and type options`() {
        assertTrue(blocked("https://pics.example/a.png", page = "news.example", type = Types.IMAGE))
        assertFalse(blocked("https://pics.example/a.png", page = "pics.example", type = Types.IMAGE))
        assertFalse(blocked("https://pics.example/a.js", page = "news.example", type = Types.SCRIPT))
    }

    @Test
    fun `domain option follows the page`() {
        assertTrue(blocked("https://cdn.example/sidebar-promo.js", page = "www.news.example"))
        assertFalse(blocked("https://cdn.example/sidebar-promo.js", page = "sport.news.example"))
        assertFalse(blocked("https://cdn.example/sidebar-promo.js", page = "other.example"))
    }

    @Test
    fun `exceptions, and important beats them`() {
        assertFalse(blocked("https://ads.example.com/consent/banner.js"))
        assertFalse(blocked("https://site.example/ads/banner_1.png", page = "shop.example"))
        assertTrue(blocked("https://hard.example/x.js"))
    }

    @Test
    fun `page-level exceptions`() {
        assertEquals(Types.DOCUMENT, engine.pageFlags("https://www.trusted.example/article"))
        assertEquals(Types.GENERICHIDE, engine.pageFlags("https://plain.example/"))
        assertEquals(0, engine.pageFlags("https://other.example/"))
    }

    @Test
    fun `request types guessed from Accept and extension`() {
        assertEquals(Types.IMAGE, Types.guess("https://a.example/x", "image/avif,image/webp,*/*"))
        assertEquals(Types.STYLESHEET, Types.guess("https://a.example/x", "text/css,*/*;q=0.1"))
        assertEquals(Types.SUBDOCUMENT, Types.guess("https://a.example/x", "text/html,application/xhtml+xml"))
        assertEquals(Types.SCRIPT, Types.guess("https://a.example/app.js?v=2", "*/*"))
        assertEquals(Types.FONT, Types.guess("https://a.example/f.woff2", null))
        assertEquals(Types.UNKNOWN, Types.guess("https://a.example/api/v1.2/items", "*/*"))
    }

    @Test
    fun `safe tokens are whole URL tokens only`() {
        assertEquals(listOf("ads"), Pattern.safeTokens("/ads/banner", anchoredStart = false, anchoredEnd = false))
        assertEquals(listOf("pics", "example"), Pattern.safeTokens("pics.example^", anchoredStart = true, anchoredEnd = false))
        assertEquals(emptyList<String>(), Pattern.safeTokens("*ad*", anchoredStart = false, anchoredEnd = false))
    }

    @Test
    fun `registrable domains`() {
        assertEquals("bbc.co.uk", Domains.registrable("news.bbc.co.uk"))
        assertEquals("example.com", Domains.registrable("a.b.example.com"))
        assertEquals("abc.net.au", Domains.registrable("www.abc.net.au"))
        assertEquals("localhost", Domains.registrable("localhost"))
        assertTrue(Domains.isWithin("a.example.com", "example.com"))
        assertFalse(Domains.isWithin("badexample.com", "example.com"))
    }

    /** The shipped lists: known trackers are blocked, ordinary page resources are not. */
    @Test
    fun `shipped EasyList and EasyPrivacy`() {
        val text = File("src/main/assets/filters/network.txt").readText()
        val start = System.nanoTime()
        val real = FilterEngine.parse(text)
        val parseMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("hosts ${real.hostCount}", real.hostCount > 50_000)
        assertTrue("filters ${real.filterCount}", real.filterCount > 5_000)

        val page = "www.bbc.co.uk"
        val trackers = listOf(
            "https://www.google-analytics.com/analytics.js" to Types.SCRIPT,
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js" to Types.SCRIPT,
            "https://www.googletagmanager.com/gtm.js?id=GTM-XXXX" to Types.SCRIPT,
            "https://connect.facebook.net/en_US/fbevents.js" to Types.SCRIPT,
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js" to Types.SCRIPT,
            "https://static.hotjar.com/c/hotjar-123.js?sv=6" to Types.SCRIPT,
            "https://bat.bing.com/bat.js" to Types.SCRIPT,
            "https://example-news.com/ads/banner_728x90.gif" to Types.IMAGE,
        )
        for ((url, type) in trackers) assertTrue(url, real.shouldBlock(url, page, type))

        val ordinary = listOf(
            "https://www.bbc.co.uk/news/articles/abc123" to Types.SUBDOCUMENT,
            "https://static.files.bbci.co.uk/core/website/assets/static/news/main.css" to Types.STYLESHEET,
            "https://ichef.bbci.co.uk/news/976/cpsprodpb/photo.jpg.webp" to Types.IMAGE,
            "https://fonts.googleapis.com/css2?family=Roboto" to Types.STYLESHEET,
            "https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92Fr1Mu4mxK.woff2" to Types.FONT,
            "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js" to Types.SCRIPT,
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Tide.jpg/320px-Tide.jpg" to Types.IMAGE,
            "https://en.wikipedia.org/w/load.php?modules=startup&only=scripts" to Types.SCRIPT,
            "https://www.google.com/search?q=tides" to Types.UNKNOWN,
            "https://html.duckduckgo.com/html/?q=tides" to Types.UNKNOWN,
        )
        for ((url, type) in ordinary) assertFalse(url, real.shouldBlock(url, page, type))

        // Matching cost per request, averaged over a mixed batch.
        val batch = (trackers + ordinary).map { it.first }
        val t0 = System.nanoTime()
        val rounds = 2000
        repeat(rounds) { for (u in batch) real.shouldBlock(u, page, Types.UNKNOWN) }
        val perRequestUs = (System.nanoTime() - t0) / 1000.0 / (rounds * batch.size)
        println("filters: parse ${parseMs}ms, ${"%.1f".format(perRequestUs)}µs per request, ${real.hostCount} hosts, ${real.filterCount} filters")
        assertTrue("per request $perRequestUs µs", perRequestUs < 200)
    }
}
