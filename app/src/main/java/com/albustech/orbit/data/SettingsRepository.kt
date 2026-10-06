package com.albustech.orbit.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.albustech.orbit.browser.BrowserController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    val blockTrackers: Boolean = true,
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

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** App-wide settings. Per-site settings, bookmarks, history and positions live in Room. */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            searchTemplate = p[SEARCH_TEMPLATE] ?: BrowserController.DEFAULT_SEARCH_TEMPLATE,
            textZoom = p[TEXT_ZOOM] ?: Settings.DEFAULT_TEXT_ZOOM,
            lastUrl = p[LAST_URL],
            reader = ReaderStyle(
                fontSize = p[READER_FONT_SIZE] ?: ReaderStyle.DEFAULT_FONT_SIZE,
                lineHeight = p[READER_LINE_HEIGHT] ?: ReaderStyle.DEFAULT_LINE_HEIGHT,
                serif = p[READER_SERIF] ?: true,
            ),
            keepScreenOn = p[KEEP_SCREEN_ON] ?: false,
            blockTrackers = p[BLOCK_TRACKERS] ?: true,
        )
    }

    suspend fun setSearchTemplate(template: String) = set(SEARCH_TEMPLATE, template)

    suspend fun setTextZoom(percent: Int) =
        set(TEXT_ZOOM, percent.coerceIn(Settings.MIN_TEXT_ZOOM, Settings.MAX_TEXT_ZOOM))

    suspend fun setLastUrl(url: String) = set(LAST_URL, url)

    suspend fun setReaderStyle(style: ReaderStyle) {
        store.edit {
            it[READER_FONT_SIZE] = style.fontSize.coerceIn(ReaderStyle.MIN_FONT_SIZE, ReaderStyle.MAX_FONT_SIZE)
            it[READER_LINE_HEIGHT] = style.lineHeight
            it[READER_SERIF] = style.serif
        }
    }

    suspend fun setKeepScreenOn(on: Boolean) = set(KEEP_SCREEN_ON, on)

    suspend fun setBlockTrackers(on: Boolean) = set(BLOCK_TRACKERS, on)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private companion object {
        val SEARCH_TEMPLATE = stringPreferencesKey("search_template")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
        val LAST_URL = stringPreferencesKey("last_url")
        val READER_FONT_SIZE = intPreferencesKey("reader_font_size")
        val READER_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val READER_SERIF = booleanPreferencesKey("reader_serif")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BLOCK_TRACKERS = booleanPreferencesKey("block_trackers")
    }
}
