package com.albustech.orbit

import android.app.Application
import android.webkit.WebView
import com.albustech.orbit.data.SettingsRepository

class OrbitApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // Inspect pages from a desktop Chrome at chrome://inspect.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }
}
