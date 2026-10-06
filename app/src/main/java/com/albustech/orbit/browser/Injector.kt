package com.albustech.orbit.browser

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.albustech.orbit.data.ReaderStyle
import com.albustech.orbit.reader.Anchor
import com.albustech.orbit.reader.Article
import com.albustech.orbit.reader.ReaderTemplate
import com.albustech.orbit.ui.RoundGeometry
import java.security.SecureRandom

/**
 * Loads the injected assets once and gets them into pages.
 *
 * - round.js/round.css: a document-start script (layout in place before first paint), with
 *   an evaluateJavascript fallback for WebViews without DOCUMENT_START_SCRIPT.
 * - Readability + extract.js: evaluated after load, only when Reader might be wanted, so
 *   ordinary pages don't pay for parsing 90 KB of JS.
 * - links.js: evaluated when link-focus mode starts.
 * - reader.html/css/js: assembled by [ReaderTemplate] into the reader page.
 * - cosmetic.js: a document-start script in every frame that asks for element-hiding rules.
 * - WebGL guard: a document-start script that refuses WebGL contexts except on opted-in sites.
 */
class Injector(context: Context, private val geometry: RoundGeometry) {

    private val assets = context.applicationContext.assets
    private val roundCss by lazy { readAsset("round.css") }
    private val roundJs by lazy { readAsset("round.js") }
    private val extractJs by lazy {
        listOf("Readability-readerable.js", "Readability.js", "extract.js").joinToString("\n") { readAsset(it) }
    }
    private val linksJs by lazy { readAsset("links.js") }
    private val serpJs by lazy { readAsset("serp.js") }
    private val cosmeticJs by lazy { readAsset("cosmetic.js") }
    private val readerTemplate by lazy { readAsset("reader.html") }
    private val readerCss by lazy { readAsset("reader.css") }
    private val readerJs by lazy { readAsset("reader.js") }
    private val random = SecureRandom()

    private var handle: ScriptHandler? = null
    private var webGlHandle: ScriptHandler? = null
    private var webGlAllowed: Set<String>? = null
    private var roundInstalled: Boolean? = null

    private val documentStart: Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** Turns the round layout on or off for upcoming navigations. */
    fun setRoundLayout(webView: WebView, enabled: Boolean) {
        if (roundInstalled == enabled) return
        roundInstalled = enabled
        handle?.remove()
        handle = null
        if (enabled && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handle = WebViewCompat.addDocumentStartJavaScript(webView, roundScript(), setOf("*"))
        }
    }

    /**
     * WebGL off by default: there is no WebView setting for it, so pages are refused a WebGL
     * context before their own scripts run, and fall back to 2D or skip the effect. That keeps
     * the GPU process and its buffers from spinning up on a watch, and removes a fingerprinting
     * surface. [allowed] are site keys ("example.com") opted in under This site → Rich graphics;
     * their subdomains are included.
     */
    fun setWebGlAllowed(webView: WebView, allowed: Set<String>) {
        if (allowed == webGlAllowed || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        webGlAllowed = allowed
        webGlHandle?.remove()
        webGlHandle = WebViewCompat.addDocumentStartJavaScript(webView, webGlGuard(allowed), setOf("*"))
    }

    /** Element hiding in every frame from document start (cosmetic.js, answered by Cosmetics). */
    fun installCosmetics(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(webView, cosmeticJs, setOf("*"))
    }

    /** Fallback path when document-start scripts are unsupported. Idempotent in the page. */
    fun injectLate(webView: WebView) {
        if (documentStart || roundInstalled != true) return
        webView.evaluateJavascript(roundScript(), null)
    }

    /** Runs Readability on the loaded page; the result arrives as an 'article' message. */
    fun extract(webView: WebView, force: Boolean) {
        webView.evaluateJavascript("(function(){var orbitForce=$force;\n$extractJs\n})();", null)
    }

    /** Reads the results off a search page; they arrive as a 'serp' message. */
    fun extractSerp(webView: WebView) {
        webView.evaluateJavascript(serpJs, null)
    }

    fun startLinks(webView: WebView) {
        webView.evaluateJavascript(linksJs, null)
    }

    fun readerPage(article: Article, style: ReaderStyle, anchor: Anchor?): String =
        ReaderTemplate.build(
            template = readerTemplate,
            css = readerCss,
            script = readerJs,
            article = article,
            diameterCss = geometry.diameterCss,
            squareCss = geometry.squareCss,
            insetCss = geometry.insetCss,
            style = style,
            anchor = anchor,
            nonce = nonce(),
        )

    private fun roundScript(): String = buildString {
        append("window.__orbit={d:").append(JsStrings.number(geometry.diameterCss))
        append(",sq:").append(JsStrings.number(geometry.squareCss))
        append(",inset:").append(JsStrings.number(geometry.insetCss)).append("};\n")
        append("window.__orbitCss=").append(JsStrings.quote(roundCss)).append(";\n")
        append(roundJs)
    }

    private fun webGlGuard(allowed: Set<String>): String = buildString {
        append("(function(){var h=location.hostname,a=[")
        append(allowed.sorted().joinToString(",") { JsStrings.quote(it) })
        append("];for(var i=0;i<a.length;i++){if(h===a[i]||h.slice(-a[i].length-1)==='.'+a[i])return;}")
        append("var re=/^(experimental-)?webgl2?$/i;")
        append("function guard(p){if(!p)return;var g=p.getContext;")
        append("p.getContext=function(t){return typeof t==='string'&&re.test(t)?null:g.apply(this,arguments);};}")
        append("guard(window.HTMLCanvasElement&&HTMLCanvasElement.prototype);")
        append("guard(window.OffscreenCanvas&&OffscreenCanvas.prototype);})();")
    }

    private fun nonce(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
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
