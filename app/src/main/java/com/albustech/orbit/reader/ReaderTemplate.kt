package com.albustech.orbit.reader

import com.albustech.orbit.browser.JsStrings
import com.albustech.orbit.data.ReaderStyle
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** What extract.js found on a page. [content] is already sanitised HTML. */
data class Article(
    val url: String,
    val title: String,
    val byline: String,
    val siteName: String,
    val lang: String,
    val dir: String,
    val words: Int,
    val content: String,
)

/** A position inside an article that survives re-pagination: unit index and word index. */
data class Anchor(val unit: Int, val word: Int)

/**
 * Builds what the reader page shows. The page itself (reader.html, reader-boot.js, reader.js) is
 * an extension page in GeckoView: it receives this payload from the extension's background script
 * and lays it out. Pure Kotlin so it can be unit-tested; tools/round-check assembles the same
 * pieces for its headless checks.
 */
object ReaderTemplate {

    private val LANG = Regex("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$")

    /**
     * { lang, dir, title, html, config }. [Article.content] must already be sanitised: the page
     * sets it with innerHTML (the extension's CSP stops any script in it from running regardless).
     */
    fun payload(article: Article, diameterCss: Float, squareCss: Float, insetCss: Float, style: ReaderStyle, anchor: Anchor?): JSONObject {
        val title = article.title.ifBlank { article.siteName.ifBlank { article.url } }
        val html = buildString {
            append("<header class=\"orbit-title-block\">")
            append("<h1 class=\"orbit-title\">").append(escapeHtml(title)).append("</h1>")
            append("<p class=\"orbit-meta\">").append(escapeHtml(meta(article))).append("</p>")
            append("</header>\n")
            append(article.content)
        }
        return JSONObject()
            .put("lang", article.lang.takeIf { LANG.matches(it) } ?: "")
            .put("dir", article.dir.lowercase().takeIf { it == "rtl" || it == "ltr" } ?: "auto")
            .put("title", title)
            .put("html", html)
            .put("config", JSONObject(config(diameterCss, squareCss, insetCss, style, anchor)))
    }

    /** "Byline · Site · 6 min read" */
    fun meta(article: Article): String {
        val minutes = (article.words / WORDS_PER_MINUTE).coerceAtLeast(1)
        return listOf(article.byline.trim(), article.siteName.trim(), "$minutes min read")
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" · ")
    }

    fun config(d: Float, sq: Float, inset: Float, style: ReaderStyle, anchor: Anchor?): String = buildString {
        append("{\"d\":").append(JsStrings.number(d))
        append(",\"sq\":").append(JsStrings.number(sq))
        append(",\"inset\":").append(JsStrings.number(inset))
        append(",\"style\":").append(styleJson(style))
        append(",\"anchor\":")
        if (anchor == null) append("null") else append("{\"u\":${anchor.unit},\"w\":${anchor.word}}")
        append('}')
    }

    /** The style object reader.js takes, for live changes (it keeps the reader on the same word). */
    fun styleJson(style: ReaderStyle): String =
        "{\"fontSize\":${style.fontSize},\"lineHeight\":${JsStrings.number(style.lineHeight)},\"serif\":${style.serif}}"

    /** moz-extension://…/reader.html#u=<article>&t=<token>: the article is in the URL so history can find it. */
    fun pageUrl(extensionBase: String, articleUrl: String, token: String): String =
        extensionBase.removeSuffix("/") + "/" + PAGE + "#u=" + URLEncoder.encode(articleUrl, "UTF-8") + "&t=" + token

    /** The article a reader page URL shows, or null if [url] isn't one. */
    fun articleOf(url: String?, extensionBase: String?): String? {
        if (url == null || extensionBase == null) return null
        val page = extensionBase.removeSuffix("/") + "/" + PAGE + "#"
        if (!url.startsWith(page)) return null
        val encoded = url.substring(page.length).split('&').firstOrNull { it.startsWith("u=") }?.substring(2) ?: return null
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    fun escapeHtml(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val PAGE = "reader.html"
    private const val WORDS_PER_MINUTE = 220
}
