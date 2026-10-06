package com.albustech.orbit.reader

import com.albustech.orbit.data.ReaderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        content = "<p>Body with {{TITLE}} literally in it</p>",
    )

    private fun build(template: String, anchor: Anchor? = null) = ReaderTemplate.build(
        template, css = "body{}", script = "run()", article = article,
        diameterCss = 240f, squareCss = 169.7056f, insetCss = 35.1472f,
        style = ReaderStyle(fontSize = 16, lineHeight = 1.5f, serif = false),
        anchor = anchor, nonce = "abc",
    )

    @Test
    fun `placeholders are filled once and content is not re-scanned`() {
        val html = build("<html lang=\"{{LANG}}\" dir=\"{{DIR}}\"><t>{{TITLE}}</t><m>{{META}}</m>{{CONTENT}}<s n=\"{{NONCE}}\">{{SCRIPT}}</s>")
        assertTrue(html.contains("lang=\"en-GB\""))
        assertTrue(html.contains("dir=\"ltr\""))
        assertTrue(html.contains("<t>Cats &amp; &lt;Dogs&gt;</t>"))
        assertTrue(html.contains("<p>Body with {{TITLE}} literally in it</p>"))
        assertTrue(html.contains("<s n=\"abc\">run()</s>"))
        assertTrue(html.contains("<m>By Ann · Example · 6 min read</m>"))
    }

    @Test
    fun `bad lang and dir are dropped`() {
        val html = ReaderTemplate.build(
            "{{LANG}}|{{DIR}}", "", "", article.copy(lang = "en\" onload=\"x", dir = "sideways"),
            240f, 170f, 35f, ReaderStyle(), null, "n",
        )
        assertEquals("|auto", html)
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
    fun `base url carries the marker`() {
        assertEquals("https://example.com/story#orbit-reader", ReaderTemplate.baseUrl(article.url))
    }

    @Test
    fun `meta skips blanks and duplicates`() {
        assertEquals("Example · 1 min read", ReaderTemplate.meta(article.copy(byline = " ", siteName = "Example", words = 10)))
        assertFalse(ReaderTemplate.meta(article.copy(byline = "Example")).contains("Example · Example"))
    }
}
