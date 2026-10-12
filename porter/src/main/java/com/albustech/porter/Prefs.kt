package com.albustech.porter

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("porter", Context.MODE_PRIVATE)

    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) = p.edit { putString("host", v) }

    var port: Int
        get() = p.getInt("port", 0)
        set(v) = p.edit { putInt("port", v) }

    /** Set once a connection has worked, which hides the pairing card. */
    var paired: Boolean
        get() = p.getBoolean("paired", false)
        set(v) = p.edit { putBoolean("paired", v) }

    /** The watch's screen timeout while porting; 0 leaves it alone. */
    var timeoutMs: Int
        get() = p.getInt("timeoutMs", 10 * 60_000)
        set(v) = p.edit { putInt("timeoutMs", v) }

    /** The watch's own timeout, kept until it has been put back (survives Porter being killed). */
    var savedTimeoutMs: Int
        get() = p.getInt("savedTimeoutMs", 0)
        set(v) = p.edit(commit = true) { putInt("savedTimeoutMs", v) }
}
