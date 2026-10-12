package com.albustech.porter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.Signature

class PorterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun syncHeaderIsIdThenLittleEndianLength() {
        val h = SyncProtocol.header("DATA", 0x00010203)
        assertArrayEquals(byteArrayOf('D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 3, 2, 1, 0), h)
        assertEquals("DATA", SyncProtocol.id(h))
        assertEquals(0x00010203, SyncProtocol.length(h))
        assertEquals(65536, SyncProtocol.length(SyncProtocol.header("FAIL", 65536)))
    }

    @Test fun keysAreMadeOnceThenReloaded() {
        val dir = tmp.newFolder()
        val first = AdbKeys.loadOrCreate(dir)
        val again = AdbKeys.loadOrCreate(dir)
        assertArrayEquals(first.privateKey.encoded, again.privateKey.encoded)
        assertArrayEquals(first.certificate.encoded, again.certificate.encoded)
        // The certificate carries the key's public half.
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(again.privateKey); update(1) }.sign()
        val ok = Signature.getInstance("SHA256withRSA").apply { initVerify(again.certificate); update(1) }.verify(sig)
        assertEquals(true, ok)
    }

    @Test fun timeoutsReadNaturally() {
        assertEquals("10 min", Porter.formatMs(600_000))
        assertEquals("30 s", Porter.formatMs(30_000))
    }
}
