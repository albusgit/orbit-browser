package com.albustech.orbit.browser

import java.net.URLEncoder

/**
 * Turns what the user said or typed into a URL: either the address itself or a search.
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object UrlResolver {

    const val QUERY_PLACEHOLDER = "%s"

    private val SCHEME = Regex("^(https?)://", RegexOption.IGNORE_CASE)
    private val SPOKEN_HOST = Regex("^[a-z0-9-]+( dot [a-z0-9-]+)+(/\\S*)?$")
    private val HOST_LIKE = Regex(
        "^(" +
            "localhost" +
            "|(\\d{1,3}\\.){3}\\d{1,3}" +
            "|([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}" +
            ")(:\\d{1,5})?([/?#]\\S*)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param input raw text from voice or keyboard.
     * @param searchTemplate a URL containing [QUERY_PLACEHOLDER] where the encoded query goes.
     * @return the URL to load, or null for blank input.
     */
    fun resolve(input: String, searchTemplate: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null

        if (SCHEME.containsMatchIn(text)) return text
        if (text.equals("about:blank", ignoreCase = true)) return text

        val spoken = normalizeSpoken(text)
        if (!spoken.contains(' ') && HOST_LIKE.matches(spoken)) return "https://$spoken"

        return searchUrl(text, searchTemplate)
    }

    fun searchUrl(query: String, searchTemplate: String): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return if (searchTemplate.contains(QUERY_PLACEHOLDER)) {
            searchTemplate.replace(QUERY_PLACEHOLDER, encoded)
        } else {
            searchTemplate + encoded
        }
    }

    /** "Wikipedia dot org" (as speech recognisers sometimes return it) → "wikipedia.org". */
    private fun normalizeSpoken(text: String): String {
        val lower = text.lowercase()
        return if (SPOKEN_HOST.matches(lower)) lower.replace(" dot ", ".") else text
    }
}
