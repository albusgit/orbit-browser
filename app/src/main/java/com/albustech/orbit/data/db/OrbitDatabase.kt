package com.albustech.orbit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Bookmark::class, HistoryEntry::class, SiteSettings::class, ReadingPosition::class, TabRecord::class],
    version = 2,
    exportSchema = true,
)
abstract class OrbitDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
    abstract fun history(): HistoryDao
    abstract fun siteSettings(): SiteSettingsDao
    abstract fun positions(): PositionDao
    abstract fun tabs(): TabDao

    companion object {
        /** 2: per-site WebGL opt-in. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE site_settings ADD COLUMN richGraphics INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): OrbitDatabase =
            Room.databaseBuilder(context.applicationContext, OrbitDatabase::class.java, "orbit.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
