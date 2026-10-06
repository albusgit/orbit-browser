package com.albustech.orbit.browser

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.albustech.orbit.ui.RoundGeometry

/**
 * Loads the injected assets once and installs them into the WebView.
 *
 * Preferred path: a document-start script, so the round layout is in place before first paint.
 * Fallback for WebViews without DOCUMENT_START_SCRIPT: [injectLate] from page callbacks.
 */
class Injector(context: Context, private val geometry: RoundGeometry) {

    private val assets = context.applicationContext.assets
    private val roundCss: String by lazy { readAsset("round.css") }
    private val roundJs: String by lazy { readAsset("round.js") }

    private var handle: ScriptHandler? = null
    private var mode: RenderMode = RenderMode.SCROLL

    private val usesDocumentStart: Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** (Re)installs the scripts for [newMode]. Takes effect on the next navigation. */
    fun install(webView: WebView, newMode: RenderMode) {
        mode = newMode
        handle?.remove()
        handle = null
        val script = scriptFor(newMode) ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handle = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
    }

    /** Fallback when document-start scripts are unsupported. Safe to call repeatedly. */
    fun injectLate(webView: WebView) {
        if (usesDocumentStart) return
        val script = scriptFor(mode) ?: return
        webView.evaluateJavascript(script, null)
    }

    private fun scriptFor(mode: RenderMode): String? = when (mode) {
        RenderMode.SCROLL -> buildString {
            append("window.__orbit={d:").append(JsStrings.number(geometry.diameterCss))
            append(",sq:").append(JsStrings.number(geometry.squareCss))
            append(",inset:").append(JsStrings.number(geometry.insetCss)).append("};\n")
            append("window.__orbitCss=").append(JsStrings.quote(roundCss)).append(";\n")
            append(roundJs)
        }
        RenderMode.DESKTOP -> null
    }

    private fun readAsset(name: String): String =
        try {
            assets.open(name).bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Missing asset $name", e)
            ""
        }

    private companion object {
        const val TAG = "OrbitInjector"
    }
}
