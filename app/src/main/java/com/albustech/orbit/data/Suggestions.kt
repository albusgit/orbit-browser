package com.albustech.orbit.data

import java.net.URI

/** One row in the URL-entry suggestion list. */
data class Suggestion(
    val kind: Kind,
    val title: String,
    val url: String,
) {
    enum class Kind { GO, SEARCH, BOOKMARK, HISTORY }
}

/** Pure helpers for site keys and suggestion ranking (unit-tested on the JVM). */
object Suggestions {

    /** Host without "www.", lower-cased; null for non-web URLs. Per-site settings use this key. */
    fun siteKey(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = try {
            URI(url.substringBefore('#')).host
        } catch (_: Exception) {
            null
        } ?: return null
        return host.lowercase().removePrefix("www.")
    }

    /** Escapes LIKE wildcards; the DAO queries use ESCAPE '\'. */
    fun likeEscape(q: String): String =
        q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** URL without scheme, "www." and trailing slash, for display and de-duplication. */
    fun displayUrl(url: String): String =
        url.substringAfter("://").removePrefix("www.").trimEnd('/')

    /**
     * Orders matches: entries whose host starts with the query first (that's what people type),
     * then bookmarks, then history; duplicates by display URL removed.
     */
    fun rank(
        query: String,
        bookmarks: List<Pair<String, String>>,
        history: List<Pair<String, String?>>,
        limit: Int = 6,
    ): List<Suggestion> {
        val q = query.trim().lowercase()
        val all = bookmarks.map { Suggestion(Suggestion.Kind.BOOKMARK, it.second.ifBlank { displayUrl(it.first) }, it.first) } +
            history.map { Suggestion(Suggestion.Kind.HISTORY, it.second?.ifBlank { null } ?: displayUrl(it.first), it.first) }
        val seen = HashSet<String>()
        return all
            .filter { seen.add(displayUrl(it.url).lowercase()) }
            .sortedWith(
                compareByDescending<Suggestion> { displayUrl(it.url).lowercase().startsWith(q) }
                    .thenBy { it.kind != Suggestion.Kind.BOOKMARK },
            )
            .take(limit)
    }
}
