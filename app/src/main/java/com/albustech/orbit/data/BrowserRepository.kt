package com.albustech.orbit.data

import com.albustech.orbit.data.db.Bookmark
import com.albustech.orbit.data.db.HistoryEntry
import com.albustech.orbit.data.db.OrbitDatabase
import com.albustech.orbit.data.db.ReadingPosition
import com.albustech.orbit.data.db.SiteSettings
import kotlinx.coroutines.flow.Flow

/** Bookmarks, history, per-site settings and reading positions. */
class BrowserRepository(private val db: OrbitDatabase) {

    val bookmarks: Flow<List<Bookmark>> = db.bookmarks().observeAll()
    val history: Flow<List<HistoryEntry>> = db.history().observeRecent(HISTORY_SHOWN)
    val latestPosition: Flow<ReadingPosition?> = db.positions().observeLatest()

    fun isBookmarked(url: String): Flow<Boolean> = db.bookmarks().observeIsBookmarked(url)

    suspend fun recentBookmarks(limit: Int): List<Bookmark> = db.bookmarks().recent(limit)

    suspend fun toggleBookmark(url: String, title: String?, bookmarked: Boolean) {
        if (bookmarked) {
            db.bookmarks().delete(url)
        } else {
            db.bookmarks().upsert(Bookmark(url, title?.ifBlank { null } ?: Suggestions.displayUrl(url), System.currentTimeMillis()))
        }
    }

    suspend fun addBookmark(url: String, title: String?) = toggleBookmark(url, title, bookmarked = false)

    suspend fun removeBookmark(url: String) = db.bookmarks().delete(url)

    /** Records a visit; a reload or same-page title update doesn't add a new row. */
    suspend fun recordVisit(url: String, title: String?) {
        val dao = db.history()
        val last = dao.latest()
        if (last != null && last.url == url) {
            if (!title.isNullOrBlank() && title != last.title) dao.updateTitle(last.id, title)
            return
        }
        dao.insert(HistoryEntry(url = url, title = title, visitedAt = System.currentTimeMillis()))
        dao.prune(HISTORY_KEPT)
    }

    suspend fun clearHistory() = db.history().clear()

    suspend fun suggestions(query: String): List<Suggestion> {
        val q = Suggestions.likeEscape(query.trim())
        if (q.isEmpty()) return emptyList()
        val b = db.bookmarks().search(q, 6).map { it.url to it.title }
        val h = db.history().search(q, 10).map { it.url to it.title }
        return Suggestions.rank(query, b, h)
    }

    suspend fun siteSettings(url: String?): SiteSettings {
        val key = Suggestions.siteKey(url) ?: return SiteSettings(host = "")
        return db.siteSettings().get(key) ?: SiteSettings(host = key)
    }

    suspend fun allSiteSettings(): List<SiteSettings> = db.siteSettings().all()

    suspend fun saveSiteSettings(settings: SiteSettings) {
        if (settings.host.isNotEmpty()) db.siteSettings().upsert(settings)
    }

    suspend fun position(url: String): ReadingPosition? = db.positions().get(normalize(url))

    suspend fun latestPosition(): ReadingPosition? = db.positions().latest()

    suspend fun savePosition(position: ReadingPosition) {
        db.positions().upsert(position.copy(url = normalize(position.url)))
        db.positions().prune(POSITIONS_KEPT)
    }

    companion object {
        const val HISTORY_KEPT = 500
        const val HISTORY_SHOWN = 100
        const val POSITIONS_KEPT = 300

        /** Positions are per page, not per in-page anchor. */
        fun normalize(url: String): String = url.substringBefore('#')
    }
}
