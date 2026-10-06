package com.albustech.orbit.browser

/**
 * Back over GeckoView's history list (HistoryDelegate.onHistoryStateChange), kept pure so it's
 * unit-tested. [docOf] maps an entry's URL to the document it shows: a reader page to its
 * article, anything else to itself.
 */
object GeckoHistory {

    const val BLANK = "about:blank"

    /**
     * How far Back goes from [current], or null at the start. From a reader page every entry of
     * the same article right behind it is skipped (its source page, an earlier reader copy, a
     * #fragment the page pushed): landing on one would only re-open the reader. Blank tab
     * entries are skipped too.
     */
    fun backSteps(
        entries: List<String?>,
        current: Int,
        reading: String?,
        docOf: (String?) -> String?,
        sameDoc: (String?, String?) -> Boolean,
    ): Int? {
        if (current !in entries.indices) return null
        var j = current - 1
        if (reading != null) while (j >= 0 && sameDoc(docOf(entries[j]), reading)) j--
        while (j >= 0 && entries[j] == BLANK) j--
        return if (j >= 0) j - current else null
    }
}
