package com.albustech.orbit.reader

import com.albustech.orbit.data.ReaderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTemplateTest {

    private val article = Article(
        url = "https://example.com/story#top",
        title = "Cats & <Dogs>",
        byline = "By Ann",
        siteName = "Example",
        lang = "en-GB",
        dir = "ltr",
        words = 1320,
        content = "<p>Body with <b>markup</b></p>",
    )

    private fun payload(a: Article = article, anchor: Anchor? = null) = ReaderTemplate.payload(
        a, diameterCss = 240f, squareCss = 169.7056f, insetCss = 35.1472f,
        style = ReaderStyle(fontSize = 16, lineHeight = 1.5f, serif = false), anchor = anchor,
    )

    @Test
    fun `payload carries escaped header, untouched content and config`() {
        val p = payload(anchor = Anchor(3, 42))
        assertEquals("en-GB", p.getString("lang"))
        assertEquals("ltr", p.getString("dir"))
        assertEquals("Cats & <Dogs>", p.getString("title")) // document.title is text, not HTML
        val html = p.getString("html")
        assertTrue(html.contains("<h1 class=\"orbit-title\">Cats &amp; &lt;Dogs&gt;</h1>"))
        assertTrue(html.contains("<p class=\"orbit-meta\">By Ann · Example · 6 min read</p>"))
        assertTrue(html.endsWith("<p>Body with <b>markup</b></p>"))
        assertEquals(42, p.getJSONObject("config").getJSONObject("anchor").getInt("w"))
    }

    @Test
    fun `bad lang and dir are dropped`() {
        val p = payload(article.copy(lang = "en\" onload=\"x", dir = "sideways"))
        assertEquals("", p.getString("lang"))
        assertEquals("auto", p.getString("dir"))
    }

    @Test
    fun `config json`() {
        assertEquals(
            "{\"d\":240.000,\"sq\":169.706,\"inset\":35.147,\"style\":{\"fontSize\":16,\"lineHeight\":1.500,\"serif\":false},\"anchor\":{\"u\":3,\"w\":42}}",
            ReaderTemplate.config(240f, 169.7056f, 35.1472f, ReaderStyle(16, 1.5f, false), Anchor(3, 42)),
        )
        assertTrue(ReaderTemplate.config(240f, 1f, 1f, ReaderStyle(), null).endsWith("\"anchor\":null}"))
    }

    @Test
    fun `reader page urls round-trip the article`() {
        val base = "moz-extension://1234-abcd/"
        val page = ReaderTemplate.pageUrl(base, "https://example.com/a?b=1&c=2#x", "t9")
        assertEquals("moz-extension://1234-abcd/reader.html#u=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1%26c%3D2%23x&t=t9", page)
        assertEquals("https://example.com/a?b=1&c=2#x", ReaderTemplate.articleOf(page, base))
        assertNull(ReaderTemplate.articleOf("https://example.com/reader.html#u=x", base))
        assertNull(ReaderTemplate.articleOf("moz-extension://1234-abcd/reader.html#u=javascript%3Aalert(1)", base))
        assertNull(ReaderTemplate.articleOf(page, null))
    }

    @Test
    fun `meta skips blanks and duplicates`() {
        assertEquals("Example · 1 min read", ReaderTemplate.meta(article.copy(byline = " ", siteName = "Example", words = 10)))
        assertFalse(ReaderTemplate.meta(article.copy(byline = "Example")).contains("Example · Example"))
    }
}
