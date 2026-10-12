package com.albustech.porter

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import javax.net.ssl.SSLException

class DiagnoseTest {
    @Test fun openPortPasses() {
        ServerSocket(0).use { Diagnose.probe("127.0.0.1", it.localPort, "Wireless debugging") }
    }

    @Test fun closedPortSaysNothingIsListening() {
        val port = ServerSocket(0).use { it.localPort }
        try {
            Diagnose.probe("127.0.0.1", port, "Wireless debugging")
            fail()
        } catch (e: Diagnose.Unreachable) {
            assertTrue(e.message!!, "nothing is listening on port $port" in e.message!!)
            assertTrue(Diagnose.connectFailure(e, null) == e.message)
        }
    }

    @Test fun wrongCodeReadsAsWrongCode() {
        val msg = Diagnose.pairFailure(IOException("Exchanging message wasn't successful."))
        assertTrue(msg, "code was probably wrong" in msg)
        assertTrue("code was probably wrong" in Diagnose.pairFailure(IOException(EOFException())))
    }

    @Test fun hiddenTlsCauseIsShown() {
        val msg = Diagnose.connectFailure(IOException("Connection failed"), SSLException("handshake alert: bad_certificate"))
        assertTrue(msg, "bad_certificate" in msg)
        val unpaired = Diagnose.connectFailure(IOException("Connection failed"), SSLException("Read error: ssl=0x1: protocol error"))
        assertTrue(unpaired, "pair it first" in unpaired)
    }
}
