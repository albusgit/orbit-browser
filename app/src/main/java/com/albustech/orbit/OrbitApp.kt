package com.albustech.orbit

import android.app.Application
import com.albustech.orbit.data.BrowserRepository
import com.albustech.orbit.data.SettingsRepository
import com.albustech.orbit.data.db.OrbitDatabase

/**
 * Process-wide singletons, shared by the Activity, the Tile and the complication. Gecko's
 * runtime is not here: its content processes start this Application too (see OrbitRuntime).
 */
class OrbitApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val database: OrbitDatabase by lazy { OrbitDatabase.create(this) }
    val repository: BrowserRepository by lazy { BrowserRepository(database) }
}
