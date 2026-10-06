package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentsTest {

    @Test
    fun `webview markers are removed`() {
        val wv = "Mozilla/5.0 (Linux; Android 14; SM-R960 Build/UP1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; SM-R960 Build/UP1A) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
            UserAgents.mobile(wv),
        )
    }

    @Test
    fun `mobile token is added when missing`() {
        val ua = "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
            UserAgents.mobile(ua),
        )
    }
}
