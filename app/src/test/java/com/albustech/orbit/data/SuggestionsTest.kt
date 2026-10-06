package com.albustech.orbit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestionsTest {

    @Test
    fun `site keys`() {
        assertEquals("example.com", Suggestions.siteKey("https://www.Example.com/a?b#c"))
        assertEquals("news.ycombinator.com", Suggestions.siteKey("https://news.ycombinator.com/item?id=1"))
        assertNull(Suggestions.siteKey("about:blank"))
        assertNull(Suggestions.siteKey(null))
        assertNull(Suggestions.siteKey("not a url"))
    }

    @Test
    fun `like escaping`() {
        assertEquals("50\\% off\\_now\\\\", Suggestions.likeEscape("50% off_now\\"))
    }

    @Test
    fun `ranking puts host-prefix matches first and dedupes`() {
        val r = Suggestions.rank(
            "wiki",
            bookmarks = listOf("https://example.org/wiki-guide" to "Guide"),
            history = listOf(
                "https://en.wikipedia.org/wiki/Watch" to "Watch - Wikipedia",
                "https://wikipedia.org/" to "Wikipedia",
                "https://www.wikipedia.org" to "Wikipedia again",
            ),
        )
        assertEquals("https://wikipedia.org/", r[0].url)
        assertEquals(3, r.size)
        assertEquals(Suggestion.Kind.BOOKMARK, r[1].kind)
    }
}
