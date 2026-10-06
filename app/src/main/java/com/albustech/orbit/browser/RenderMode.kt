package com.albustech.orbit.browser

/** How the current page is drawn on the round screen. */
enum class RenderMode {
    /** Article text in circular pages; the bezel turns pages. */
    READER,

    /** Content column capped to the inscribed square, vertical scrolling. */
    SCROLL,

    /** The page's own layout in overview with pinch/double-tap zoom. Fallback for complex sites. */
    ZOOM,
}

/** What the bezel does. Switched by a long press in the centre of the screen. */
enum class BezelMode {
    /** Scroll (Round Scroll / Zoom view) or turn pages (Reader). */
    SCROLL,

    /** Step through visible links and controls; a tap opens the focused one. */
    LINKS,

    /** Zoom the page (Zoom view / Round Scroll) or change the text size (Reader). */
    ZOOM;

    fun next(): BezelMode = entries[(ordinal + 1) % entries.size]
}

/** The control links.js has focused. */
data class LinkFocus(val href: String, val label: String, val kind: String)

/** Where the reader is: 0-based page, pages paginated so far, and whether that's all of them. */
data class ReaderProgress(val page: Int, val total: Int, val done: Boolean)
