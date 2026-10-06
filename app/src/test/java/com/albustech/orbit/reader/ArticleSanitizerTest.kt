package com.albustech.orbit.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleSanitizerTest {

    private fun clean(html: String) = ArticleSanitizer.clean(html, "https://example.com/news/story")

    @Test
    fun `scripts handlers and active content go`() {
        val out = clean(
            """<p onclick="x()">Hi<script>alert(1)</script></p><iframe src="https://ads"></iframe>
               <form action="/steal"><input name=p></form><base href="https://evil/"><style>p{}</style>
               <a href="javascript:alert(1)">bad</a><svg><script>y()</script></svg>""",
        )
        listOf("script", "onclick", "iframe", "form", "input", "<base", "<style", "javascript:", "<svg").forEach {
            assertFalse("$it survived: $out", out.contains(it, ignoreCase = true))
        }
        assertTrue(out.contains("<p>Hi</p>"))
        assertTrue(out.contains(">bad</a>"))
    }

    @Test
    fun `article markup stays`() {
        val out = clean(
            """<h2 lang="fr">Titre</h2><figure><img src="/a.jpg" alt="A"><figcaption>Cap</figcaption></figure>
               <ol start="3"><li>x</li></ol><table><tr><td colspan="2">c</td></tr></table><pre><code>k</code></pre><hr>""",
        )
        assertTrue(out.contains("<h2 lang=\"fr\">Titre</h2>"))
        assertTrue(out.contains("<figcaption>Cap</figcaption>"))
        assertTrue(out.contains("src=\"https://example.com/a.jpg\""))
        assertTrue(out.contains("loading=\"lazy\""))
        assertTrue(out.contains("<ol start=\"3\">"))
        assertTrue(out.contains("colspan=\"2\""))
        assertTrue(out.contains("<pre><code>k</code></pre>"))
        assertTrue(out.contains("<hr>"))
    }

    @Test
    fun `links resolve and data urls are images only`() {
        val out = clean(
            """<a href="../other">o</a><img src="data:image/png;base64,AAAA"><img src="data:text/html,<b>x</b>">""",
        )
        assertTrue(out.contains("href=\"https://example.com/other\""))
        assertTrue(out.contains("rel=\"noreferrer\""))
        assertTrue(out.contains("data:image/png;base64,AAAA"))
        assertFalse(out.contains("data:text/html"))
    }
}
