package com.albustech.orbit.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.albustech.orbit.data.db.OrbitDatabase
import com.albustech.orbit.data.db.SiteMode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Builds a version-1 database from the exported schema, then opens it with the app's real
 * builder: Room runs the migrations and validates the result against the current schema, so a
 * bad migration fails here instead of wiping a user's data via the destructive fallback.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun `site settings survive the move to version 2`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("orbit.db").also { it.parentFile?.mkdirs(); it.delete() }
        val schema = JSONObject(File("schemas/com.albustech.orbit.data.db.OrbitDatabase/1.json").readText())
            .getJSONObject("database")

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (k in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO site_settings (host, mode, blockImages, javaScript, liteUserAgent) " +
                    "VALUES ('example.com', 'READER', 1, 0, 1)",
            )
            db.version = 1
        }

        val db = OrbitDatabase.create(context)
        val site = db.siteSettings().get("example.com")!!
        assertEquals(SiteMode.READER, site.mode)
        assertEquals(true, site.blockImages)
        assertEquals(false, site.javaScript)
        assertEquals(true, site.liteUserAgent)
        assertFalse(site.richGraphics)
        db.close()
    }
}
