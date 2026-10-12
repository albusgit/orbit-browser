package com.albustech.orbit.phone

import android.content.Context
import com.albustech.orbit.browser.filters.Cosmetics
import com.albustech.orbit.browser.filters.FilterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * EasyList + EasyPrivacy for the pages the phone renders (assets/filters, compiled by
 * tools/filters/compile.mjs): ads and trackers never load, so they never reach the watch either.
 */
object Blocking {
    /** Read on WebView's network threads. Null until loaded: the first page may load unfiltered. */
    @Volatile
    var filters: FilterEngine? = null
        private set

    @Volatile
    var cosmetics: Cosmetics? = null
        private set

    private var started = false

    fun load(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        val assets = context.applicationContext.assets
        // ~100k rules: indexed off the main thread.
        scope.launch(Dispatchers.Default) {
            filters = assets.open("filters/network.txt").bufferedReader().use { FilterEngine.parse(it.readText()) }
            cosmetics = assets.open("filters/cosmetic.txt").bufferedReader().use { Cosmetics.parse(it.readText()) }
        }
    }

    /** Adds to today's count, shown in the setup screen. */
    fun addToToday(context: Context, n: Int) {
        if (n <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today()
        val base = if (prefs.getLong(DAY, -1) == day) prefs.getInt(COUNT, 0) else 0
        prefs.edit().putLong(DAY, day).putInt(COUNT, base + n).apply()
    }

    fun blockedToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getLong(DAY, -1) == today()) prefs.getInt(COUNT, 0) else 0
    }

    private fun today(): Long = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())

    private const val PREFS = "blocking"
    private const val DAY = "day"
    private const val COUNT = "count"
}
