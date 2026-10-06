package com.albustech.orbit.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature

/** A main-frame load failure, shown as Orbit's own error screen. */
data class PageError(val url: String, val description: String)

/**
 * Owns the single WebView and exposes its state to Compose.
 * Everything here runs on the main thread.
 */
class BrowserController(
    val webView: WebView,
    private val injector: Injector,
    private val memoryLog: MemoryLog,
    private val onPageCommitted: (url: String) -> Unit,
    /** The renderer died (crash or low-memory kill). This WebView is unusable; the owner must replace it. */
    private val onRendererGone: (url: String?, crashed: Boolean) -> Unit,
) {
    var url by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var progress by mutableIntStateOf(100)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var mode by mutableStateOf(RenderMode.SCROLL)
        private set
    var error by mutableStateOf<PageError?>(null)
        private set

    /** False until the first navigation; the home screen shows instead of the WebView. */
    var hasPage by mutableStateOf(false)
        private set

    var searchTemplate: String = DEFAULT_SEARCH_TEMPLATE

    init {
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        injector.install(webView, mode)
        OrbitWebView.applyMode(webView, mode)
    }

    /** Loads what the user typed or said. Returns false for blank input. */
    fun open(input: String): Boolean {
        val target = UrlResolver.resolve(input, searchTemplate) ?: return false
        loadUrl(target)
        return true
    }

    fun loadUrl(target: String) {
        error = null
        hasPage = true
        url = target
        webView.loadUrl(target)
    }

    fun back() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun forward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reloadOrStop() {
        if (isLoading) webView.stopLoading() else reload()
    }

    fun reload() {
        error = null
        webView.reload()
    }

    fun switchMode(newMode: RenderMode) {
        if (newMode == mode) return
        mode = newMode
        injector.install(webView, newMode)
        OrbitWebView.applyMode(webView, newMode)
        if (hasPage) reload()
    }

    fun setTextZoom(percent: Int) {
        webView.settings.textZoom = percent
    }

    /** Leaves the page for the home screen. History is kept, so Back still works from the page. */
    fun showHome() {
        webView.stopLoading()
        error = null
        hasPage = false
    }

    fun saveState(out: Bundle) {
        webView.saveState(out)
    }

    fun restoreState(saved: Bundle): Boolean {
        val list = webView.restoreState(saved) ?: return false
        if (list.size == 0) return false
        hasPage = true
        url = list.currentItem?.url
        title = list.currentItem?.title
        refreshNav()
        return true
    }

    private fun refreshNav() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    // Lint misses the onRenderProcessGone override below when the base is WebViewClientCompat.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClientCompat() {
        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
            isLoading = true
            progress = 0
            url = pageUrl
            refreshNav()
        }

        override fun onPageCommitVisible(view: WebView, pageUrl: String) {
            injector.injectLate(view)
        }

        override fun onPageFinished(view: WebView, pageUrl: String?) {
            isLoading = false
            progress = 100
            refreshNav()
            injector.injectLate(view)
            memoryLog.onPageLoaded(pageUrl)
            if (pageUrl != null && error == null) onPageCommitted(pageUrl)
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String?, isReload: Boolean) {
            url = pageUrl
            refreshNav()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "about") return false
            // intent:, mailto:, tel:, market: … have nowhere to go on a watch browser yet.
            // Phase 4 hands them to the phone.
            Log.i(TAG, "Blocked navigation to ${request.url}")
            return true
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            err: WebResourceErrorCompat,
        ) {
            if (!request.isForMainFrame) return
            val description =
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
                    err.description.toString()
                } else {
                    "Network error"
                }
            error = PageError(request.url.toString(), description)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // On a 2 GB watch the system may kill the renderer to reclaim memory. Returning
            // true keeps the app alive; the WebView itself can't be reused.
            Log.w(TAG, "Renderer gone (crash=${detail.didCrash()}) at $url")
            onRendererGone(url, detail.didCrash())
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            title = newTitle
        }
    }

    companion object {
        private const val TAG = "OrbitBrowser"
        const val DEFAULT_SEARCH_TEMPLATE = "https://html.duckduckgo.com/html/?q=%s"

        fun hostOf(url: String?): String? = url?.let { it.toUri().host?.removePrefix("www.") }
    }
}
