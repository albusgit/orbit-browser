package com.albustech.orbit.browser

/**
 * Registrable domains without shipping the Public Suffix List: the last two labels, or three
 * under the common two-label suffixes (co.uk, com.au, ...). Good enough for "is this request
 * third-party?" and for letter avatars; a miss only treats two sites under an unusual suffix as
 * the same party.
 */
object Domains {
    /** Second-level labels used under country codes: "co" in co.uk, "com" in com.au, ... */
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or", "go", "gv")

    /** "news.bbc.co.uk" → "bbc.co.uk", "en.wikipedia.org" → "wikipedia.org", "localhost" → "localhost". */
    fun registrable(host: String): String {
        val h = host.lowercase().trimEnd('.')
        val last = h.lastIndexOf('.')
        if (last <= 0) return h
        val second = h.lastIndexOf('.', last - 1)
        if (second < 0) return h
        val twoLevel = h.length - last - 1 == 2 && h.substring(second + 1, last) in SECOND_LEVEL
        if (!twoLevel) return h.substring(second + 1)
        val third = h.lastIndexOf('.', second - 1)
        return if (third < 0) h else h.substring(third + 1)
    }

    /** True when [host] is [domain] or one of its subdomains. */
    fun isWithin(host: String, domain: String): Boolean =
        host.length == domain.length && host == domain ||
            host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.'
}
