package com.albustech.orbit.browser

import com.albustech.orbit.data.db.SiteMode

/** How the current page is drawn on the round screen. */
enum class RenderMode {
    /** Article text in circular pages; the bezel turns pages. */
    READER,

    /** The site's own mobile version, as a phone would show it. The default. */
    MOBILE,

    /** The mobile version laid out at a phone's width (360 CSS px) and scaled to fit. */
    COMPACT,

    /** The site's desktop version, shown whole; pinch or bezel to zoom. */
    ZOOM,
    ;

    companion object {
        /** The three choices of the global View setting. */
        val VIEWS = listOf(MOBILE, COMPACT, ZOOM)

        /** How a page renders for a site's View choice, given the global View setting. */
        fun forSite(mode: SiteMode, default: RenderMode): RenderMode = when (mode) {
            SiteMode.MOBILE -> MOBILE
            SiteMode.SCROLL -> COMPACT
            SiteMode.ZOOM -> ZOOM
            SiteMode.AUTO, SiteMode.READER -> default
        }
    }
}

/** What the bezel does. Picked on the mode arc (a tap on the page shows it). */
enum class BezelMode {
    /** Scroll the page or turn pages (Reader). */
    SCROLL,

    /** Step through visible links and controls; a tap opens the focused one. */
    LINKS,

    /** Touch drives an on-screen cursor like a trackpad and a tap clicks under it; the bezel scrolls. */
    CURSOR,

    /** Zoom the page or change the text size (Reader). Pinch zooms in every mode. */
    ZOOM,
}

/** The control links.js has focused. */
data class LinkFocus(val href: String, val label: String, val kind: String, val index: Int = 0, val count: Int = 0)

/** Where the reader is: 0-based page, pages paginated so far, and whether that's all of them. */
data class ReaderProgress(val page: Int, val total: Int, val done: Boolean)
