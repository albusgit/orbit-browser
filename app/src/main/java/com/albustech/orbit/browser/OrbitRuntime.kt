package com.albustech.orbit.browser

import android.content.Context
import android.util.Log
import com.albustech.orbit.BuildConfig
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * The one GeckoRuntime (Gecko allows one per process), with Orbit's two built-in extensions:
 * Orbit's own (round layout, reader, link focus, search cards: see assets/extensions/orbit) and
 * uBlock Origin. Created on first use from the Activity, never in Application.onCreate: Gecko's
 * content processes start the Application too.
 */
object OrbitRuntime {

    private const val TAG = "OrbitGecko"
    const val ORBIT_ID = "orbit@albustech.com"
    const val UBLOCK_ID = "uBlock0@raymondhill.net"
    private const val ORBIT_LOCATION = "resource://android/assets/extensions/orbit/"
    private const val UBLOCK_LOCATION = "resource://android/assets/extensions/ublock/"

    private var runtime: GeckoRuntime? = null

    /** The app's end of the Orbit extension; outlives Activities, like the runtime. */
    val extension = OrbitExtension()

    /** uBlock Origin once installed; null until then or if it failed to install. */
    var ublock: WebExtension? = null
        private set
    private var blockingWanted = true
    private val ublockListeners = ArrayList<(WebExtension) -> Unit>()

    fun get(context: Context): GeckoRuntime = runtime ?: create(context.applicationContext).also { runtime = it }

    private fun create(context: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            // Debug builds: inspect pages from desktop Firefox (about:debugging) over ADB.
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .automaticFontSizeAdjustment(false)
            .loginAutofillEnabled(false)
            .webManifest(false)
            .doubleTapZoomingEnabled(true)
            .inputAutoZoomEnabled(false)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    // No Safe Browsing lookups; Firefox's own tracker protection stays at standard.
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                    .build(),
            )
            .build()
        val rt = GeckoRuntime.create(context, settings)
        Log.i(TAG, "Runtime created")
        val controller = rt.webExtensionController
        controller.ensureBuiltIn(ORBIT_LOCATION, ORBIT_ID).accept(
            { ext ->
                Log.i(TAG, "Orbit extension ready")
                ext?.let(extension::attach)
            },
            { e -> Log.e(TAG, "Orbit extension failed to install", e) },
        )
        controller.ensureBuiltIn(UBLOCK_LOCATION, UBLOCK_ID).accept(
            { ext ->
                Log.i(TAG, "uBlock Origin ready (${ext?.metaData?.version})")
                ublock = ext
                if (ext != null) {
                    applyBlocking(rt)
                    ublockListeners.forEach { it(ext) }
                }
            },
            { e -> Log.e(TAG, "uBlock Origin failed to install", e) },
        )
        return rt
    }

    /** Calls [listener] with uBlock Origin now if it's installed, or once it is. */
    fun onUblock(listener: (WebExtension) -> Unit) {
        ublock?.let(listener) ?: ublockListeners.add(listener)
    }

    /** The "Block ads and trackers" setting: uBlock Origin on or off. */
    fun setBlocking(context: Context, on: Boolean) {
        blockingWanted = on
        applyBlocking(get(context))
    }

    private fun applyBlocking(rt: GeckoRuntime) {
        val ext = ublock ?: return
        val enabled = ext.metaData.enabled
        if (enabled == blockingWanted) return
        val controller = rt.webExtensionController
        val result = if (blockingWanted) {
            controller.enable(ext, WebExtensionController.EnableSource.USER)
        } else {
            controller.disable(ext, WebExtensionController.EnableSource.USER)
        }
        result.accept({ updated -> if (updated != null) ublock = updated }, { e -> Log.e(TAG, "uBlock toggle failed", e) })
    }
}
