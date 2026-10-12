package com.albustech.orbit.link

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * What the watch (Orbit) and the phone helper (Orbit Phone) say to each other over the Wear
 * Data Layer. Both apps share one applicationId and signing key, so only they can talk.
 *
 * The phone renders the real page in a WebView sized like the watch and sends it as picture
 * strips ([Tile]) plus a [PageMeta] (address, size, history and where the links and fields are).
 * The watch scrolls through the strips itself and sends back only what the user does ([Input]).
 *
 * Coordinates are document pixels of the rendered page: CSS px × the render scale. One strip
 * is a [Tile.height]-tall slice at [Tile.y]. JSON goes gzipped; strips are WebP with a header.
 * Everything is a one-way message: the watch knows the phone heard it when the page arrives.
 */
object Paths {
    /** The phone helper advertises this capability (phone/src/main/res/values/wear.xml). */
    const val CAPABILITY = "orbit_phone"

    /** Watch → phone: open [OpenRequest]. */
    const val OPEN = "/orbit/open"

    /** Watch → phone: one [Input]. */
    const val INPUT = "/orbit/input"

    /** Phone → watch: [PageMeta] for the page on screen. */
    const val META = "/orbit/meta"

    /** Phone → watch: one [Tile]. */
    const val TILE = "/orbit/tile"

    /** Phone → watch: serp.js's results message, plus "rev". */
    const val SERP = "/orbit/serp"

    /** Phone → watch: extract.js's article message, plus "rev". */
    const val ARTICLE = "/orbit/article"

    /** Phone → watch: a text field took focus after a tap ([FieldFocus]). */
    const val FOCUS = "/orbit/focus"

    /** The Data Layer refuses messages above 100 KB. */
    const val MAX_MESSAGE = 98 * 1024
}

object Json {
    fun pack(o: JSONObject): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(o.toString().toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun unpack(bytes: ByteArray): JSONObject =
        JSONObject(GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8))
}

/** Which layout the phone renders, matching the watch's View setting. */
enum class View { MOBILE, COMPACT, DESKTOP }

/**
 * Open [url] in the phone's page (history is kept unless [fresh]). [cssWidth] is the layout
 * width in CSS px; the page is rendered [widthPx] × [heightPx] (the watch screen) × [scale].
 */
data class OpenRequest(
    val url: String,
    val view: View,
    val cssWidth: Int,
    val widthPx: Int,
    val heightPx: Int,
    val scale: Int = 1,
    val textZoom: Int = 100,
    val fresh: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url).put("view", view.name).put("cssWidth", cssWidth)
        .put("widthPx", widthPx).put("heightPx", heightPx).put("scale", scale)
        .put("textZoom", textZoom).put("fresh", fresh)

    companion object {
        fun from(o: JSONObject) = OpenRequest(
            url = o.getString("url"),
            view = View.entries.firstOrNull { it.name == o.optString("view") } ?: View.MOBILE,
            cssWidth = o.getInt("cssWidth").coerceIn(120, 1600),
            widthPx = o.getInt("widthPx").coerceIn(120, 1200),
            heightPx = o.getInt("heightPx").coerceIn(120, 1200),
            scale = o.optInt("scale", 1).coerceIn(1, 2),
            textZoom = o.optInt("textZoom", 100).coerceIn(50, 300),
            fresh = o.optBoolean("fresh"),
        )
    }
}

/** Something the user did on the watch, for the phone's page. */
data class Input(
    val type: Type,
    val x: Int = 0,
    val y: Int = 0,
    val text: String = "",
    val submit: Boolean = false,
) {
    enum class Type {
        /** A tap at ([x], [y]). */
        TAP,

        /** Replace the focused field's text with [text]; [submit] presses Enter after. */
        TEXT,
        BACK,
        FORWARD,
        RELOAD,
        STOP,

        /** The watch is showing the page from [y] down: render the strips around it. */
        VIEW,

        /** Run Readability; [submit] = forced (the user picked Reader). */
        EXTRACT,
    }

    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("x", x).put("y", y).put("text", text).put("submit", submit)

    companion object {
        fun from(o: JSONObject): Input? {
            val type = Type.entries.firstOrNull { it.name == o.optString("type") } ?: return null
            return Input(type, o.optInt("x"), o.optInt("y"), o.optString("text").take(MAX_TEXT), o.optBoolean("submit"))
        }

        const val MAX_TEXT = 4000
    }
}

/** A link, button or field on the page, in document px. */
data class Box(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val kind: Kind,
    val href: String = "",
    val label: String = "",
) {
    enum class Kind { LINK, BUTTON, INPUT }

    fun contains(px: Int, py: Int, slop: Int = 0): Boolean =
        px >= x - slop && px < x + w + slop && py >= y - slop && py < y + h + slop

    fun toJson(): JSONArray = JSONArray().put(x).put(y).put(w).put(h).put(kind.ordinal).put(href).put(label)

    companion object {
        fun from(a: JSONArray) = Box(
            a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3),
            Kind.entries.getOrElse(a.getInt(4)) { Kind.BUTTON }, a.optString(5), a.optString(6),
        )
    }
}

/**
 * The state of the phone's page. [rev] goes up with every navigation; strips carry it, so the
 * watch drops strips of a page it has left. [error] is a PageError kind name.
 */
data class PageMeta(
    val rev: Int,
    val url: String,
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val loading: Boolean = false,
    val progress: Int = 100,
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    val backUrl: String? = null,
    val live: Boolean = false,
    val blocked: Int = 0,
    val error: String? = null,
    val errorDetail: String = "",
    /** Null: unchanged since the last meta (progress updates leave them out). */
    val boxes: List<Box>? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rev", rev).put("url", url).put("title", title).put("width", width).put("height", height)
        .put("loading", loading).put("progress", progress).put("canBack", canBack).put("canForward", canForward)
        .put("live", live).put("blocked", blocked).put("errorDetail", errorDetail)
        .apply {
            backUrl?.let { put("backUrl", it) }
            error?.let { put("error", it) }
            boxes?.let { b -> put("boxes", JSONArray().also { a -> b.forEach { a.put(it.toJson()) } }) }
        }

    companion object {
        /** [error] when the phone can't render at all (it may not run in the background): any rev. */
        const val UNAVAILABLE = "UNAVAILABLE"

        fun from(o: JSONObject): PageMeta {
            val raw = o.optJSONArray("boxes")
            return PageMeta(
                rev = o.getInt("rev"),
                url = o.getString("url"),
                title = o.optString("title"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                loading = o.optBoolean("loading"),
                progress = o.optInt("progress", 100),
                canBack = o.optBoolean("canBack"),
                canForward = o.optBoolean("canForward"),
                backUrl = o.optString("backUrl").ifEmpty { null },
                live = o.optBoolean("live"),
                blocked = o.optInt("blocked"),
                error = o.optString("error").ifEmpty { null },
                errorDetail = o.optString("errorDetail"),
                boxes = raw?.let { List(it.length()) { i -> Box.from(it.getJSONArray(i)) } },
            )
        }
    }
}

/**
 * Pages the phone opens for the watch: http(s) only, and never the phone's own network
 * (localhost, private and link-local addresses), so a watch request can't reach a router.
 */
object UrlSafety {
    fun isPublicWebUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.lowercase()?.trim('[', ']')?.trimEnd('.') ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        val v4 = host.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return true }
        if (v4 != null) {
            val (a, b) = v4
            return !(a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127))
        }
        if (':' in host) return !(host == "::1" || host == "::" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80"))
        return true
    }
}

/** A text field took focus on the phone: the watch opens its keyboard. */
data class FieldFocus(val rev: Int, val value: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("rev", rev).put("value", value).put("label", label)

    companion object {
        fun from(o: JSONObject) = FieldFocus(o.getInt("rev"), o.optString("value"), o.optString("label"))
    }
}

/** One strip of the rendered page: WebP [image], [width] × [height] px at document [y]. */
class Tile(val rev: Int, val y: Int, val width: Int, val height: Int, val image: ByteArray) {

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream(image.size + HEADER)
        DataOutputStream(out).use {
            it.writeInt(MAGIC)
            it.writeInt(rev)
            it.writeInt(y)
            it.writeInt(width)
            it.writeInt(height)
            it.write(image)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x4F524254 // "ORBT"
        private const val HEADER = 20

        fun decode(bytes: ByteArray): Tile? {
            if (bytes.size <= HEADER) return null
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) return null
            val rev = input.readInt()
            val y = input.readInt()
            val w = input.readInt()
            val h = input.readInt()
            if (y < 0 || w <= 0 || h <= 0) return null
            return Tile(rev, y, w, h, bytes.copyOfRange(HEADER, bytes.size))
        }
    }
}
