package com.albustech.orbit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Bookmark>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun observeIsBookmarked(url: String): Flow<Boolean>

    @Query("SELECT * FROM bookmarks WHERE url LIKE '%' || :q || '%' ESCAPE '\\' OR title LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<Bookmark>

    @Upsert
    suspend fun upsert(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 1")
    suspend fun latest(): HistoryEntry?

    /** Distinct URLs matching [q], most recent visit first. */
    @Query(
        "SELECT id, url, title, MAX(visitedAt) AS visitedAt FROM history " +
            "WHERE url LIKE '%' || :q || '%' ESCAPE '\\' OR title LIKE '%' || :q || '%' ESCAPE '\\' " +
            "GROUP BY url ORDER BY visitedAt DESC LIMIT :limit",
    )
    suspend fun search(q: String, limit: Int): List<HistoryEntry>

    @Insert
    suspend fun insert(entry: HistoryEntry)

    @Query("UPDATE history SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visitedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface SiteSettingsDao {
    @Query("SELECT * FROM site_settings WHERE host = :host")
    suspend fun get(host: String): SiteSettings?

    @Query("SELECT * FROM site_settings")
    suspend fun all(): List<SiteSettings>

    @Upsert
    suspend fun upsert(settings: SiteSettings)
}

@Dao
interface PositionDao {
    @Query("SELECT * FROM reading_positions WHERE url = :url")
    suspend fun get(url: String): ReadingPosition?

    @Query("SELECT * FROM reading_positions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): ReadingPosition?

    @Query("SELECT * FROM reading_positions ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<ReadingPosition?>

    @Upsert
    suspend fun upsert(position: ReadingPosition)

    @Query("DELETE FROM reading_positions WHERE url NOT IN (SELECT url FROM reading_positions ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY lastUsed DESC")
    fun observeAll(): Flow<List<TabRecord>>

    @Query("SELECT * FROM tabs ORDER BY lastUsed DESC")
    suspend fun all(): List<TabRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(tab: TabRecord)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: Long)
}
