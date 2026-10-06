package com.albustech.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTypeTest {

    @Test
    fun `wifi beats cellular beats bluetooth`() {
        assertEquals(ConnectionType.WIFI, ConnectionType.from(true, true, true, false, true))
        assertEquals(ConnectionType.CELLULAR, ConnectionType.from(false, true, true, false, true))
        assertEquals(ConnectionType.BLUETOOTH, ConnectionType.from(false, false, true, false, true))
        assertEquals(ConnectionType.OTHER, ConnectionType.from(false, false, false, true, true))
        assertEquals(ConnectionType.NONE, ConnectionType.from(true, false, false, false, false))
    }

    @Test
    fun `bluetooth gets a longer timeout`() {
        assertTrue(ConnectionType.BLUETOOTH.stallTimeoutMs > ConnectionType.WIFI.stallTimeoutMs)
    }

    @Test
    fun `timeout over bluetooth advises wifi`() {
        val e = PageError("https://x", PageError.Kind.TIMEOUT, "", ConnectionType.BLUETOOTH)
        assertTrue(e.message().second.contains("Wi-Fi"))
        val w = PageError("https://x", PageError.Kind.TIMEOUT, "", ConnectionType.WIFI)
        assertTrue(!w.message().second.contains("Bluetooth"))
    }
}
