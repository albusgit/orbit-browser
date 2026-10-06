package com.albustech.orbit.browser

/** Helpers for building JavaScript source from Kotlin values. */
object JsStrings {

    /** Quotes [s] as a JavaScript string literal (double quotes), safe to embed in a script. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 16).append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c") // never let "</script>" through
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** A finite number formatted for JS (no locale commas, no NaN). */
    fun number(v: Float): String = if (v.isFinite()) "%.3f".format(java.util.Locale.ROOT, v) else "0"
}
