package com.albustech.orbit.browser

/**
 * The bundled ad/tracker host list (assets/blocklist.txt). A host is blocked if it, or any
 * parent domain, is listed. Lookups walk the labels of the host: a handful of HashSet hits
 * per request, cheap enough for shouldInterceptRequest on a watch.
 */
class Blocklist(private val hosts: Set<String>) {

    val size: Int get() = hosts.size

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false // never match a bare TLD
        }
    }

    companion object {
        fun parse(text: String): Blocklist =
            Blocklist(
                text.lineSequence()
                    .map { it.substringBefore('#').trim().lowercase() }
                    .filter { it.isNotEmpty() && it.contains('.') }
                    .toHashSet(),
            )
    }
}
