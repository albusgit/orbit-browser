package com.albustech.orbit.data

import android.content.Context
import android.content.SharedPreferences
import com.albustech.orbit.browser.BrowserController
import com.albustech.orbit.browser.RenderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reader typography. Sizes are CSS px (= dp on the watch). */
data class ReaderStyle(
    val fontSize: Int = DEFAULT_FONT_SIZE,
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    val serif: Boolean = true,
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 15
        const val MIN_FONT_SIZE = 11
        const val MAX_FONT_SIZE = 26
        const val DEFAULT_LINE_HEIGHT = 1.4f
        val LINE_HEIGHTS = listOf(1.2f, 1.3f, 1.4f, 1.55f, 1.7f)
    }
}

data class Settings(
    val searchTemplate: String = BrowserController.DEFAULT_SEARCH_TEMPLATE,
    val textZoom: Int = DEFAULT_TEXT_ZOOM,
    val lastUrl: String? = null,
    val reader: ReaderStyle = ReaderStyle(),
    val keepScreenOn: Boolean = false,
    /** uBlock Origin on the watch's own engine, on top of Gecko's strict tracking protection. */
    val blockTrackers: Boolean = false,
    /** How sites left on Auto render: Mobile, Compact or Desktop (a site's own View still wins). */
    val defaultView: RenderMode = RenderMode.MOBILE,
    /** Pages are rendered by the phone helper when it's reachable. */
    val phonePages: Boolean = true,
    /** The launcher's "Turn bezel to pick" hint shows until the bezel is first used there. */
    val bezelHintSeen: Boolean = false,
) {
    companion object {
        const val DEFAULT_TEXT_ZOOM = 100
        const val MIN_TEXT_ZOOM = 70
        const val MAX_TEXT_ZOOM = 200
    }
}

/** Search engines offered in Settings. `%s` is replaced by the encoded query. */
enum class SearchEngine(val label: String, val template: String) {
    DUCKDUCKGO_HTML("DuckDuckGo", "https://html.duckduckgo.com/html/?q=%s"),
    DUCKDUCKGO_LITE("DuckDuckGo Lite", "https://lite.duckduckgo.com/lite/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    ;

    companion object {
        fun of(template: String): SearchEngine? = entries.firstOrNull { it.template == template }
    }
}

/**
 * App-wide settings on plain SharedPreferences: a handful of values doesn't need DataStore (and
 * its extra libraries and native code). Exposed as a StateFlow that updates on every change.
 * Per-site settings, bookmarks, history and positions live in Room.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    // Held in a field: SharedPreferences keeps listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val settings: StateFlow<Settings> = state.asStateFlow()

    fun setSearchTemplate(template: String) = edit { putString(SEARCH_TEMPLATE, template) }

    fun setTextZoom(percent: Int) =
        edit { putInt(TEXT_ZOOM, percent.coerceIn(Settings.MIN_TEXT_ZOOM, Settings.MAX_TEXT_ZOOM)) }

    fun setLastUrl(url: String) = edit { putString(LAST_URL, url) }

    fun setReaderStyle(style: ReaderStyle) = edit {
        putInt(READER_FONT_SIZE, style.fontSize.coerceIn(ReaderStyle.MIN_FONT_SIZE, ReaderStyle.MAX_FONT_SIZE))
        putFloat(READER_LINE_HEIGHT, style.lineHeight)
        putBoolean(READER_SERIF, style.serif)
    }

    fun setKeepScreenOn(on: Boolean) = edit { putBoolean(KEEP_SCREEN_ON, on) }

    fun setBlockTrackers(on: Boolean) = edit { putBoolean(BLOCK_TRACKERS, on) }

    fun setPhonePages(on: Boolean) = edit { putBoolean(PHONE_PAGES, on) }

    fun setDefaultView(view: RenderMode) = edit { putString(DEFAULT_VIEW, view.name) }

    fun setBezelHintSeen() {
        if (!state.value.bezelHintSeen) edit { putBoolean(BEZEL_HINT_SEEN, true) }
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    private fun read() = Settings(
        searchTemplate = prefs.getString(SEARCH_TEMPLATE, null) ?: BrowserController.DEFAULT_SEARCH_TEMPLATE,
        textZoom = prefs.getInt(TEXT_ZOOM, Settings.DEFAULT_TEXT_ZOOM),
        lastUrl = prefs.getString(LAST_URL, null),
        reader = ReaderStyle(
            fontSize = prefs.getInt(READER_FONT_SIZE, ReaderStyle.DEFAULT_FONT_SIZE),
            lineHeight = prefs.getFloat(READER_LINE_HEIGHT, ReaderStyle.DEFAULT_LINE_HEIGHT),
            serif = prefs.getBoolean(READER_SERIF, true),
        ),
        keepScreenOn = prefs.getBoolean(KEEP_SCREEN_ON, false),
        blockTrackers = prefs.getBoolean(BLOCK_TRACKERS, false),
        defaultView = prefs.getString(DEFAULT_VIEW, null)?.let { n -> RenderMode.VIEWS.firstOrNull { it.name == n } }
            // 0.3/0.4 had a Desktop sites switch.
            ?: if (prefs.getBoolean(DESKTOP_SITES, false)) RenderMode.ZOOM else RenderMode.MOBILE,
        bezelHintSeen = prefs.getBoolean(BEZEL_HINT_SEEN, false),
        phonePages = prefs.getBoolean(PHONE_PAGES, true),
    )

    private companion object {
        const val FILE = "settings"
        const val SEARCH_TEMPLATE = "search_template"
        const val TEXT_ZOOM = "text_zoom"
        const val LAST_URL = "last_url"
        const val READER_FONT_SIZE = "reader_font_size"
        const val READER_LINE_HEIGHT = "reader_line_height"
        const val READER_SERIF = "reader_serif"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        // 0.6.0 made uBlock opt-in: a new key, so the old default-on value is not carried over.
        const val BLOCK_TRACKERS = "ublock"
        const val DESKTOP_SITES = "desktop_sites"
        const val DEFAULT_VIEW = "default_view"
        const val BEZEL_HINT_SEEN = "bezel_hint_seen"
        const val PHONE_PAGES = "phone_pages"
    }
}
