package com.albustech.orbit

import android.app.Application
import android.webkit.WebView
import com.albustech.orbit.data.BrowserRepository
import com.albustech.orbit.data.SettingsRepository
import com.albustech.orbit.data.db.OrbitDatabase

/** Process-wide singletons, shared by the Activity, the Tile and the complication. */
class OrbitApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val database: OrbitDatabase by lazy { OrbitDatabase.create(this) }
    val repository: BrowserRepository by lazy { BrowserRepository(database) }

    override fun onCreate() {
        super.onCreate()
        // Inspect pages from a desktop Chrome at chrome://inspect.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }
}
