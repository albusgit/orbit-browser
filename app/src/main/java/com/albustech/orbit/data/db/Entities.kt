package com.albustech.orbit.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** What the user picked for a site. AUTO = Reader when the page is an article, else Round Scroll. */
enum class SiteMode { AUTO, READER, SCROLL, ZOOM }

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey val url: String,
    val title: String,
    val createdAt: Long,
)

@Entity(tableName = "history", indices = [Index("url"), Index("visitedAt")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String?,
    val visitedAt: Long,
)

/** Per-site choices, keyed by host without "www.". */
@Entity(tableName = "site_settings")
data class SiteSettings(
    @PrimaryKey val host: String,
    val mode: SiteMode = SiteMode.AUTO,
    val blockImages: Boolean = false,
    val javaScript: Boolean = true,
    val liteUserAgent: Boolean = false,
)

/**
 * Where the user was on a page. Reader mode stores an anchor (block, word) that survives
 * re-pagination; Round Scroll stores a fraction of the scroll range.
 */
@Entity(tableName = "reading_positions", indices = [Index("updatedAt")])
data class ReadingPosition(
    @PrimaryKey val url: String,
    val title: String?,
    val reader: Boolean,
    val page: Int = 0,
    val pageCount: Int = 0,
    val anchorBlock: Int = 0,
    val anchorWord: Int = 0,
    val scrollFraction: Float = 0f,
    val updatedAt: Long,
)

/** A saved tab: no live WebView, just its back/forward state on disk and a small snapshot. */
@Entity(tableName = "tabs")
data class TabRecord(
    @PrimaryKey val id: Long,
    val url: String?,
    val title: String?,
    val stateFile: String?,
    val snapshotFile: String?,
    val lastUsed: Long,
)
