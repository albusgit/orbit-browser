package com.albustech.orbit.claude

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * The one GeckoRuntime (Gecko allows one per process) with the app's built-in extension
 * (assets/extensions/claude). Its profile keeps claude.ai's sign-in cookies between launches.
 * Created on first use from the Activity: Gecko's content processes start the Application too.
 */
object ClaudeRuntime {

    private const val TAG = "ClaudeGecko"
    const val EXTENSION_ID = "claude@albustech.com"
    private const val EXTENSION_LOCATION = "resource://android/assets/extensions/claude/"

    private var runtime: GeckoRuntime? = null

    /** The app's end of the extension; outlives Activities, like the runtime. */
    val bridge = ClaudeBridge()

    fun get(context: Context): GeckoRuntime = runtime ?: create(context.applicationContext).also { runtime = it }

    private fun create(context: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            // Debug builds: inspect pages from desktop Firefox (about:debugging) over ADB.
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .consoleOutput(BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
            .automaticFontSizeAdjustment(false)
            .loginAutofillEnabled(false)
            .webManifest(false)
            .inputAutoZoomEnabled(false)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    // Standard protection, as in Firefox: strict would break Google's sign-in popup.
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                    .build(),
            )
            .build()
        val rt = GeckoRuntime.create(context, settings)
        Log.i(TAG, "Runtime created")
        rt.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept(
            { ext ->
                Log.i(TAG, "Extension ready")
                ext?.let(bridge::attach)
            },
            { e -> Log.e(TAG, "Extension failed to install", e) },
        )
        return rt
    }
}
