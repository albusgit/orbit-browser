package com.albustech.orbit.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Second, app-side sanitising pass over the article HTML that extract.js posts.
 *
 * extract.js already cleans the article, but it runs inside the page, which could tamper with
 * it. This pass runs in the app, where the page can't reach, against an allow-list of the
 * markup the reader actually lays out. Relative links resolve against the article URL.
 */
object ArticleSanitizer {

    private val SAFELIST: Safelist = Safelist.relaxed()
        .addTags(
            "figure", "figcaption", "hr", "header", "section", "article", "main", "aside",
            "details", "summary", "abbr", "mark", "s", "del", "ins", "kbd", "samp", "var",
            "time", "dfn", "bdi", "bdo", "wbr", "address",
        )
        .addAttributes(":all", "lang", "dir")
        .addAttributes("img", "srcset", "sizes", "loading", "decoding")
        .addAttributes("a", "rel")
        .addAttributes("ol", "start", "reversed", "type")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan", "scope")
        .addAttributes("time", "datetime")
        .addProtocols("img", "src", "http", "https", "data")
        .addProtocols("a", "href", "http", "https", "mailto", "#")
        .preserveRelativeLinks(false)

    fun clean(html: String, baseUrl: String): String {
        val dirty = Jsoup.parseBodyFragment(html, baseUrl)
        val clean: Document = Cleaner(SAFELIST).clean(dirty)
        // Only images that can show (data: limited to images), loaded lazily.
        clean.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isBlank() || (src.startsWith("data:", ignoreCase = true) && !src.startsWith("data:image/", ignoreCase = true))) {
                img.remove()
            } else {
                img.attr("loading", "lazy").attr("decoding", "async")
            }
        }
        clean.select("a[href]").forEach { it.attr("rel", "noreferrer") }
        clean.outputSettings().prettyPrint(false)
        return clean.body().html()
    }
}
