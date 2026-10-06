package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.geckoview.WebRequestError

class GeckoMappingTest {

    @Test
    fun `load errors map to Orbit's error kinds`() {
        fun kind(category: Int, code: Int) = PageError.kindFor(category, code)
        assertEquals(PageError.Kind.OFFLINE, kind(WebRequestError.ERROR_CATEGORY_NETWORK, WebRequestError.ERROR_OFFLINE))
        assertEquals(PageError.Kind.TIMEOUT, kind(WebRequestError.ERROR_CATEGORY_NETWORK, WebRequestError.ERROR_NET_TIMEOUT))
        assertEquals(PageError.Kind.HOST, kind(WebRequestError.ERROR_CATEGORY_URI, WebRequestError.ERROR_UNKNOWN_HOST))
        assertEquals(PageError.Kind.SECURITY, kind(WebRequestError.ERROR_CATEGORY_SECURITY, WebRequestError.ERROR_SECURITY_BAD_CERT))
        assertEquals(PageError.Kind.SECURITY, kind(WebRequestError.ERROR_CATEGORY_NETWORK, WebRequestError.ERROR_BAD_HSTS_CERT))
        assertEquals(PageError.Kind.OTHER, kind(WebRequestError.ERROR_CATEGORY_NETWORK, WebRequestError.ERROR_CONNECTION_REFUSED))
        assertEquals("The site refused the connection.", PageError.detailFor(WebRequestError.ERROR_CONNECTION_REFUSED))
        assertEquals("Network error", PageError.detailFor(WebRequestError.ERROR_UNKNOWN))
    }

    @Test
    fun `uBlock badge text becomes a count`() {
        assertEquals(0, BrowserController.parseBadge(null))
        assertEquals(0, BrowserController.parseBadge(""))
        assertEquals(12, BrowserController.parseBadge("12"))
        assertEquals(1000, BrowserController.parseBadge("1k"))
        assertEquals(2000, BrowserController.parseBadge("2K+"))
    }
}
