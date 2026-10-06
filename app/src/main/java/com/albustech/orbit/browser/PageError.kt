package com.albustech.orbit.browser

import android.webkit.WebViewClient

/** A main-frame load failure, shown as Orbit's own error screen. */
data class PageError(
    val url: String,
    val kind: Kind,
    val detail: String,
    val connection: ConnectionType,
) {
    enum class Kind { OFFLINE, TIMEOUT, HOST, SECURITY, HTTP, OTHER }

    /** Short title and a sentence of advice, tuned for how the watch is connected. */
    fun message(): Pair<String, String> {
        val viaPhone = connection == ConnectionType.BLUETOOTH
        return when (kind) {
            Kind.OFFLINE -> "No connection" to
                "The watch isn't connected. Check Wi-Fi, LTE, or that your phone is nearby."
            Kind.TIMEOUT -> if (viaPhone) {
                "Too slow over Bluetooth" to
                    "You're online through your phone, which is slow for web pages. Connect the watch to Wi-Fi, or try Reader view."
            } else {
                "Page took too long" to "The site didn't respond in time. Try again in a moment."
            }
            Kind.HOST -> "Site not found" to
                (if (viaPhone) "Check the address. If it's right, your phone may have lost its connection." else "Check the address and try again.")
            Kind.SECURITY -> "Not secure" to "The site's security certificate isn't valid, so Orbit didn't open it."
            Kind.HTTP -> "Page unavailable" to detail
            Kind.OTHER -> "Can't load page" to detail
        }
    }

    companion object {
        /** Maps WebViewClient.ERROR_* codes. */
        fun kindFor(code: Int): Kind = when (code) {
            WebViewClient.ERROR_TIMEOUT -> Kind.TIMEOUT
            WebViewClient.ERROR_HOST_LOOKUP -> Kind.HOST
            WebViewClient.ERROR_CONNECT -> Kind.OFFLINE
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> Kind.SECURITY
            else -> Kind.OTHER
        }
    }
}
