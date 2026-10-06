package com.albustech.orbit.browser.filters

import com.albustech.orbit.browser.Domains

/**
 * Network blocking with EasyList + EasyPrivacy, in the form tools/filters/compile.mjs leaves
 * them (assets/filters/network.txt): already normalised, so loading is splitting lines.
 *
 * - Host filters (`||host^`, ~95k of them) are kept as a sorted array of 64-bit hashes: about
 *   760 KB instead of several MB of strings, and a lookup is a binary search per host label.
 * - Pattern filters (~13k) are indexed by one token each, the rarest safe one, the way uBlock
 *   Origin does it: a request only tests the filters filed under the tokens in its own URL.
 *
 * Thread-safe once built: [shouldBlock] runs on WebView's network threads.
 */
class FilterEngine private constructor(
    private val hosts: LongArray,
    private val hosts3p: LongArray,
    private val block: TokenIndex,
    private val important: TokenIndex,
    private val allow: TokenIndex,
    private val pageExceptions: List<NetFilter>,
) {
    val hostCount: Int get() = hosts.size + hosts3p.size
    val filterCount: Int get() = block.size + important.size + allow.size + pageExceptions.size

    /**
     * Whether a subresource request should be blocked. [url] is the full request URL, [pageHost]
     * the host of the page on screen, [type] a mask of [Types] (see [Types.guess]).
     */
    fun shouldBlock(url: String, pageHost: String?, type: Int): Boolean {
        val u = url.lowercase()
        val hostStart = u.indexOf("://").let { if (it < 0) return false else it + 3 }
        var hostEnd = hostStart
        while (hostEnd < u.length && u[hostEnd] != '/' && u[hostEnd] != '?' && u[hostEnd] != '#' && u[hostEnd] != ':') hostEnd++
        if (hostEnd == hostStart) return false
        val host = u.substring(hostStart, hostEnd)
        val page = pageHost?.lowercase()
        val thirdParty = page != null && Domains.registrable(host) != Domains.registrable(page)
        val req = Request(u, hostStart, hostEnd, page, thirdParty, type)

        if (important.matches(req)) return true
        val blocked = inHosts(hosts, host) || (thirdParty && inHosts(hosts3p, host)) || block.matches(req)
        return blocked && !allow.matches(req)
    }

    /** Page-level exceptions for [pageUrl]: any of [Types.DOCUMENT], [Types.ELEMHIDE], [Types.GENERICHIDE]. */
    fun pageFlags(pageUrl: String?): Int {
        if (pageUrl.isNullOrEmpty()) return 0
        val u = pageUrl.lowercase()
        val hostStart = u.indexOf("://").let { if (it < 0) return 0 else it + 3 }
        var hostEnd = hostStart
        while (hostEnd < u.length && u[hostEnd] != '/' && u[hostEnd] != '?' && u[hostEnd] != '#' && u[hostEnd] != ':') hostEnd++
        val host = u.substring(hostStart, hostEnd)
        val req = Request(u, hostStart, hostEnd, host, false, Types.ALL_REQUESTS)
        var flags = 0
        for (f in pageExceptions) if (f.matches(req, ignoreType = true)) flags = flags or (f.mask and Types.PAGE)
        return flags
    }

    private fun inHosts(table: LongArray, host: String): Boolean {
        if (table.isEmpty()) return false
        var i = 0
        while (true) {
            if (table.binarySearch(hash(host, i)) >= 0) return true
            val dot = host.indexOf('.', i)
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) return false // never a bare TLD
            i = dot + 1
        }
    }

    companion object {
        /** Builds the engine from network.txt. Takes ~100-300 ms on a watch: call off the main thread. */
        fun parse(text: String): FilterEngine {
            val hosts = LongArrayBuilder()
            val hosts3p = LongArrayBuilder()
            val filters = ArrayList<NetFilter>()
            val tokens = ArrayList<List<String>>()
            var section = ""
            text.lineSequence().forEach { line ->
                when {
                    line.isEmpty() || line.startsWith('!') -> Unit
                    line.startsWith('#') -> section = line
                    section == "#hosts" -> hosts.add(hash(line, 0))
                    section == "#hosts3p" -> hosts3p.add(hash(line, 0))
                    section == "#filters" -> NetFilter.parse(line)?.let {
                        filters += it
                        tokens += it.pattern.tokens()
                    }
                }
            }
            // Each filter is filed under its rarest token; the token lists are dropped after.
            val freq = HashMap<String, Int>()
            for (t in tokens) for (k in t) freq[k] = (freq[k] ?: 0) + 1
            val block = TokenIndex.Builder()
            val important = TokenIndex.Builder()
            val allow = TokenIndex.Builder()
            val page = ArrayList<NetFilter>()
            for (i in filters.indices) {
                val f = filters[i]
                if (f.allow && f.mask and Types.PAGE != 0) page += f
                if (f.mask and Types.ALL_REQUESTS == 0) continue
                val best = tokens[i].minWithOrNull(compareBy<String> { freq[it] ?: 0 }.thenByDescending { it.length })
                when {
                    f.allow -> allow
                    f.important -> important
                    else -> block
                }.add(best, f)
            }
            return FilterEngine(hosts.sorted(), hosts3p.sorted(), block.build(), important.build(), allow.build(), page)
        }

        /** FNV-1a over host[from..], so walking a host's labels allocates nothing. */
        internal fun hash(s: String, from: Int): Long = fnv(s, from, s.length)
    }
}

/** Resource-type bits, as in tools/filters/compile.mjs. */
object Types {
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLESHEET = 4
    const val XHR = 8
    const val SUBDOCUMENT = 16
    const val PING = 32
    const val WEBSOCKET = 64
    const val MEDIA = 128
    const val FONT = 256
    const val OTHER = 512
    const val ALL_REQUESTS = 0x3ff
    const val DOCUMENT = 0x400
    const val ELEMHIDE = 0x800
    const val GENERICHIDE = 0x1000
    const val PAGE = DOCUMENT or ELEMHIDE or GENERICHIDE

    /** What WebView can't tell us: a fetch-like request that is one of these. */
    const val UNKNOWN = SCRIPT or XHR or PING or OTHER

    private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")
    private val FONTS = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIA_EXT = setOf("mp4", "webm", "m3u8", "mp3", "m4a", "ogg", "aac", "m4s", "mpd")

    /**
     * WebView's shouldInterceptRequest doesn't say what a request is for. Guess from the Accept
     * header WebView sends for images, stylesheets and frames, then from the extension; what's
     * left is [UNKNOWN] and matches filters for any of those types.
     */
    fun guess(url: String, accept: String?): Int {
        if (accept != null) {
            when {
                accept.startsWith("image/") -> return IMAGE
                accept.startsWith("text/css") -> return STYLESHEET
                accept.startsWith("text/html") -> return SUBDOCUMENT
            }
        }
        val path = url.substringBefore('?').substringBefore('#')
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        if (dot <= slash) return UNKNOWN
        return when (path.substring(dot + 1).lowercase()) {
            "js", "mjs" -> SCRIPT
            "css" -> STYLESHEET
            in IMAGES -> IMAGE
            in FONTS -> FONT
            in MEDIA_EXT -> MEDIA
            else -> UNKNOWN
        }
    }
}

/** A request being matched, lower-cased once. */
internal class Request(
    val url: String,
    val hostStart: Int,
    val hostEnd: Int,
    val pageHost: String?,
    val thirdParty: Boolean,
    val type: Int,
)

/** One pattern filter: `kind \t party \t mask \t domains \t pattern` in network.txt. */
internal class NetFilter(
    val allow: Boolean,
    val important: Boolean,
    private val party: Int,
    val mask: Int,
    private val include: Array<String>,
    private val exclude: Array<String>,
    val pattern: Pattern,
) {
    fun matches(r: Request, ignoreType: Boolean = false): Boolean {
        if (!ignoreType && mask and r.type == 0) return false
        if (party == 3 && !r.thirdParty || party == 1 && r.thirdParty) return false
        if (include.isNotEmpty() || exclude.isNotEmpty()) {
            val page = r.pageHost ?: return false
            if (exclude.any { Domains.isWithin(page, it) }) return false
            if (include.isNotEmpty() && include.none { Domains.isWithin(page, it) }) return false
        }
        return pattern.matches(r.url, r.hostStart, r.hostEnd)
    }

    companion object {
        fun parse(line: String): NetFilter? {
            val parts = line.split('\t')
            if (parts.size != 5) return null
            val domains = if (parts[3].isEmpty()) emptyList() else parts[3].split('|')
            return NetFilter(
                allow = parts[0] == "a",
                important = parts[0] == "B",
                party = parts[1].toIntOrNull() ?: 0,
                mask = parts[2].toIntOrNull(16) ?: return null,
                include = domains.filter { !it.startsWith('~') }.toArrayOrEmpty(),
                exclude = domains.filter { it.startsWith('~') }.map { it.substring(1) }.toArrayOrEmpty(),
                pattern = Pattern.parse(parts[4]),
            )
        }
    }
}

/**
 * An Adblock Plus URL pattern: `||` host anchor, `|` start/end anchors, `*` wildcard, `^`
 * separator (anything but a letter, digit or `_-.%`, or the end of the URL). Matched by hand,
 * in place on the one [body] string: thousands of java.util.regex Patterns, or split parts,
 * would cost megabytes on a watch.
 */
internal class Pattern private constructor(private val flags: Int, private val body: String) {
    private val hostAnchor get() = flags and HOST != 0
    private val startAnchor get() = flags and START != 0
    private val endAnchor get() = flags and END != 0

    /** Candidate index tokens; computed once at load, not kept. */
    fun tokens(): List<String> = safeTokens(body, hostAnchor || startAnchor, endAnchor)

    fun matches(url: String, hostStart: Int, hostEnd: Int): Boolean {
        if (body.isEmpty()) return true
        if (hostAnchor) {
            if (matchFrom(url, hostStart, anchored = true)) return true
            for (i in hostStart until hostEnd) {
                if (url[i] == '.' && matchFrom(url, i + 1, anchored = true)) return true
            }
            return false
        }
        return matchFrom(url, 0, anchored = startAnchor)
    }

    /** Walks the `*`-separated segments of [body] left to right, each at its leftmost match. */
    private fun matchFrom(url: String, start: Int, anchored: Boolean): Boolean {
        var pos = start
        var seg = 0
        var first = anchored
        while (true) {
            val star = body.indexOf('*', seg).let { if (it < 0) body.length else it }
            val last = star == body.length
            if (star > seg) {
                when {
                    first -> {
                        pos = partAt(url, pos, body, seg, star)
                        if (pos < 0) return false
                        if (last && endAnchor) return pos == url.length
                    }
                    last && endAnchor -> {
                        // The last segment must end the URL: try it there, not leftmost.
                        for (s in maxOf(pos, url.length - (star - seg))..url.length) {
                            if (partAt(url, s, body, seg, star) == url.length) return true
                        }
                        return false
                    }
                    else -> {
                        pos = find(url, pos, seg, star)
                        if (pos < 0) return false
                    }
                }
            }
            first = false
            if (last) return true
            seg = star + 1
        }
    }

    /** End of the leftmost match of body[seg, end) at or after [from], or -1. */
    private fun find(url: String, from: Int, seg: Int, end: Int): Int {
        val c = body[seg]
        var i = from
        while (i <= url.length) {
            if (c != '^') {
                i = url.indexOf(c, i)
                if (i < 0) return -1
            }
            val e = partAt(url, i, body, seg, end)
            if (e >= 0) return e
            i++
        }
        return -1
    }

    companion object {
        private const val HOST = 1
        private const val START = 2
        private const val END = 4
        private val BAD_TOKENS = setOf("http", "https", "www", "com", "net", "org", "js", "html", "php", "png", "jpg", "gif", "css")

        fun parse(raw: String): Pattern {
            var p = raw
            var flags = 0
            if (p.startsWith("||")) {
                flags = HOST
                p = p.substring(2)
            } else if (p.startsWith("|")) {
                flags = START
                p = p.substring(1)
            }
            if (p.endsWith("|")) {
                flags = flags or END
                p = p.dropLast(1)
            }
            return Pattern(flags, p)
        }

        /**
         * Letter/digit runs in [p] that are whole tokens in any URL the pattern matches: bounded by
         * a literal separator or an anchor on both sides, never by `*` or the pattern's open ends.
         */
        internal fun safeTokens(p: String, anchoredStart: Boolean, anchoredEnd: Boolean): List<String> {
            val out = ArrayList<String>(4)
            var i = 0
            while (i < p.length) {
                if (!isTokenChar(p[i])) {
                    i++
                    continue
                }
                val s = i
                while (i < p.length && isTokenChar(p[i])) i++
                val before = if (s == 0) (if (anchoredStart) '|' else '*') else p[s - 1]
                val after = if (i == p.length) (if (anchoredEnd) '|' else '*') else p[i]
                if (before != '*' && after != '*' && i - s >= 2) {
                    val t = p.substring(s, i)
                    if (t !in BAD_TOKENS) out += t
                }
            }
            return out
        }

        internal fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9'

        private fun isSeparator(c: Char) =
            !(c in 'a'..'z' || c in '0'..'9' || c in 'A'..'Z' || c == '_' || c == '-' || c == '.' || c == '%')

        /** Matches p[from, to) at url[pos]; returns the end position, or -1. `^` may match the URL's end. */
        internal fun partAt(url: String, pos: Int, p: String, from: Int = 0, to: Int = p.length): Int {
            var u = pos
            for (k in from until to) {
                val c = p[k]
                if (c == '^') {
                    if (u == url.length) continue
                    if (!isSeparator(url[u])) return -1
                    u++
                } else {
                    if (u >= url.length || url[u] != c) return -1
                    u++
                }
            }
            return u
        }
    }
}

/**
 * Filters filed by token: parallel arrays sorted by the token's hash, so a URL's tokens are
 * hashed in place (no substring per token) and looked up by binary search. A hash collision
 * only means testing a filter that won't match. [noToken] holds the few with no usable token.
 */
internal class TokenIndex private constructor(
    private val hashes: LongArray,
    private val filters: Array<NetFilter>,
    private val noToken: Array<NetFilter>,
) {
    val size: Int get() = filters.size + noToken.size

    fun matches(r: Request): Boolean {
        for (f in noToken) if (f.matches(r)) return true
        if (hashes.isEmpty()) return false
        val u = r.url
        var i = 0
        while (i < u.length) {
            if (!Pattern.isTokenChar(u[i])) {
                i++
                continue
            }
            val s = i
            while (i < u.length && Pattern.isTokenChar(u[i])) i++
            val h = fnv(u, s, i)
            var k = hashes.binarySearch(h)
            if (k < 0) continue
            while (k > 0 && hashes[k - 1] == h) k--
            while (k < hashes.size && hashes[k] == h) {
                if (filters[k].matches(r)) return true
                k++
            }
        }
        return false
    }

    class Builder {
        private val keyed = ArrayList<Pair<Long, NetFilter>>()
        private val none = ArrayList<NetFilter>()

        fun add(token: String?, f: NetFilter) {
            if (token == null) none += f else keyed += fnv(token, 0, token.length) to f
        }

        fun build(): TokenIndex {
            keyed.sortBy { it.first }
            return TokenIndex(
                LongArray(keyed.size) { keyed[it].first },
                Array(keyed.size) { keyed[it].second },
                none.toTypedArray(),
            )
        }
    }
}

/** A growable LongArray, sorted once at the end. */
private class LongArrayBuilder {
    private var a = LongArray(1024)
    private var n = 0

    fun add(v: Long) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }

    fun sorted(): LongArray = a.copyOf(n).also { it.sort() }
}

private val NO_DOMAINS = emptyArray<String>()

private fun List<String>.toArrayOrEmpty(): Array<String> = if (isEmpty()) NO_DOMAINS else toTypedArray()
