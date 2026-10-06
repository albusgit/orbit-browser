package com.albustech.orbit.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import com.albustech.orbit.data.BrowserRepository
import com.albustech.orbit.data.ReaderStyle
import com.albustech.orbit.data.Suggestions
import com.albustech.orbit.data.db.ReadingPosition
import com.albustech.orbit.data.db.SiteMode
import com.albustech.orbit.data.db.SiteSettings
import com.albustech.orbit.input.WebViewScroller
import com.albustech.orbit.reader.Anchor
import com.albustech.orbit.reader.Article
import com.albustech.orbit.reader.ReaderTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** What the controller needs from the Activity. */
interface BrowserHost {
    /** The renderer died; this WebView is unusable and must be replaced. */
    fun onRendererGone(url: String?)

    /** Hands a URL the watch can't handle (mailto:, tel:, downloads) to the phone. */
    fun openOnPhone(url: String)

    fun toast(message: String)

    /** A bezel turn hit the first/last page or the end of the page. */
    fun onEdge()

    /** A page finished loading: remember it for the home screen. */
    fun onPageCommitted(url: String)

    /** Reader typography changed from the bezel (zoom mode); persist it. */
    fun onReaderStyleChanged(style: ReaderStyle)
}

/**
 * Owns the single WebView and everything that happens in it, and exposes state to Compose.
 * Runs on the main thread, apart from shouldInterceptRequest, which only reads thread-safe state.
 *
 * Reader flow: a page loads in Round Scroll; if its site is AUTO or READER, extract.js runs
 * Readability and posts the article back; the reader page is then loaded with
 * loadDataWithBaseURL(articleUrl#orbit-reader). History becomes [… A, A-reader]; Back from
 * the reader skips A (which would only re-open the reader).
 */
class BrowserController(
    val webView: WebView,
    private val injector: Injector,
    private val repo: BrowserRepository,
    private val scope: CoroutineScope,
    private val memoryLog: MemoryLog,
    private val connection: ConnectionMonitor,
    private val host: BrowserHost,
) {
    // ------------------------------------------------------------ observable state

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
    var error by mutableStateOf<PageError?>(null)
        private set

    /** False until the first navigation (and after Home); the home screen shows instead. */
    var hasPage by mutableStateOf(false)
        private set

    var site by mutableStateOf(SiteSettings(host = ""))
        private set

    /** Non-null while a reader page is showing: the article's own URL. */
    var readerUrl by mutableStateOf<String?>(null)
        private set
    var reader by mutableStateOf<ReaderProgress?>(null)
        private set

    val renderMode: RenderMode
        get() = when {
            readerUrl != null -> RenderMode.READER
            site.mode == SiteMode.ZOOM -> RenderMode.ZOOM
            else -> RenderMode.SCROLL
        }

    var bezelMode by mutableStateOf(BezelMode.SCROLL)
        private set
    var focusedLink by mutableStateOf<LinkFocus?>(null)
        private set

    /** 0..1 position in a scrolling page, for the edge arc. */
    var scrollFraction by mutableFloatStateOf(0f)
        private set
    var blockedCount by mutableIntStateOf(0)
        private set

    // ---------------------------------------------------------------- settings in

    var searchTemplate: String = DEFAULT_SEARCH_TEMPLATE
    var readerStyle: ReaderStyle = ReaderStyle()
        set(value) {
            if (value == field) return
            field = value
            if (readerUrl != null) webView.evaluateJavascript(ReaderTemplate.setStyleJs(value), null)
        }

    // ------------------------------------------------------------------- internals

    private val main = Handler(Looper.getMainLooper())
    private val scroller = WebViewScroller(webView)
    private val mobileUa = UserAgents.mobile(WebSettings.getDefaultUserAgent(webView.context))
    private val siteCache = HashMap<String, SiteSettings>()
    private val blockedCounter = AtomicInteger()
    @Volatile private var blocklist: Blocklist? = null
    @Volatile private var blockingOn = true

    private var appliedSiteKey: String? = null
    private var pendingExtract: String? = null
    private var expectingReader: String? = null
    private var readerTitle: String? = null
    private var lastArticle: Article? = null
    private var linksActive = false
    private var clearHistoryOnNextLoad = false
    private var positionJob: Job? = null
    private var zoomStyleJob: Job? = null
    private var pendingFontSize: Int? = null
    private val readerAnchors = HashMap<String, Anchor>()

    private val watchdog = Runnable {
        if (isLoading) {
            Log.w(TAG, "Load stalled at $progress% on ${connection.current()}")
            webView.stopLoading()
            error = PageError(url.orEmpty(), PageError.Kind.TIMEOUT, "", connection.current())
        }
    }

    init {
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.setDownloadListener { downloadUrl, _, _, _, _ -> host.openOnPhone(downloadUrl) }
        webView.setOnScrollChangeListener { _, _, y, _, _ -> onScrolled(y) }
        Bridge(::onMessage).attach(webView)
        applySite(SiteSettings(host = ""))
        scope.launch {
            repo.allSiteSettings().forEach { siteCache[it.host] = it }
            blocklist = withContext(Dispatchers.IO) {
                webView.context.assets.open("blocklist.txt").bufferedReader().use { Blocklist.parse(it.readText()) }
            }
        }
    }

    // ------------------------------------------------------------------ navigation

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
        applySiteFor(target)
        webView.loadUrl(target)
    }

    /** Opens [target] as the start of a fresh history (a tab switch or a new tab). */
    fun loadFresh(target: String?) {
        if (target == null) {
            showHome()
            webView.loadUrl("about:blank")
            clearHistoryOnNextLoad = true
            url = null
            title = null
            hasPage = false
        } else {
            clearHistoryOnNextLoad = true
            loadUrl(target)
        }
    }

    /** Back, skipping an article's source page when leaving its reader view. False at the start. */
    fun back(): Boolean {
        if (linksActive) {
            setBezel(BezelMode.SCROLL)
            return true
        }
        val list = webView.copyBackForwardList()
        val i = list.currentIndex
        if (readerUrl != null && i >= 1 && sameDoc(list.getItemAtIndex(i - 1)?.url, readerUrl)) {
            if (i < 2) return false
            webView.goBackOrForward(-2)
            return true
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    fun forward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reloadOrStop() {
        if (isLoading) webView.stopLoading() else reload()
    }

    fun reload() {
        error = null
        val article = lastArticle
        if (readerUrl != null && article != null) showReader(article) else webView.reload()
    }

    fun showHome() {
        webView.stopLoading()
        setBezel(BezelMode.SCROLL)
        error = null
        hasPage = false
    }

    /** Back to the page from the home screen, if there is one. */
    fun resumePage(): Boolean {
        if (url == null) return false
        hasPage = true
        return true
    }

    // ----------------------------------------------------------------- modes

    /** Picks Reader, Round Scroll or Zoom for this site and remembers it. */
    fun setSiteMode(mode: SiteMode) {
        val pageUrl = readerUrl ?: url ?: return
        val key = Suggestions.siteKey(pageUrl) ?: return
        saveSite(site.copy(host = key, mode = mode))
        val source = readerUrl
        val wantsReader = mode == SiteMode.READER || mode == SiteMode.AUTO
        when {
            source != null && !wantsReader -> {
                // Leave the reader for the original page, re-using its entry if it's right behind us.
                val list = webView.copyBackForwardList()
                val i = list.currentIndex
                appliedSiteKey = null
                applySiteFor(source)
                if (i >= 1 && sameDoc(list.getItemAtIndex(i - 1)?.url, source)) webView.goBack() else webView.loadUrl(source)
            }
            source != null -> Unit // already reading
            wantsReader && !injectorWasZoom() -> requestExtract(pageUrl, force = true)
            else -> {
                appliedSiteKey = null
                applySiteFor(pageUrl)
                webView.reload()
            }
        }
    }

    private var lastAppliedRender = RenderMode.SCROLL
    private fun injectorWasZoom() = lastAppliedRender == RenderMode.ZOOM

    /** Changes a per-site toggle (images, JavaScript, lite UA) and reloads to apply it. */
    fun updateSite(transform: (SiteSettings) -> SiteSettings) {
        val pageUrl = readerUrl ?: url ?: return
        val key = Suggestions.siteKey(pageUrl) ?: return
        val updated = transform(site.copy(host = key))
        saveSite(updated)
        applySite(updated)
        if (readerUrl == null && hasPage) webView.reload()
    }

    private fun saveSite(s: SiteSettings) {
        siteCache[s.host] = s
        site = s
        scope.launch { repo.saveSiteSettings(s) }
    }

    private fun applySiteFor(target: String?) {
        val key = Suggestions.siteKey(target)
        if (key != null && key == appliedSiteKey) return
        appliedSiteKey = key
        applySite(key?.let { siteCache[it] ?: SiteSettings(host = it) } ?: SiteSettings(host = ""))
    }

    private fun applySite(s: SiteSettings) {
        site = s
        val settings = webView.settings
        settings.javaScriptEnabled = s.javaScript
        settings.blockNetworkImage = s.blockImages
        settings.userAgentString = if (s.liteUserAgent) UserAgents.LITE else mobileUa
        val mode = if (s.mode == SiteMode.ZOOM) RenderMode.ZOOM else RenderMode.SCROLL
        lastAppliedRender = mode
        injector.setRoundLayout(webView, mode == RenderMode.SCROLL)
        OrbitWebView.applyMode(webView, mode)
    }

    // ----------------------------------------------------------------- bezel

    /** Routes bezel detents according to the bezel mode and the render mode. */
    fun onBezel(detents: Int, stepPx: Int, eventTimeMs: Long) {
        when (bezelMode) {
            BezelMode.SCROLL -> if (readerUrl != null) {
                webView.evaluateJavascript("window.orbitReader&&orbitReader.turn($detents)", null)
            } else {
                scroller.onDetents(detents, stepPx, eventTimeMs)
            }
            BezelMode.LINKS -> {
                val dir = sign(detents.toFloat()).toInt()
                repeat(abs(detents)) { webView.evaluateJavascript("window.orbitLinks&&orbitLinks.step($dir)", null) }
            }
            BezelMode.ZOOM -> if (readerUrl != null) {
                val size = (displayedFontSize + detents).coerceIn(ReaderStyle.MIN_FONT_SIZE, ReaderStyle.MAX_FONT_SIZE)
                pendingFontSize = size
                // Re-paginating on every click would stutter: wait for the bezel to settle.
                zoomStyleJob?.cancel()
                zoomStyleJob = scope.launch {
                    delay(350)
                    val style = readerStyle.copy(fontSize = size)
                    pendingFontSize = null
                    readerStyle = style
                    host.onReaderStyleChanged(style)
                }
            } else {
                webView.zoomBy(ZOOM_STEP.pow(detents).coerceIn(0.02f, 50f))
            }
        }
    }

    /** Reader font size including a bezel change that hasn't settled yet (for the label). */
    val displayedFontSize: Int get() = pendingFontSize ?: readerStyle.fontSize

    fun stopScrolling() = scroller.stop()

    fun cycleBezelMode(): BezelMode {
        setBezel(bezelMode.next())
        return bezelMode
    }

    fun setBezel(mode: BezelMode) {
        if (linksActive && mode != BezelMode.LINKS) {
            webView.evaluateJavascript("window.orbitLinks&&orbitLinks.stop()", null)
            linksActive = false
            focusedLink = null
        }
        bezelMode = mode
        if (mode == BezelMode.LINKS && !linksActive) {
            if (!site.javaScript && readerUrl == null) {
                host.toast("Link focus needs JavaScript on this site")
                bezelMode = BezelMode.SCROLL
                return
            }
            linksActive = true
            injector.startLinks(webView)
            webView.evaluateJavascript("window.orbitLinks&&orbitLinks.step(1)", null)
        }
    }

    /**
     * Zoom view: WebView's double-tap fits the tapped block to the full width, which on a round
     * screen puts its line ends under the rim. Once that zoom has settled, scale it back so the
     * block fits the inscribed square (the square is 1/√2 of the width).
     */
    fun onDoubleTap() {
        if (renderMode != RenderMode.ZOOM) return
        @Suppress("DEPRECATION")
        val before = webView.scale
        main.postDelayed({
            @Suppress("DEPRECATION")
            if (webView.scale > before * 1.05f) webView.zoomBy(SQUARE_FIT)
        }, DOUBLE_TAP_SETTLE_MS)
    }

    fun activateFocusedLink() {
        webView.evaluateJavascript("window.orbitLinks&&orbitLinks.activate()", null)
    }

    // ------------------------------------------------------------------- reader

    private fun requestExtract(pageUrl: String, force: Boolean) {
        if (!site.javaScript) {
            if (force) host.toast("Reader needs JavaScript on this site")
            return
        }
        pendingExtract = pageUrl
        injector.extract(webView, force)
    }

    private fun showReader(article: Article) {
        scope.launch {
            val saved = repo.position(article.url)
            val anchor = readerAnchors[key(article.url)]
                ?: saved?.takeIf { it.reader }?.let { Anchor(it.anchorBlock, it.anchorWord) }
            val html = withContext(Dispatchers.Default) { injector.readerPage(article, readerStyle, anchor) }
            if (!sameDoc(url, article.url)) return@launch // the user moved on meanwhile
            lastArticle = article
            expectingReader = article.url
            readerTitle = article.title
            // The history entry carries the marker too, so Back/Forward onto it is recognised
            // whichever URL the WebView reports for a data page.
            val base = ReaderTemplate.baseUrl(article.url)
            webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
        }
    }

    private fun onArticle(msg: JSONObject) {
        val requested = pendingExtract ?: return
        pendingExtract = null
        if (!sameDoc(requested, url) || readerUrl != null) return
        if (!msg.optBoolean("ok")) {
            if (site.mode == SiteMode.READER) host.toast("No article found on this page")
            return
        }
        val article = Article(
            url = requested,
            title = msg.optString("title"),
            byline = msg.optString("byline"),
            siteName = msg.optString("siteName"),
            lang = msg.optString("lang"),
            dir = msg.optString("dir"),
            words = msg.optInt("words"),
            content = msg.optString("content"),
        )
        if (article.content.isNotBlank()) showReader(article)
    }

    private fun onReaderMessage(msg: JSONObject) {
        val article = readerUrl ?: run {
            // Arrived on a reader page through history: recognise it by its marker.
            val current = webView.url ?: return
            if (!current.endsWith(ReaderTemplate.MARKER)) return
            current.removeSuffix(ReaderTemplate.MARKER).also { readerUrl = it }
        }
        val event = msg.optString("event")
        val page = msg.optInt("page")
        val total = msg.optInt("total")
        reader = ReaderProgress(page, total, msg.optBoolean("done"))
        val anchor = msg.optJSONObject("anchor")?.let { Anchor(it.optInt("u"), it.optInt("w")) }
        when (event) {
            "ready" -> {
                val known = readerAnchors[key(article)]
                if (known != null && known != anchor) {
                    webView.evaluateJavascript("window.orbitReader&&orbitReader.goToAnchor({u:${known.unit},w:${known.word}})", null)
                }
                if (readerTitle == null) readerTitle = webView.title
                title = readerTitle
                scope.launch { repo.recordVisit(article, readerTitle) }
                host.onPageCommitted(article)
            }
            "edge" -> host.onEdge()
        }
        if (anchor != null && (event == "page")) {
            readerAnchors[key(article)] = anchor
            savePositionSoon(
                ReadingPosition(
                    url = article,
                    title = readerTitle,
                    reader = true,
                    page = page,
                    pageCount = total,
                    anchorBlock = anchor.unit,
                    anchorWord = anchor.word,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun onLinksMessage(msg: JSONObject) {
        when (msg.optString("event")) {
            "focus" -> focusedLink = LinkFocus(msg.optString("href"), msg.optString("label"), msg.optString("kind"))
            "activate" -> if (msg.optString("kind") == "input") webView.requestFocus() // keyboard needs focus
            "none" -> focusedLink = null
            "edge" -> host.onEdge()
        }
    }

    private fun onMessage(type: String, msg: JSONObject) {
        logBridge(type, msg)
        when (type) {
            "article" -> onArticle(msg)
            "reader" -> onReaderMessage(msg)
            "links" -> onLinksMessage(msg)
        }
    }

    // ---------------------------------------------------------------- positions

    private fun onScrolled(y: Int) {
        if (readerUrl != null || !hasPage) return
        @Suppress("DEPRECATION")
        val max = (webView.contentHeight * webView.scale).roundToInt() - webView.height
        scrollFraction = if (max > 0) (y.toFloat() / max).coerceIn(0f, 1f) else 0f
        val pageUrl = url ?: return
        if (isLoading) return
        savePositionSoon(
            ReadingPosition(
                url = pageUrl,
                title = title,
                reader = false,
                scrollFraction = scrollFraction,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun savePositionSoon(position: ReadingPosition) {
        positionJob?.cancel()
        positionJob = scope.launch {
            delay(1200)
            repo.savePosition(position)
        }
    }

    private fun restoreScroll(pageUrl: String) {
        scope.launch {
            val pos = repo.position(pageUrl) ?: return@launch
            if (pos.reader || pos.scrollFraction < 0.01f) return@launch
            delay(250)
            // Back/forward restore their own scroll; only act on a fresh load at the top.
            if (!sameDoc(url, pageUrl) || webView.scrollY > 0) return@launch
            val f = JsStrings.number(pos.scrollFraction)
            if (site.javaScript) {
                webView.evaluateJavascript(
                    "window.scrollTo(0,$f*Math.max(0,document.documentElement.scrollHeight-innerHeight))",
                    null,
                )
            } else {
                @Suppress("DEPRECATION")
                val max = (webView.contentHeight * webView.scale).roundToInt() - webView.height
                webView.scrollTo(0, (pos.scrollFraction * max).roundToInt())
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun refreshNav() {
        val list = webView.copyBackForwardList()
        val i = list.currentIndex
        canGoBack = if (readerUrl != null && i >= 1 && sameDoc(list.getItemAtIndex(i - 1)?.url, readerUrl)) {
            i >= 2
        } else {
            webView.canGoBack()
        }
        canGoForward = webView.canGoForward()
    }

    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        if (isLoading) main.postDelayed(watchdog, connection.current().stallTimeoutMs)
    }

    private fun key(u: String) = BrowserRepository.normalize(u.removeSuffix(ReaderTemplate.MARKER))

    private fun sameDoc(a: String?, b: String?): Boolean = a != null && b != null && key(a) == key(b)

    fun saveState(out: Bundle) {
        webView.saveState(out)
    }

    fun restoreState(saved: Bundle): Boolean {
        val list = webView.restoreState(saved) ?: return false
        if (list.size == 0) return false
        hasPage = true
        url = list.currentItem?.url?.removeSuffix(ReaderTemplate.MARKER)
        title = list.currentItem?.title
        refreshNav()
        return true
    }

    /** Stops timers and animations (Activity stopped or ambient). */
    fun pause() {
        main.removeCallbacks(watchdog)
        scroller.stop()
    }

    fun resume() {
        armWatchdog()
    }

    /** Applies the blocking setting; read on the network thread in shouldInterceptRequest. */
    fun setBlocking(on: Boolean) {
        blockingOn = on
    }

    // ----------------------------------------------------------------- clients

    // Lint misses the onRenderProcessGone override below when the base is WebViewClientCompat.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClientCompat() {

        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
            val reading = expectingReader
                ?: pageUrl?.takeIf { it.endsWith(ReaderTemplate.MARKER) }?.removeSuffix(ReaderTemplate.MARKER)
            expectingReader = null
            readerUrl = reading
            if (reading == null) {
                reader = null
                readerTitle = null
                applySiteFor(pageUrl)
            }
            isLoading = true
            progress = 0
            error = null
            url = reading ?: pageUrl
            scrollFraction = 0f
            blockedCounter.set(0)
            blockedCount = 0
            focusedLink = null
            linksActive = false
            bezelMode = if (reading == null && site.mode == SiteMode.ZOOM) BezelMode.ZOOM else BezelMode.SCROLL
            pendingExtract = null
            scroller.stop()
            refreshNav()
            armWatchdog()
        }

        override fun onPageCommitVisible(view: WebView, pageUrl: String) {
            injector.injectLate(view)
        }

        override fun onPageFinished(view: WebView, pageUrl: String?) {
            isLoading = false
            progress = 100
            main.removeCallbacks(watchdog)
            if (clearHistoryOnNextLoad) {
                clearHistoryOnNextLoad = false
                view.clearHistory()
            }
            refreshNav()
            memoryLog.onPageLoaded(pageUrl)
            if (readerUrl != null || pageUrl == null || error != null || !pageUrl.startsWith("http")) return
            injector.injectLate(view)
            host.onPageCommitted(pageUrl)
            scope.launch { repo.recordVisit(pageUrl, view.title) }
            if (site.mode == SiteMode.AUTO || site.mode == SiteMode.READER) {
                requestExtract(pageUrl, force = site.mode == SiteMode.READER)
            }
            restoreScroll(pageUrl)
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String?, isReload: Boolean) {
            if (readerUrl == null && pageUrl?.startsWith("http") == true) url = pageUrl
            refreshNav()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    if (request.isForMainFrame) applySiteFor(uri.toString())
                    return false
                }
                "about", "data", "blob" -> return false
                "intent" -> {
                    // intent://…#Intent;…;S.browser_fallback_url=…;end: follow the web fallback.
                    val fallback = FALLBACK.find(uri.toString())?.groupValues?.get(1)?.let(Uri::decode)
                    if (fallback != null && fallback.startsWith("http")) view.loadUrl(fallback)
                    return true
                }
                "mailto", "tel", "sms", "geo", "market" -> {
                    host.openOnPhone(uri.toString())
                    return true
                }
            }
            Log.i(TAG, "Blocked navigation to $uri")
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!blockingOn || request.isForMainFrame) return null
            val list = blocklist ?: return null
            if (!list.isBlocked(request.url.host)) return null
            val n = blockedCounter.incrementAndGet()
            main.post { blockedCount = n }
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceErrorCompat) {
            if (!request.isForMainFrame) return
            val code = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) err.errorCode else 0
            val detail = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
                err.description.toString()
            } else {
                "Network error"
            }
            val conn = connection.current()
            val kind = if (conn == ConnectionType.NONE) PageError.Kind.OFFLINE else PageError.kindFor(code)
            error = PageError(request.url.toString(), kind, detail, conn)
        }

        @SuppressLint("WebViewClientOnReceivedSslError") // Always cancels: never proceeds past a bad certificate.
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
            handler.cancel()
            if (sameDoc(sslError.url, url)) {
                error = PageError(sslError.url, PageError.Kind.SECURITY, "", connection.current())
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // On a 2 GB watch the system may kill the renderer to reclaim memory. Returning
            // true keeps the app alive; the WebView itself can't be reused.
            Log.w(TAG, "Renderer gone (crash=${detail.didCrash()}) at $url")
            host.onRendererGone(readerUrl ?: url)
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress != progress) {
                progress = newProgress
                armWatchdog()
            }
        }

        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            title = if (readerUrl != null) readerTitle ?: newTitle else newTitle
        }
    }

    companion object {
        private const val TAG = "OrbitBrowser"
        const val DEFAULT_SEARCH_TEMPLATE = "https://html.duckduckgo.com/html/?q=%s"
        private const val ZOOM_STEP = 1.12f
        private const val SQUARE_FIT = 0.7071f
        private const val DOUBLE_TAP_SETTLE_MS = 450L
        private val FALLBACK = Regex("S\\.browser_fallback_url=([^;]+)")

        fun hostOf(url: String?): String? = Suggestions.siteKey(url)
    }
}
