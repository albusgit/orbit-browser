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
 * uBlock Origin. Created on first use (the first page the watch itself renders), never in
 * Application.onCreate: Gecko's content processes start the Application too.
 *
 * uBlock Origin is opt-in ("Stronger blocking"): its filter engine costs seconds of start-up and
 * memory on a watch, and Gecko's strict tracking protection already blocks the worst.
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
    private var blockingWanted = false
    private var ublockInstalling = false
    private var fontSizeFactor = 1f
    private val ublockListeners = ArrayList<(WebExtension) -> Unit>()

    fun get(context: Context): GeckoRuntime = runtime ?: create(context.applicationContext).also { runtime = it }

    /** True once Gecko is up. Until then nothing of it is loaded. */
    val isCreated: Boolean get() = runtime != null

    /** Text size for web pages; kept until the runtime exists. */
    fun setFontSizeFactor(factor: Float) {
        fontSizeFactor = factor
        runtime?.settings?.fontSizeFactor = factor
    }

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
            // A 2 GB watch: one content process for all sites (no site isolation), extensions in
            // the main process, and Gecko frees caches when Android says memory is low.
            .fissionEnabled(false)
            .extensionsProcessEnabled(false)
            .lowMemoryDetection(true)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    // No Safe Browsing lookups. Firefox's strict tracker protection: trackers,
                    // social trackers, fingerprinters and cryptominers never load.
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .build(),
            )
            .fontSizeFactor(fontSizeFactor)
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
        if (blockingWanted) {
            installUblock(rt)
        } else {
            // Installed by an earlier version or an earlier "on": make sure it stays off.
            controller.list().accept({ all -> all?.firstOrNull { it.id == UBLOCK_ID }?.let(::onUblockReady) }, { e -> Log.w(TAG, "Extension list failed", e) })
        }
        return rt
    }

    private fun installUblock(rt: GeckoRuntime) {
        if (ublockInstalling) return
        ublockInstalling = true
        rt.webExtensionController.ensureBuiltIn(UBLOCK_LOCATION, UBLOCK_ID).accept(
            { ext ->
                Log.i(TAG, "uBlock Origin ready (${ext?.metaData?.version})")
                ext?.let(::onUblockReady)
            },
            { e ->
                ublockInstalling = false
                Log.e(TAG, "uBlock Origin failed to install", e)
            },
        )
    }

    private fun onUblockReady(ext: WebExtension) {
        ublock = ext
        runtime?.let(::applyBlocking)
        ublockListeners.forEach { it(ext) }
    }

    /** Calls [listener] with uBlock Origin now if it's installed, or once it is. */
    fun onUblock(listener: (WebExtension) -> Unit) {
        ublock?.let(listener) ?: ublockListeners.add(listener)
    }

    /** The "Stronger blocking" setting: uBlock Origin on or off. Never starts Gecko by itself. */
    fun setBlocking(on: Boolean) {
        blockingWanted = on
        val rt = runtime ?: return
        if (on && ublock == null) installUblock(rt) else applyBlocking(rt)
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
