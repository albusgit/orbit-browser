package com.albustech.orbit.browser

/** User agents Orbit sends. Derived from the WebView default so the Chrome version stays real. */
object UserAgents {

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
