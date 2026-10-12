package com.albustech.orbit.browser

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** Search engines whose result pages Orbit redraws as native cards. */
enum class SearchSite { GOOGLE, DUCKDUCKGO, BING }

/** A recognised search-results page: which engine, what was asked, and the 0-based offset. */
data class SerpPage(val site: SearchSite, val query: String, val start: Int)

data class SerpResult(val title: String, val url: String, val host: String, val snippet: String)

/** What serp.js found, after decoding and validation. */
data class SerpData(
    val page: SerpPage,
    val pageUrl: String,
    val results: List<SerpResult>,
    val answer: String?,
    val nextUrl: String?,
)

/** What the results screen shows: a search on its way, or its results. */
sealed interface SerpState {
    val page: SerpPage
    val url: String

    data class Loading(override val page: SerpPage, override val url: String) : SerpState

    data class Ready(val data: SerpData) : SerpState {
        override val page: SerpPage get() = data.page
        override val url: String get() = data.pageUrl
    }
}

/**
 * Recognises search-results pages and cleans their links. Pure Kotlin (java.net only) so it
 * is unit-tested on the JVM.
 */
object Serp {

    private val GOOGLE_HOST = Regex("^(www\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?$")
    /** Google tabs that aren't a list of web pages (images, news, videos, books, shopping). */
    private val GOOGLE_NON_WEB_TBM = setOf("isch", "nws", "vid", "bks", "shop", "lcl", "fin")

    fun detect(url: String?): SerpPage? {
        val uri = parse(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val q = params(uri.rawQuery)
        val query = q["q"]?.trim().orEmpty()
        if (query.isEmpty()) return null
        return when {
            GOOGLE_HOST.matches(host) && path == "/search" -> {
                if (q["tbm"] in GOOGLE_NON_WEB_TBM) return null
                // udm=14 is Google's plain "Web" filter; any other udm (2 = images, …) isn't a web list.
                val udm = q["udm"]
                if (udm != null && udm != "14") return null
                SerpPage(SearchSite.GOOGLE, query, q["start"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
            }
            (host == "html.duckduckgo.com" || host == "lite.duckduckgo.com") ||
                (host.endsWith("duckduckgo.com") && (path.startsWith("/html") || path.startsWith("/lite"))) ->
                SerpPage(SearchSite.DUCKDUCKGO, query, q["s"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
            (host == "bing.com" || host.endsWith(".bing.com")) && path == "/search" ->
                SerpPage(SearchSite.BING, query, ((q["first"]?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0))
            else -> null
        }
    }

    /**
     * The real destination of a result link: Google `/url?q=`, DuckDuckGo `/l/?uddg=` and Bing
     * `/ck/a?u=a1…` redirects are unwrapped (one hop fewer over Bluetooth, no click tracking).
     * Returns null for anything that isn't an http(s) page outside the engine itself.
     */
    fun decodeResultUrl(href: String?, site: SearchSite): String? {
        val uri = parse(href) ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val q = params(uri.rawQuery)
        val target = when {
            GOOGLE_HOST.matches(host) && uri.rawPath == "/url" -> q["q"] ?: q["url"]
            host.endsWith("duckduckgo.com") && uri.rawPath.orEmpty().startsWith("/l/") -> q["uddg"]
            host.endsWith("bing.com") && uri.rawPath.orEmpty().startsWith("/ck/") -> q["u"]?.let(::decodeBing)
            else -> href
        } ?: return null
        val t = parse(target) ?: return null
        val scheme = t.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val tHost = t.host?.lowercase() ?: return null
        // Links back into the engine (more searches, image tabs, settings) aren't results.
        if (GOOGLE_HOST.matches(tHost) || tHost.endsWith("duckduckgo.com") || tHost == "bing.com" || tHost.endsWith(".bing.com")) {
            return null
        }
        return target
    }

    /** The next page of results, or null where the engine has no simple GET form for it. */
    fun nextUrl(page: SerpPage, results: Int): String? {
        if (results == 0) return null
        val q = URLEncoder.encode(page.query, Charsets.UTF_8.name())
        return when (page.site) {
            SearchSite.GOOGLE -> "https://www.google.com/search?q=$q&start=${page.start + GOOGLE_PAGE}"
            SearchSite.DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=$q&s=${page.start + results}&dc=${page.start + results + 1}"
            SearchSite.BING -> "https://www.bing.com/search?q=$q&first=${page.start + results + 1}"
        }
    }

    /** Host without "www." for display ("noaa.gov"). */
    fun displayHost(url: String): String =
        parse(url)?.host?.lowercase()?.removePrefix("www.").orEmpty()

    private const val GOOGLE_PAGE = 10

    /** Bing's `u` is "a1" + base64url of the destination. */
    private fun decodeBing(u: String): String? {
        if (!u.startsWith("a1")) return null
        return try {
            String(Base64.getUrlDecoder().decode(u.substring(2).padEnd((u.length - 2 + 3) / 4 * 4, '=')), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parse(url: String?): URI? =
        if (url.isNullOrBlank()) null else try {
            URI(url.trim())
        } catch (_: Exception) {
            null
        }

    private fun params(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            val key = decode(k)
            if (key.isNotEmpty() && key !in out) out[key] = decode(v)
        }
        return out
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            s
        }
}
