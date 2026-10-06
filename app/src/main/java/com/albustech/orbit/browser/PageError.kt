package com.albustech.orbit.browser

import org.mozilla.geckoview.WebRequestError

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
        /** Maps GeckoView's WebRequestError category and code. */
        fun kindFor(category: Int, code: Int): Kind = when {
            code == WebRequestError.ERROR_OFFLINE -> Kind.OFFLINE
            code == WebRequestError.ERROR_NET_TIMEOUT -> Kind.TIMEOUT
            code == WebRequestError.ERROR_UNKNOWN_HOST || code == WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> Kind.HOST
            category == WebRequestError.ERROR_CATEGORY_SECURITY ||
                code == WebRequestError.ERROR_BAD_HSTS_CERT -> Kind.SECURITY
            else -> Kind.OTHER
        }

        /** A plain description for the codes that end up as [Kind.OTHER]. */
        fun detailFor(code: Int): String = when (code) {
            WebRequestError.ERROR_CONNECTION_REFUSED -> "The site refused the connection."
            WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "The connection was interrupted."
            WebRequestError.ERROR_REDIRECT_LOOP -> "The page keeps redirecting to itself."
            WebRequestError.ERROR_UNSAFE_CONTENT_TYPE, WebRequestError.ERROR_CORRUPTED_CONTENT,
            WebRequestError.ERROR_INVALID_CONTENT_ENCODING -> "The site sent something Orbit can't show."
            WebRequestError.ERROR_CONTENT_CRASHED -> "The page crashed."
            WebRequestError.ERROR_MALFORMED_URI, WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "That address isn't valid."
            WebRequestError.ERROR_PORT_BLOCKED -> "That port is blocked for safety."
            WebRequestError.ERROR_HTTPS_ONLY -> "The site has no secure version."
            else -> "Network error"
        }
    }
}
