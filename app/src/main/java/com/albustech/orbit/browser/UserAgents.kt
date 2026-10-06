package com.albustech.orbit.browser

/** User agents Orbit sends. Derived from the WebView default so the Chrome version stays real. */
object UserAgents {

    /**
     * A small-browser identity (Opera Mini). Many sites, Google search included, answer it
     * with a basic HTML page: lighter over Bluetooth and simpler to lay out on a circle.
     */
    const val LITE = "Opera/9.80 (Android; Opera Mini/36.2.2254/119.132; U; en) Presto/2.12.423 Version/12.16"

    /**
     * The WebView default with the `wv` and `Version/4.0` markers removed, which some sites
     * use to serve broken "in-app" pages, and with `Mobile` guaranteed so sites serve mobile layouts.
     */
    fun mobile(defaultUa: String): String {
        var ua = defaultUa
            .replace("; wv)", ")")
            .replace(Regex("\\s?Version/\\d+(\\.\\d+)*"), "")
        if (!ua.contains("Mobile")) {
            ua = if (ua.contains(" Safari/")) ua.replace(" Safari/", " Mobile Safari/") else "$ua Mobile"
        }
        return ua
    }
}
