package com.albustech.orbit.reader

import com.albustech.orbit.browser.JsStrings
import com.albustech.orbit.data.ReaderStyle

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
 * Builds the reader page from reader.html. Pure Kotlin so it can be unit-tested; the
 * headless pagination check in tools/round-check fills the same template the same way.
 */
object ReaderTemplate {

    /** The fragment marking a reader page; round.js skips pages with it (see Injector). */
    const val MARKER = "#orbit-reader"

    private val PLACEHOLDER = Regex("\\{\\{([A-Z]+)\\}\\}")
    private val LANG = Regex("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$")

    fun build(
        template: String,
        css: String,
        script: String,
        article: Article,
        diameterCss: Float,
        squareCss: Float,
        insetCss: Float,
        style: ReaderStyle,
        anchor: Anchor?,
        nonce: String,
    ): String {
        val values = mapOf(
            "LANG" to (article.lang.takeIf { LANG.matches(it) } ?: ""),
            "DIR" to (article.dir.lowercase().takeIf { it == "rtl" || it == "ltr" } ?: "auto"),
            "NONCE" to nonce,
            "TITLE" to escapeHtml(article.title.ifBlank { article.siteName.ifBlank { article.url } }),
            "META" to escapeHtml(meta(article)),
            "CSS" to css,
            "CONTENT" to article.content,
            "CONFIG" to config(diameterCss, squareCss, insetCss, style, anchor),
            "SCRIPT" to script,
        )
        // Single pass: inserted values (article HTML in particular) are never re-scanned.
        return PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }
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
        append(",\"style\":{\"fontSize\":").append(style.fontSize)
        append(",\"lineHeight\":").append(JsStrings.number(style.lineHeight))
        append(",\"serif\":").append(style.serif).append('}')
        append(",\"anchor\":")
        if (anchor == null) append("null") else append("{\"u\":${anchor.unit},\"w\":${anchor.word}}")
        append('}')
    }

    /** JS for a live style change, keeping the reader on the same word. */
    fun setStyleJs(style: ReaderStyle): String =
        "window.orbitReader&&orbitReader.setStyle({fontSize:${style.fontSize}," +
            "lineHeight:${JsStrings.number(style.lineHeight)},serif:${style.serif}})"

    /** Base URL for the reader page: the article's URL with [MARKER] as its fragment. */
    fun baseUrl(articleUrl: String): String = articleUrl.substringBefore('#') + MARKER

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

    private const val WORDS_PER_MINUTE = 220
}
