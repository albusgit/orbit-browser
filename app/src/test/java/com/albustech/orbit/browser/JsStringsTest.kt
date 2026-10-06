package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class JsStringsTest {

    @Test
    fun `quotes and escapes`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", JsStrings.quote("a\"b\\c\nd"))
        assertEquals("\"\\u003c/script>\"", JsStrings.quote("</script>"))
        assertEquals("\"\\u2028\"", JsStrings.quote("\u2028"))
        assertEquals("\"\\u0001\"", JsStrings.quote("\u0001"))
    }

    @Test
    fun `numbers are locale independent`() {
        assertEquals("169.706", JsStrings.number(169.7056f))
        assertEquals("0", JsStrings.number(Float.NaN))
    }
}
