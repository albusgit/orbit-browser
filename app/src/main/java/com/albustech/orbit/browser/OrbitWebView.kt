package com.albustech.orbit.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** The outcome of creating Orbit's WebView. */
sealed interface WebViewStart {
    class Ready(val webView: WebView) : WebViewStart
    class Failed(val problem: WebViewProblem) : WebViewStart
}

/** Creates and configures Orbit's single WebView. */
object OrbitWebView {

    private const val TAG = "OrbitWebView"

    /** Smallest font the WebView will render, in CSS px. Keeps tiny page text legible on a watch. */
    const val MIN_FONT_SIZE = 12

    /**
     * Creates the WebView, or says why it can't. Only the constructor can fail this: a setting
     * that throws while configuring is logged and skipped, so it never looks like a missing
     * WebView.
     */
    fun create(context: Context): WebViewStart {
        val webView = try {
            WebView(context)
        } catch (e: Exception) {
            // MissingWebViewPackageException is hidden API; it surfaces as a RuntimeException.
            Log.e(TAG, "WebView unavailable", e)
            return WebViewStart.Failed(diagnose(context, e))
        }
        configure(webView, context)
        return WebViewStart.Ready(webView)
    }

    private fun diagnose(context: Context, error: Throwable): WebViewProblem {
        val pm = context.packageManager
        val current = try {
            WebViewCompat.getCurrentWebViewPackage(context)
        } catch (_: Exception) {
            null
        }?.let { info(pm, it.packageName) }
        val candidates = WebViewDiagnosis.PROVIDERS.map { info(pm, it) }
        return WebViewDiagnosis.diagnose(current, candidates, error).also { Log.e(TAG, "Diagnosis: $it") }
    }

    private fun info(pm: PackageManager, pkg: String): ProviderInfo =
        try {
            val p = pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
            ProviderInfo(pkg, installed = true, enabled = p.applicationInfo?.enabled != false, versionName = p.versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            ProviderInfo(pkg, installed = false, enabled = false)
        }

    /** Runs one piece of setup; if this WebView build rejects it, Orbit carries on without it. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Skipped WebView setting: $what", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView, context: Context) {
        safely("settings") {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = false
                minimumFontSize = MIN_FONT_SIZE
                minimumLogicalFontSize = MIN_FONT_SIZE
            }
        }
        safely("user agent") { webView.settings.userAgentString = UserAgents.mobile(WebSettings.getDefaultUserAgent(context)) }
        // Safe Browsing stays off (the manifest opts out too): each check is a lookup Orbit
        // doesn't want on a watch's link, and the interstitials don't fit a round screen.
        safely("safe browsing") {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, false)
            }
        }
        safely("darkening") {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
            }
        }
        webView.setBackgroundColor(Color.BLACK)
        // Orbit draws its own position arc; the stock scrollbar sits under the rim anyway.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    /** Applies the per-mode layout settings. The caller reloads afterwards. */
    fun applyMode(webView: WebView, mode: RenderMode) {
        webView.settings.loadWithOverviewMode = mode == RenderMode.ZOOM
    }
}
