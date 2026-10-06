package com.albustech.orbit.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Creates and configures Orbit's single WebView. */
object OrbitWebView {

    private const val TAG = "OrbitWebView"

    /** Smallest font the WebView will render, in CSS px. Keeps tiny page text legible on a watch. */
    const val MIN_FONT_SIZE = 12

    /** Returns null if the device has no usable WebView provider. */
    fun create(context: Context): WebView? =
        try {
            WebView(context).also { configure(it, context) }
        } catch (e: Exception) {
            // MissingWebViewPackageException is hidden API; it surfaces as a RuntimeException.
            Log.e(TAG, "WebView unavailable", e)
            null
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView, context: Context) {
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
            userAgentString = UserAgents.mobile(WebSettings.getDefaultUserAgent(context))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
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
