package com.albustech.orbit.browser

/** How a page is drawn on the round screen. Reader arrives in Phase 2. */
enum class RenderMode {
    /** Content column capped to the inscribed square, vertical scrolling. */
    SCROLL,

    /** The page's own layout in overview, with pinch/double-tap zoom. Fallback for complex sites. */
    DESKTOP,
}
