package com.albustech.orbit.browser.filters

import com.albustech.orbit.browser.Domains

/**
 * Element hiding (EasyList's `##` rules) from assets/filters/cosmetic.txt.
 *
 * ~13k generic selectors can't be pushed into every page: style recalculation would crawl on
 * a watch. Like uBlock Origin, cosmetic.js reports the ids and classes a page actually uses and
 * gets back only the generic rules filed under them, plus the page's own site-specific rules
 * and the few hundred generic rules that have no id or class to file them under.
 *
 * Memory: the file stays one (Latin-1, so one byte a character) string, and the indexes are
 * sorted hash/offset arrays into it: about 1 MB in all, against ~6 MB as rule objects. A rule's
 * selector is only cut out of the text when a page needs it.
 */
class Cosmetics private constructor(
    private val text: String,
    private val generic: Index,
    private val always: IntArray,
    private val specific: Index,
    private val exceptions: Index,
) {
    val size: Int get() = generic.size + always.size + specific.size

    /** First answer for a frame on [host]: its site-specific rules and the unkeyed generic ones. */
    fun siteCss(host: String, pageFlags: Int): String {
        if (pageFlags and (Types.DOCUMENT or Types.ELEMHIDE) != 0) return ""
        val h = host.lowercase()
        val allowed = allowedFor(h)
        val out = StringBuilder()
        val seen = HashSet<Int>()
        forEachLabel(h) { label ->
            specific.forEach(text, label) { line -> if (seen.add(line)) siteRule(line, h, allowed, out) }
        }
        if (pageFlags and Types.GENERICHIDE == 0) for (line in always) genericRule(line, h, allowed, out)
        return out.toString()
    }

    /** Generic rules for the [ids] and [classes] a frame on [host] has reported. */
    fun genericCss(host: String, pageFlags: Int, ids: Iterable<String>, classes: Iterable<String>): String {
        if (pageFlags and (Types.DOCUMENT or Types.ELEMHIDE or Types.GENERICHIDE) != 0) return ""
        val h = host.lowercase()
        val allowed = allowedFor(h)
        val out = StringBuilder()
        for (id in ids) generic.forEach(text, "#$id") { genericRule(it, h, allowed, out) }
        for (c in classes) generic.forEach(text, ".$c") { genericRule(it, h, allowed, out) }
        return out.toString()
    }

    /** Selectors this site turns off with `#@#`. */
    private fun allowedFor(host: String): Set<String> {
        var out: HashSet<String>? = null
        forEachLabel(host) { label ->
            exceptions.forEach(text, label) { line ->
                val tab = text.indexOf('\t', line)
                if (appliesTo(line, tab, host)) (out ?: HashSet<String>().also { out = it }).add(text.substring(tab + 1, lineEnd(tab)))
            }
        }
        return out ?: emptySet()
    }

    /** `key \t excluded|sites \t selector` */
    private fun genericRule(line: Int, host: String, allowed: Set<String>, out: StringBuilder) {
        val t1 = text.indexOf('\t', line)
        val t2 = text.indexOf('\t', t1 + 1)
        if (t2 > t1 + 1 && anyWithin(host, t1 + 1, t2)) return
        val end = lineEnd(t2)
        if (allowed.isNotEmpty() && text.substring(t2 + 1, end) in allowed) return
        out.append(text, t2 + 1, end).append(RULE)
    }

    /** `sites \t selector` */
    private fun siteRule(line: Int, host: String, allowed: Set<String>, out: StringBuilder) {
        val tab = text.indexOf('\t', line)
        if (!appliesTo(line, tab, host)) return
        val end = lineEnd(tab)
        if (allowed.isNotEmpty() && text.substring(tab + 1, end) in allowed) return
        out.append(text, tab + 1, end).append(RULE)
    }

    /** "a.com|~b.a.com" in text[from, to): [host] is within an included site and no excluded one. */
    private fun appliesTo(from: Int, to: Int, host: String): Boolean {
        var included = false
        var s = from
        while (s < to) {
            val bar = text.indexOf('|', s).let { if (it < 0 || it > to) to else it }
            if (text[s] == '~') {
                if (within(host, s + 1, bar)) return false
            } else if (!included && within(host, s, bar)) {
                included = true
            }
            s = bar + 1
        }
        return included
    }

    private fun anyWithin(host: String, from: Int, to: Int): Boolean {
        var s = from
        while (s < to) {
            val bar = text.indexOf('|', s).let { if (it < 0 || it > to) to else it }
            if (within(host, s, bar)) return true
            s = bar + 1
        }
        return false
    }

    /** [host] is text[from, to) or a subdomain of it. */
    private fun within(host: String, from: Int, to: Int): Boolean {
        val len = to - from
        if (host.length < len || !host.regionMatches(host.length - len, text, from, len)) return false
        return host.length == len || host[host.length - len - 1] == '.'
    }

    private fun lineEnd(from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    /**
     * Lines filed under a key (a class/id or a site), as parallel arrays sorted by the key's
     * hash. The key itself is checked against the text, so a hash collision costs nothing.
     */
    private class Index(private val hashes: LongArray, private val lines: IntArray, private val keyEnds: IntArray) {
        val size: Int get() = hashes.size

        inline fun forEach(text: String, key: String, block: (Int) -> Unit) {
            val h = fnv(key, 0, key.length)
            var i = hashes.binarySearch(h)
            if (i < 0) return
            while (i > 0 && hashes[i - 1] == h) i--
            while (i < hashes.size && hashes[i] == h) {
                val start = keyEnds[i] - key.length
                if (start >= 0 && text.regionMatches(start, key, 0, key.length)) block(lines[i])
                i++
            }
        }

        class Builder {
            private val entries = ArrayList<LongArray>()

            /** Files the line at [line] under text[keyStart, keyEnd). */
            fun add(text: String, keyStart: Int, keyEnd: Int, line: Int) {
                entries += longArrayOf(fnv(text, keyStart, keyEnd), line.toLong(), keyEnd.toLong())
            }

            fun build(): Index {
                entries.sortBy { it[0] }
                return Index(
                    LongArray(entries.size) { entries[it][0] },
                    IntArray(entries.size) { entries[it][1].toInt() },
                    IntArray(entries.size) { entries[it][2].toInt() },
                )
            }
        }
    }

    companion object {
        private const val RULE = "{display:none!important}\n"

        /** Builds the index from cosmetic.txt. Call off the main thread. */
        fun parse(text: String): Cosmetics {
            val generic = Index.Builder()
            val always = ArrayList<Int>()
            val specific = Index.Builder()
            val exceptions = Index.Builder()
            var section = ""
            var start = 0
            while (start < text.length) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                if (end > start && text[start] != '!') {
                    val tab = text.indexOf('\t', start).let { if (it < 0 || it > end) -1 else it }
                    when {
                        tab < 0 -> section = text.substring(start, end) // "#generic", "#specific", "#exceptions"
                        section == "#generic" -> if (tab == start) always += start else generic.add(text, start, tab, start)
                        section == "#specific" || section == "#exceptions" -> {
                            val target = if (section == "#specific") specific else exceptions
                            // One entry per included site in "a.com|~b.a.com".
                            var s = start
                            while (s < tab) {
                                val bar = text.indexOf('|', s).let { if (it < 0 || it > tab) tab else it }
                                if (text[s] != '~' && bar > s) target.add(text, s, bar, start)
                                s = bar + 1
                            }
                        }
                    }
                }
                start = end + 1
            }
            return Cosmetics(text, generic.build(), always.toIntArray(), specific.build(), exceptions.build())
        }

        /** "a.b.example.com" → itself, "b.example.com", "example.com" (never the bare TLD). */
        private inline fun forEachLabel(host: String, block: (String) -> Unit) {
            var h = host
            while (true) {
                block(h)
                val dot = h.indexOf('.')
                if (dot < 0 || h.indexOf('.', dot + 1) < 0) return
                h = h.substring(dot + 1)
            }
        }
    }
}

/** FNV-1a over s[from, to). */
internal fun fnv(s: CharSequence, from: Int, to: Int): Long {
    var h = -0x340d631b7bdddcdbL
    for (i in from until to) {
        h = h xor s[i].code.toLong()
        h *= 0x100000001b3L
    }
    return h
}
