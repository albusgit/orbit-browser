package com.albustech.orbit.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun `requests and inputs survive the trip, with limits applied`() {
        val open = OpenRequest("https://example.com/", View.COMPACT, 360, 480, 480, scale = 2, textZoom = 120, fresh = true)
        assertEquals(open, OpenRequest.from(Json.unpack(Json.pack(open.toJson()))))
        val wild = OpenRequest.from(open.toJson().put("cssWidth", 99999).put("scale", 7).put("view", "NOPE"))
        assertEquals(1600, wild.cssWidth)
        assertEquals(2, wild.scale)
        assertEquals(View.MOBILE, wild.view)

        val tap = Input(Input.Type.TEXT, 3, 4, "hello", submit = true)
        assertEquals(tap, Input.from(tap.toJson()))
        assertNull(Input.from(tap.toJson().put("type", "EXEC")))
        assertEquals(Input.MAX_TEXT, Input.from(tap.toJson().put("text", "x".repeat(9000)))!!.text.length)
    }

    @Test
    fun `a page with a thousand links stays well under the message limit`() {
        val boxes = List(1000) { i -> Box(12, i * 40, 300, 30, Box.Kind.LINK, "https://news.example.com/2026/10/story-$i", "Story number $i about something") }
        val meta = PageMeta(7, "https://news.example.com/", "News", 480, 40000, canBack = true, backUrl = "https://a/", error = "HOST", boxes = boxes)
        val packed = Json.pack(meta.toJson())
        assertTrue("${packed.size} bytes", packed.size < Paths.MAX_MESSAGE)
        assertEquals(meta, PageMeta.from(Json.unpack(packed)))
    }

    @Test
    fun `tiles round-trip and junk is refused`() {
        val image = ByteArray(1234) { it.toByte() }
        val back = Tile.decode(Tile(3, 960, 480, 480, image).encode())!!
        assertEquals(listOf(3, 960, 480, 480), listOf(back.rev, back.y, back.width, back.height))
        assertArrayEquals(image, back.image)
        assertNull(Tile.decode(ByteArray(40)))
        assertNull(Tile.decode(ByteArray(10)))
    }

    @Test
    fun `boxes hit-test with slop`() {
        val b = Box(10, 10, 20, 10, Box.Kind.BUTTON)
        assertTrue(b.contains(10, 10))
        assertTrue(!b.contains(30, 10))
        assertTrue(b.contains(32, 21, slop = 4))
    }

    @Test
    fun `the phone opens public web pages only`() {
        for (ok in listOf("https://example.com/", "http://93.184.216.34/x", "https://[2606:4700::1]/", "https://172.32.0.1/")) {
            assertTrue(ok, UrlSafety.isPublicWebUrl(ok))
        }
        for (bad in listOf("file:///sdcard", "javascript:alert(1)", "http://localhost:8080/", "http://192.168.1.1/", "http://10.0.0.2",
            "http://127.0.0.1/", "http://172.20.1.1/", "http://169.254.169.254/latest", "http://[::1]/", "http://[fd00::1]/", "http://router.local/", "not a url")) {
            assertTrue(bad, !UrlSafety.isPublicWebUrl(bad))
        }
    }

    @Test
    fun `progress metas leave the boxes out`() {
        val meta = PageMeta(2, "https://a/", progress = 40)
        assertNull(PageMeta.from(meta.toJson()).boxes)
    }
}
