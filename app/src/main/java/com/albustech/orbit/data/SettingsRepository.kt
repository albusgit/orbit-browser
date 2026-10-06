package com.albustech.orbit.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.albustech.orbit.browser.BrowserController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val searchTemplate: String = BrowserController.DEFAULT_SEARCH_TEMPLATE,
    val textZoom: Int = DEFAULT_TEXT_ZOOM,
    val lastUrl: String? = null,
) {
    companion object {
        const val DEFAULT_TEXT_ZOOM = 100
    }
}

/** Search engines offered in Settings (Phase 4 UI). `%s` is replaced by the encoded query. */
enum class SearchEngine(val label: String, val template: String) {
    DUCKDUCKGO_HTML("DuckDuckGo", "https://html.duckduckgo.com/html/?q=%s"),
    DUCKDUCKGO_LITE("DuckDuckGo Lite", "https://lite.duckduckgo.com/lite/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** App-wide settings. Per-site settings, bookmarks and history move to Room in Phase 4. */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            searchTemplate = p[SEARCH_TEMPLATE] ?: BrowserController.DEFAULT_SEARCH_TEMPLATE,
            textZoom = p[TEXT_ZOOM] ?: Settings.DEFAULT_TEXT_ZOOM,
            lastUrl = p[LAST_URL],
        )
    }

    suspend fun setSearchTemplate(template: String) = set(SEARCH_TEMPLATE, template)

    suspend fun setTextZoom(percent: Int) = set(TEXT_ZOOM, percent.coerceIn(50, 300))

    suspend fun setLastUrl(url: String) = set(LAST_URL, url)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private companion object {
        val SEARCH_TEMPLATE = stringPreferencesKey("search_template")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
        val LAST_URL = stringPreferencesKey("last_url")
    }
}
