package com.albustech.orbit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Bookmark::class, HistoryEntry::class, SiteSettings::class, ReadingPosition::class, TabRecord::class],
    version = 1,
    exportSchema = true,
)
abstract class OrbitDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
    abstract fun history(): HistoryDao
    abstract fun siteSettings(): SiteSettingsDao
    abstract fun positions(): PositionDao
    abstract fun tabs(): TabDao

    companion object {
        fun create(context: Context): OrbitDatabase =
            Room.databaseBuilder(context.applicationContext, OrbitDatabase::class.java, "orbit.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
