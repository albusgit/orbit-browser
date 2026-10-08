package com.albustech.orbit.browser

import com.albustech.orbit.data.db.SiteMode

/** How the current page is drawn on the round screen. */
enum class RenderMode {
    /** Article text in circular pages; the bezel turns pages. */
    READER,

    /** The site's own mobile version, as a phone would show it. The default. */
    MOBILE,

    /** Opt-in "Round fit": the content column capped to the inscribed square. */
    SCROLL,

    /** The site's desktop version, shown whole; pinch or bezel to zoom. */
    ZOOM,
    ;

    companion object {
        /** How a page renders for a site's View choice, given the global Desktop sites setting. */
        fun forSite(mode: SiteMode, desktopSites: Boolean): RenderMode = when (mode) {
            SiteMode.SCROLL -> SCROLL
            SiteMode.ZOOM -> ZOOM
            SiteMode.AUTO, SiteMode.READER -> if (desktopSites) ZOOM else MOBILE
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
