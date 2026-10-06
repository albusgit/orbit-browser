package com.albustech.orbit.browser

/** The user agent Orbit sends instead of Gecko's own mobile one, per site, when asked for. */
object UserAgents {

    /**
     * A small-browser identity (Opera Mini). Many sites, Google search included, answer it
     * with a basic HTML page: lighter over Bluetooth and simpler to lay out on a circle.
     */
    const val LITE = "Opera/9.80 (Android; Opera Mini/36.2.2254/119.132; U; en) Presto/2.12.423 Version/12.16"
}
