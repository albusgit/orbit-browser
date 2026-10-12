package com.albustech.orbit.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.albustech.orbit.BuildConfig
import com.albustech.orbit.data.BrowserRepository
import com.albustech.orbit.data.ReaderStyle
import com.albustech.orbit.data.Suggestions
import com.albustech.orbit.data.db.ReadingPosition
import com.albustech.orbit.data.db.SiteMode
import com.albustech.orbit.data.db.SiteSettings
import com.albustech.orbit.input.GeckoScroller
import com.albustech.orbit.link.Box
import com.albustech.orbit.link.FieldFocus
import com.albustech.orbit.link.Input
import com.albustech.orbit.link.OpenRequest
import com.albustech.orbit.link.PageMeta
import com.albustech.orbit.link.Paths
import com.albustech.orbit.link.Tile
import com.albustech.orbit.link.View as PhoneView
import com.albustech.orbit.reader.Anchor
import com.albustech.orbit.reader.Article
import com.albustech.orbit.reader.ArticleSanitizer
import com.albustech.orbit.reader.ReaderTemplate
import com.albustech.orbit.ui.RoundGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.HistoryDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** What the controller needs from the Activity. */
interface BrowserHost {
    /** The content process died; this session is unusable and must be replaced. */
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

    /** A long press on a link (Gecko's context menu): open the link wedges. */
    fun onLinkLongPress(url: String, title: String?)
}

/**
 * Owns the one GeckoSession (shown in [view]) and everything that happens in it, and exposes
 * state to Compose. Runs on the main thread.
 *
 * Pages are reached through the Orbit extension ([OrbitExtension], assets/extensions/orbit): it
 * runs the Compact view at document start, injects Readability / serp.js / links.js on request
 * and relays their messages.
 *
 * Reader flow: a page loads (Mobile, Compact or Desktop); if its site is on READER, extract.js runs
 * Readability and posts the article back; the app sanitises it again and hands it to the
 * extension, then loads the extension's reader page (moz-extension://…/reader.html#u=article).
 * History becomes [… A, A-reader]; Back from the reader skips A (which would only re-open it).
 *
 * With the phone helper reachable, pages are rendered on the phone instead ([PhoneLink],
 * [PhonePage]): the phone loads and draws the real page with ads blocked and sends picture
 * strips, which the watch scrolls through itself. Gecko then isn't even started. It takes over
 * when the phone is away or slow, for pages that must stay live (video, maps, games), for sites
 * the user moved to the watch, and to show Reader. Back from such a page returns to the phone's.
 */
class BrowserController(
    context: Context,
    /** Starts Gecko. Called only when a page first needs the watch's own engine. */
    private val runtime: () -> GeckoRuntime,
    private val extension: OrbitExtension,
    private val geometry: RoundGeometry,
    private val repo: BrowserRepository,
    private val scope: CoroutineScope,
    private val memoryLog: MemoryLog,
    private val connection: ConnectionMonitor,
    private val host: BrowserHost,
    private val phone: PhoneLink,
) : PageListener {
    // ------------------------------------------------------------ observable state

    var url by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var progress by mutableIntStateOf(100)
        private set
    var isLoading by mutableStateOf(false)
        private set

    /** A new page is loading and hasn't painted yet: the UI covers the old one (and any results page). */
    var awaitingPaint by mutableStateOf(false)
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
            else -> RenderMode.forSite(site.mode, defaultView)
        }

    /** The global View setting (Mobile, Compact or Desktop), for sites left on Auto. */
    var defaultView = RenderMode.MOBILE
        private set

    var bezelMode by mutableStateOf(BezelMode.SCROLL)
        private set
    var focusedLink by mutableStateOf<LinkFocus?>(null)
        private set

    /** 0..1 position in a scrolling page, for the edge arc. */
    var scrollFraction by mutableFloatStateOf(0f)
        private set

    /** Requests uBlock Origin blocked on this page (its badge count). */
    var blockedCount by mutableIntStateOf(0)
        private set

    /** Non-null while a search-results page is on: it's drawn natively, never shown as a page. */
    var serp by mutableStateOf<SerpState?>(null)
        private set

    /** Non-null while the page on screen was rendered by the phone (hidden while Reader shows). */
    var phonePage by mutableStateOf<PhonePage?>(null)
        private set

    /** Link mode on a phone page: the focused link's box, for the highlight. */
    var focusedBox by mutableStateOf<Box?>(null)
        private set

    /** A text field on the phone's page took focus: the UI opens the keyboard. */
    var fieldFocus by mutableStateOf<FieldFocus?>(null)
        private set

    /** The page on screen is the phone's. */
    val onPhone: Boolean get() = phonePage != null && readerUrl == null

    /** Pages can go through the phone now. */
    val phoneReady: Boolean get() = usePhone && !phoneDown && phone.connected

    // ---------------------------------------------------------------- settings in

    var searchTemplate: String = DEFAULT_SEARCH_TEMPLATE

    /** The "Load through phone" setting. */
    var usePhone = true

    /** Text size (percent) for pages the phone renders. */
    var textZoom = 100
    var readerStyle: ReaderStyle = ReaderStyle()
        set(value) {
            if (value == field) return
            field = value
            if (readerUrl != null) extension.reader("setStyle", JSONObject(ReaderTemplate.styleJson(value)))
        }

    // ------------------------------------------------------------------- engine

    private val geckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(true)
            .build(),
    )

    /** The page on screen. Compose shows it in an AndroidView. */
    val view: GeckoView = GeckoView(context).apply {
        coverUntilFirstPaint(Color.BLACK)
        setBackgroundColor(Color.BLACK)
    }

    /** True once Gecko runs and the session is open: until then nothing of Gecko is loaded. */
    private var geckoOpen = false

    /** The session, started on first use. */
    private val session: GeckoSession get() = geckoSession.also { ensureGecko() }

    private val scroller = GeckoScroller { if (geckoOpen) geckoSession.panZoomController else null }

    // ------------------------------------------------------------------- internals

    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val siteCache = HashMap<String, SiteSettings>()
    private var appliedSiteKey: String? = null
    private var pendingExtract: String? = null
    private var expectingReader: String? = null
    private var readerTitle: String? = null
    /** False until this reader page says 'ready': earlier 'page' events are its initial layout. */
    private var readerReady = false

    /** Results pages are loaded without images or Compact view; restore the site's settings after. */
    private var serpOverride = false
    private var serpRetried = false
    /** Results pages whose extraction failed (CAPTCHA, consent): shown as ordinary pages from then on. */
    private val serpFallback = HashSet<String>()
    /** The card each results page was left on, so Back from a result returns to it. */
    private val serpIndex = HashMap<String, Int>()
    private var linksActive = false
    private var clearHistoryOnNextLoad = false
    private var positionJob: Job? = null
    private var zoomStyleJob: Job? = null
    private var pendingFontSize: Int? = null
    private val readerAnchors = HashMap<String, Anchor>()

    // Phone pages.
    /** The phone helper said it can't run (not allowed in the background): watch only this session. */
    private var phoneDown = false
    /** Pages the watch renders itself: picked by the user, live pages, or the phone was too slow. */
    private val watchOnly = HashSet<String>()
    /** Asked the phone for this page; no strip yet. */
    private var phoneAwaiting: String? = null
    /** Phone page revisions up to this one are old pages still sending: ignored. */
    private var acceptAbove = 0
    private var lastMeta: PageMeta? = null
    private val pendingTiles = ArrayList<Tile>()
    /** Recent phone pages by address: Back shows them at once while the phone goes back. */
    private val phoneCache = object : LinkedHashMap<String, PhonePage>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhonePage>) = size > PHONE_CACHE
    }
    /** The phone page left for a watch-rendered one: Back returns to it. */
    private var parked: PhonePage? = null
    /** The reader on screen was extracted from a phone page; Back returns to that page. */
    private var readerOverPhone = false
    private var phoneLinks: List<Box> = emptyList()
    private var freshPhoneHistory = false
    private var phoneFocus = -1

    /** Gecko events concern the screen: no phone page is in front, or Reader is over one. */
    private val geckoInFront: Boolean get() = (phonePage == null && phoneAwaiting == null) || readerOverPhone

    private val phoneTimeout = Runnable {
        val target = phoneAwaiting ?: return@Runnable
        Log.i(TAG, "The phone didn't answer for $target: loading it on the watch")
        watchOnly.add(key(target))
        loadUrl(target)
    }
    private val reportView = Runnable {
        phonePage?.let { phone.send(Paths.INPUT, Input(Input.Type.VIEW, y = it.offsetY.toInt()).toJson()) }
    }

    /** Gecko's history list, from HistoryDelegate. */
    private var history: List<String?> = emptyList()
    private var historyIndex = -1
    private var geckoCanGoBack = false
    private var geckoCanGoForward = false
    private var lastState: GeckoSession.SessionState? = null

    /** When the page last reported a tap on a link or control, and when Gecko last opened a context menu. */
    private var interactiveTapAt = 0L
    private var contextMenuAt = 0L
    private var gestureLoadAt = 0L

    private val watchdog = Runnable {
        if (isLoading) {
            Log.w(TAG, "Load stalled at $progress% on ${connection.current()}")
            session.stop()
            error = PageError(url.orEmpty(), PageError.Kind.TIMEOUT, "", connection.current())
        }
    }

    init {
        geckoSession.navigationDelegate = Navigation()
        geckoSession.progressDelegate = Progress()
        geckoSession.contentDelegate = Content()
        geckoSession.historyDelegate = History()
        geckoSession.promptDelegate = Prompts(context)
        extension.listener = this
        phone.listener = PhoneEvents()
        applySite(SiteSettings(host = ""))
        extension.setWebGlAllowed(emptySet())
        scope.launch {
            repo.allSiteSettings().forEach { siteCache[it.host] = it }
            extension.setWebGlAllowed(richGraphicsSites())
            reapplyCachedSite()
        }
    }

    /**
     * Starts Gecko and opens the session, once. Gecko's start is the slowest part of opening
     * Orbit on a watch, so it waits for the first page the watch renders itself.
     * False if the engine can't start (an error screen says so).
     */
    private fun ensureGecko(): Boolean {
        if (geckoOpen) return true
        val rt = try {
            runtime()
        } catch (e: Throwable) {
            // Gecko's native libraries failed to load (wrong ABI, damaged install).
            Log.e(TAG, "Engine failed to start", e)
            error = PageError(url.orEmpty(), PageError.Kind.ENGINE, e.javaClass.simpleName, connection.current())
            return false
        }
        geckoOpen = true
        geckoSession.open(rt)
        view.setSession(geckoSession)
        rt.webExtensionController.setTabActive(geckoSession, true)
        OrbitRuntime.onUblock { ublock -> geckoSession.webExtensionController.setActionDelegate(ublock, BlockedBadge()) }
        return true
    }

    // ------------------------------------------------------------------ navigation

    /** Loads what the user typed or said. Returns false for blank input. */
    fun open(input: String): Boolean {
        val target = UrlResolver.resolve(input, searchTemplate) ?: return false
        loadUrl(target)
        return true
    }

    fun loadUrl(target: String) {
        // Only web pages: never javascript:, file:, content: or intent: from anywhere.
        if (!isWebUrl(target)) {
            Log.w(TAG, "Refused to load $target")
            return
        }
        error = null
        hasPage = true
        url = target
        awaitingPaint = true
        applySiteFor(target)
        if (phoneFor(target)) {
            openViaPhone(target, fresh = false)
            return
        }
        leavePhone()
        if (!ensureGecko()) {
            awaitingPaint = false
            return
        }
        prepareFor(target)
        session.loadUri(target)
    }

    /** Opens [target] as the start of a fresh history (a tab switch or a new tab). */
    fun loadFresh(target: String?) {
        if (target == null) {
            // A blank tab: unload the old page (stops its scripts) and start a fresh history
            // with the next real page. about:blank itself is never treated as a page.
            showHome()
            if (geckoOpen) session.loadUri(GeckoHistory.BLANK)
            clearHistoryOnNextLoad = true
            url = null
            title = null
            readerUrl = null
            reader = null
        } else {
            // Through the phone the new history starts on the phone; on the watch, here.
            clearHistoryOnNextLoad = true
            freshPhoneHistory = true
            loadUrl(target)
        }
    }

    /** Back, skipping an article's source page when leaving its reader view. False at the start. */
    fun back(): Boolean {
        if (linksActive) {
            setBezel(BezelMode.SCROLL)
            return true
        }
        if (readerOverPhone) {
            leaveReaderForPhone()
            return true
        }
        if (phonePage != null) {
            val meta = lastMeta
            if (meta?.canBack != true) return false
            // Show the page we're going back to at once, if it's still here; the phone's copy follows.
            // (Revision -1: nothing the phone still sends for the page being left lands on it.)
            meta.backUrl?.let { phoneCache[key(it)] }?.let { cached ->
                val placeholder = PhonePage(-1, cached.url, geometry.widthPx, geometry.heightPx).also { it.prefill(cached) }
                phonePage = placeholder
                url = cached.url
                awaitingPaint = false
            }
            phone.send(Paths.INPUT, Input(Input.Type.BACK).toJson())
            return true
        }
        val steps = backSteps()
        if (steps == null) {
            val p = parked ?: return false
            resumeParked(p)
            return true
        }
        session.gotoHistoryIndex(historyIndex + steps)
        return true
    }

    private fun backSteps(): Int? {
        if (history.isEmpty()) return if (geckoCanGoBack) -1 else null
        return GeckoHistory.backSteps(history, historyIndex, readerUrl, ::docOf, ::sameDoc)
    }

    /** The document a history entry shows: a reader page's article, or the entry itself. */
    private fun docOf(entry: String?): String? = ReaderTemplate.articleOf(entry, extension.baseUrl) ?: entry

    fun forward() {
        if (onPhone) {
            phone.send(Paths.INPUT, Input(Input.Type.FORWARD).toJson())
        } else if (geckoCanGoForward) {
            session.goForward()
        }
    }

    fun reloadOrStop() {
        when {
            !isLoading -> reload()
            onPhone || phoneAwaiting != null -> phone.send(Paths.INPUT, Input(Input.Type.STOP).toJson())
            else -> session.stop()
        }
    }

    fun reload() {
        val failed = error
        error = null
        when {
            // A stopped or failed navigation never committed: reload() would reload the page before it.
            failed != null && isWebUrl(failed.url) -> loadUrl(failed.url)
            // Fetch the article afresh; Back still skips every copy of it.
            readerUrl != null && !readerOverPhone -> loadUrl(readerUrl!!)
            onPhone -> phone.send(Paths.INPUT, Input(Input.Type.RELOAD).toJson())
            else -> session.reload()
        }
    }

    fun showHome() {
        if (geckoOpen) session.stop()
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

    /** Picks Mobile (with Reader for articles), Reader, Round fit or Desktop for this site and remembers it. */
    fun setSiteMode(mode: SiteMode) {
        val pageUrl = readerUrl ?: url ?: return
        val key = Suggestions.siteKey(pageUrl) ?: return
        saveSite(savedSite(key).copy(mode = mode))
        val wantsReader = mode == SiteMode.READER
        if (readerOverPhone && !wantsReader) {
            leaveReaderForPhone()
            loadUrl(pageUrl)
            return
        }
        if (onPhone) {
            if (wantsReader) requestExtract(pageUrl, force = true) else loadUrl(pageUrl)
            return
        }
        val source = readerUrl
        when {
            source != null && !wantsReader -> {
                // Leave the reader for the original page, re-using its entry if it's right behind us.
                appliedSiteKey = null
                applySiteFor(source)
                val previous = history.getOrNull(historyIndex - 1)
                if (historyIndex >= 1 && sameDoc(previous, source)) session.goBack() else session.loadUri(source)
            }
            source != null -> Unit // already reading
            wantsReader && lastAppliedRender != RenderMode.ZOOM -> requestExtract(pageUrl, force = true)
            else -> {
                appliedSiteKey = null
                applySiteFor(pageUrl)
                session.reload()
            }
        }
    }

    private var lastAppliedRender = RenderMode.MOBILE

    /** The global View setting; reloads the page on screen to apply it. */
    fun setDefaultView(view: RenderMode) {
        if (view == defaultView) return
        defaultView = view
        appliedSiteKey = null
        if (readerUrl != null) return // applied when the reader is left
        if (onPhone) {
            url?.let(::loadUrl)
            return
        }
        applySiteFor(url)
        if (hasPage) session.reload()
    }

    /** Changes a per-site toggle (images, JavaScript, lite UA, WebGL) and reloads to apply it. */
    fun updateSite(transform: (SiteSettings) -> SiteSettings) {
        val pageUrl = readerUrl ?: url ?: return
        val key = Suggestions.siteKey(pageUrl) ?: return
        val updated = transform(savedSite(key))
        saveSite(updated)
        applySite(updated)
        when {
            readerUrl != null -> session.settings.allowJavascript = true
            onPhone -> loadUrl(pageUrl) // JavaScript off, images off or Lite: the watch's engine does those
            hasPage -> session.reload()
        }
    }

    private fun savedSite(key: String) = siteCache[key] ?: SiteSettings(host = key)

    /** Settings load asynchronously at start: re-apply if the page on screen already used defaults. */
    private fun reapplyCachedSite() {
        val key = appliedSiteKey ?: return
        val saved = siteCache[key] ?: return
        if (saved == site || !geckoInFront) return
        appliedSiteKey = null
        if (readerUrl != null) {
            applySiteFor(readerUrl)
            session.settings.allowJavascript = true
        } else {
            applySiteFor(url)
            if (hasPage) session.reload()
        }
    }

    private fun richGraphicsSites(): Set<String> =
        siteCache.values.filter { it.richGraphics }.mapTo(HashSet()) { it.host }

    private fun saveSite(s: SiteSettings) {
        siteCache[s.host] = s
        site = s
        extension.setWebGlAllowed(richGraphicsSites())
        scope.launch { repo.saveSiteSettings(s) }
    }

    private fun applySiteFor(target: String?) {
        val key = Suggestions.siteKey(target)
        if (key != null && key == appliedSiteKey) return
        appliedSiteKey = key
        applySite(key?.let { siteCache[it] ?: SiteSettings(host = it) } ?: SiteSettings(host = ""))
    }

    /** Site settings for [target], and the results-page treatment if it is one. */
    private fun prepareFor(target: String?) {
        if (serpOverride) {
            serpOverride = false
            appliedSiteKey = null // re-apply the real site settings (images, layout)
        }
        applySiteFor(target)
        val page = target?.let(Serp::detect)
        if (page == null || key(target) in serpFallback) {
            serp = null
            return
        }
        serpOverride = true
        if (serp?.let { key(it.url) } != key(target)) serp = SerpState.Loading(page, target)
        // Never shown as a page: skip its images and the Compact view.
        extension.setBlockImages(true)
        extension.setCompact(false)
    }

    fun serpIndexFor(pageUrl: String): Int = serpIndex[key(pageUrl)] ?: 0

    fun rememberSerpIndex(pageUrl: String, index: Int) {
        serpIndex[key(pageUrl)] = index
    }

    private fun onSerp(msg: JSONObject) {
        val state = serp ?: return
        // Engines rewrite their URL after load (Google adds parameters): match on the search itself.
        if (Serp.detect(msg.optString("url")) != state.page) return
        val raw = msg.optJSONArray("results")
        val seen = HashSet<String>()
        val results = buildList {
            for (i in 0 until (raw?.length() ?: 0)) {
                val r = raw!!.optJSONObject(i) ?: continue
                val target = Serp.decodeResultUrl(r.optString("href"), state.page.site) ?: continue
                val title = r.optString("title").trim()
                if (title.isEmpty() || !seen.add(key(target))) continue
                add(SerpResult(title, target, Serp.displayHost(target), r.optString("snippet").trim()))
            }
        }
        if (results.isEmpty()) {
            if (phonePage != null || phoneAwaiting != null) {
                // The phone already retried: show its picture of the page instead.
                serpFallback.add(key(state.url))
                serp = null
                return
            }
            if (isLoading) return // the finished page gets another try
            if (!serpRetried) {
                serpRetried = true
                main.postDelayed({ if (serp is SerpState.Loading) extension.inject("serp") }, SERP_RETRY_MS)
                return
            }
            // CAPTCHA, consent screen or an unknown layout: show the real page instead.
            Log.i(TAG, "No results found on ${state.url}; showing the page")
            serpFallback.add(key(state.url))
            serp = null
            serpOverride = false
            appliedSiteKey = null
            applySiteFor(state.url)
            session.reload()
            return
        }
        val answer = msg.optString("answer").trim().ifEmpty { null }
        serp = SerpState.Ready(
            SerpData(state.page, state.url, results, answer, Serp.nextUrl(state.page, raw?.length() ?: 0)),
        )
    }

    private fun applySite(s: SiteSettings) {
        site = s
        val settings = geckoSession.settings
        settings.allowJavascript = s.javaScript
        extension.setBlockImages(s.blockImages)
        val mode = RenderMode.forSite(s.mode, defaultView)
        lastAppliedRender = mode
        // Desktop: the site's desktop version (desktop user agent and width), shown whole.
        // Everything else gets the mobile version; Compact lays it out at a phone's width.
        val desktop = mode == RenderMode.ZOOM
        settings.userAgentMode = if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        settings.userAgentOverride = if (s.liteUserAgent) UserAgents.LITE else null
        settings.viewportMode = if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        extension.setCompact(mode == RenderMode.COMPACT)
    }

    // ----------------------------------------------------------------- bezel

    /** Routes bezel detents according to the bezel mode and the render mode. */
    fun onBezel(detents: Int, stepPx: Int, eventTimeMs: Long) {
        val page = phonePage?.takeIf { readerUrl == null }
        if (page != null) {
            when (bezelMode) {
                BezelMode.SCROLL, BezelMode.CURSOR -> if (!page.scrollBy(detents * stepPx.toFloat())) host.onEdge()
                BezelMode.LINKS -> {
                    val dir = sign(detents.toFloat()).toInt()
                    repeat(abs(detents)) { stepPhoneLink(page, dir) }
                }
                BezelMode.ZOOM -> page.zoomBy(ZOOM_STEP.pow(detents))
            }
            onPhoneScrolled(page)
            return
        }
        when (bezelMode) {
            BezelMode.SCROLL, BezelMode.CURSOR -> if (readerUrl != null) {
                extension.reader("turn", detents)
            } else if (scroller.onDetents(detents, stepPx, eventTimeMs)) {
                host.onEdge()
            }
            BezelMode.LINKS -> {
                val dir = sign(detents.toFloat()).toInt()
                repeat(abs(detents)) { links("linksStep", dir) }
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
                scroller.zoom(view, ZOOM_STEP.pow(detents).coerceIn(0.2f, 5f))
            }
        }
    }

    /** Reader font size including a bezel change that hasn't settled yet (for the label). */
    val displayedFontSize: Int get() = pendingFontSize ?: readerStyle.fontSize

    fun stopScrolling() = scroller.stop()

    fun setBezel(mode: BezelMode) {
        if (linksActive && mode != BezelMode.LINKS) {
            if (!onPhone) links("linksStop")
            linksActive = false
            focusedLink = null
        }
        bezelMode = mode
        focusedBox = null
        phoneFocus = -1
        val page = phonePage?.takeIf { readerUrl == null }
        if (page != null) {
            linksActive = mode == BezelMode.LINKS
            if (linksActive) stepPhoneLink(page, 1)
            return
        }
        if (mode == BezelMode.LINKS && !linksActive) {
            if (!site.javaScript && readerUrl == null) {
                host.toast("Link focus needs JavaScript on this site")
                bezelMode = BezelMode.SCROLL
                return
            }
            linksActive = true
            if (readerUrl == null) extension.inject("links") // the reader page loads links.js itself
            links("linksStep", 1)
        }
    }

    fun activateFocusedLink() {
        val b = focusedBox
        if (onPhone && b != null) tapPhone(b.x + b.w / 2, b.y + b.h / 2) else links("linksActivate")
    }

    /** Cursor mode: clicks the page at ([x], [y]) in view pixels. */
    fun clickAt(x: Float, y: Float) {
        val page = phonePage?.takeIf { readerUrl == null }
        if (page == null) {
            scroller.tap(x, y)
            return
        }
        val (dx, dy) = page.toDoc(x, y)
        tapPhone(dx, dy)
    }

    /** Cursor mode: the finger dragged by ([dx], [dy]) px; the page follows it under the cursor. */
    fun panBy(dx: Float, dy: Float) {
        val page = phonePage?.takeIf { readerUrl == null }
        if (page != null) {
            page.scrollBy(-dy)
            page.panBy(-dx)
            onPhoneScrolled(page)
        } else if (readerUrl == null) {
            scroller.scrollBy(-dx, -dy)
        }
    }

    /** Clears a text selection a long press may have started (before the ring opens over it). */
    fun clearSelection() {
        if (readerUrl == null && !onPhone) extension.page("clearSelection")
    }

    /** Link focus commands go to bridge.js on web pages and to the reader page in Reader. */
    private fun links(name: String, arg: Any? = null) {
        if (readerUrl != null) extension.reader(name, arg) else extension.page(name, arg)
    }

    // ------------------------------------------------------------------- phone

    /** Whether [target] goes through the phone: reachable, and nothing about the site needs the watch's engine. */
    private fun phoneFor(target: String): Boolean =
        phoneReady && key(target) !in watchOnly && site.javaScript && !site.blockImages && !site.liteUserAgent

    private fun openViaPhone(target: String, fresh: Boolean) {
        val mode = RenderMode.forSite(site.mode, defaultView)
        val (view, css, scale) = when (mode) {
            RenderMode.ZOOM -> Triple(PhoneView.DESKTOP, DESKTOP_CSS, 2)
            RenderMode.COMPACT -> Triple(PhoneView.COMPACT, COMPACT_CSS, 1)
            else -> Triple(PhoneView.MOBILE, geometry.diameterCss.roundToInt(), 1)
        }
        val request = OpenRequest(target, view, css, geometry.widthPx, geometry.heightPx, scale, textZoom, fresh || freshPhoneHistory)
        freshPhoneHistory = false
        if (!phone.send(Paths.OPEN, request.toJson())) {
            watchOnly.add(key(target))
            loadUrl(target)
            return
        }
        val geckoShowing = geckoOpen && geckoInFront
        // Set before Gecko is told anything, so its events for the page being left are ignored.
        phoneAwaiting = target
        acceptAbove = maxOf(acceptAbove, lastMeta?.rev ?: 0)
        if (geckoShowing) unloadGecko()
        readerOverPhone = false
        readerUrl = null
        reader = null
        parked = null
        isLoading = true
        progress = 0
        prepareSerpOnPhone(target)
        resetPageInput()
        main.removeCallbacks(phoneTimeout)
        main.postDelayed(phoneTimeout, PHONE_TIMEOUT_MS)
    }

    /** Stops Gecko's page (its scripts, its memory) while the phone's is on screen. */
    private fun unloadGecko() {
        if (!geckoOpen) return
        main.removeCallbacks(watchdog)
        session.loadUri(GeckoHistory.BLANK)
        clearHistoryOnNextLoad = true
    }

    private fun prepareSerpOnPhone(target: String) {
        val page = Serp.detect(target)
        serp = when {
            page == null || key(target) in serpFallback -> null
            serp?.let { key(it.url) } == key(target) -> serp
            else -> SerpState.Loading(page, target)
        }
    }

    private fun resetPageInput() {
        focusedLink = null
        focusedBox = null
        linksActive = false
        phoneFocus = -1
        bezelMode = BezelMode.SCROLL
        fieldFocus = null
        scrollFraction = 0f
    }

    /** A page goes to the watch's engine: the phone page waits behind it for Back. */
    private fun leavePhone() {
        main.removeCallbacks(phoneTimeout)
        phoneAwaiting = null
        val page = phonePage ?: return
        parked = page
        phonePage = null
        readerOverPhone = false
        resetPageInput()
    }

    private fun resumeParked(page: PhonePage) {
        parked = null
        unloadGecko()
        phonePage = page
        url = page.url
        title = lastMeta?.takeIf { it.rev == page.rev }?.title ?: title
        readerUrl = null
        reader = null
        error = null
        serp = null
        isLoading = false
        awaitingPaint = false
        resetPageInput()
        refreshNav()
    }

    /** Back from a reader opened over a phone page: that page is still here. */
    private fun leaveReaderForPhone() {
        readerOverPhone = false
        val page = phonePage
        unloadGecko()
        readerUrl = null
        reader = null
        url = page?.url ?: url
        title = lastMeta?.title ?: title
        isLoading = false
        awaitingPaint = false
        error = null
        resetPageInput()
        refreshNav()
    }

    private fun onPhoneScrolled(page: PhonePage) {
        scrollFraction = page.fraction
        main.removeCallbacks(reportView)
        main.postDelayed(reportView, VIEW_REPORT_MS)
        if (!isLoading) {
            savePositionSoon(ReadingPosition(url = page.url, title = title, reader = false, scrollFraction = scrollFraction, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Link mode on a phone page: the next or previous link in reading order, kept on screen. */
    private fun stepPhoneLink(page: PhonePage, dir: Int) {
        if (phoneLinks.isEmpty()) {
            focusedLink = null
            focusedBox = null
            return
        }
        val next = if (phoneFocus !in phoneLinks.indices) {
            // Start at the first link on screen.
            phoneLinks.indexOfFirst { it.y + it.h > page.offsetY }.takeIf { it >= 0 } ?: phoneLinks.lastIndex
        } else {
            phoneFocus + dir
        }
        if (next !in phoneLinks.indices) {
            host.onEdge()
            return
        }
        phoneFocus = next
        val b = phoneLinks[next]
        val screen = geometry.heightPx / page.scale
        if (b.y < page.offsetY + screen * 0.2f || b.y + b.h > page.offsetY + screen * 0.8f) page.scrollToDoc(b.y + b.h / 2f - screen / 2f)
        focusedBox = b
        focusedLink = LinkFocus(b.href, b.label, b.kind.name.lowercase(), next, phoneLinks.size)
    }

    /** A tap on the phone page at document ([x], [y]); returns what it hit, if anything. */
    fun tapPhone(x: Int, y: Int): Box? {
        val page = phonePage ?: return null
        phone.send(Paths.INPUT, Input(Input.Type.TAP, x, y).toJson())
        return page.boxAt(x, y)
    }

    /** The phone page scrolled by touch, [dy] screen px. */
    fun scrollPhone(dx: Float, dy: Float) {
        val page = phonePage ?: return
        page.scrollBy(dy)
        page.panBy(dx)
        onPhoneScrolled(page)
    }

    /** Text for the focused field on the phone's page, sent with Enter. */
    fun typeIntoPage(text: String) {
        phone.send(Paths.INPUT, Input(Input.Type.TEXT, text = text, submit = true).toJson())
    }

    fun consumeFieldFocus() {
        fieldFocus = null
    }

    /** "Open on watch" / "Open via phone" for the page on screen. */
    fun switchEngine() {
        val pageUrl = (if (readerOverPhone) phonePage?.url else null) ?: url ?: return
        if (onPhone) {
            watchOnly.add(key(pageUrl))
        } else {
            watchOnly.remove(key(pageUrl))
            if (!phoneReady) {
                host.toast("Phone not connected")
                return
            }
        }
        loadUrl(pageUrl)
    }

    private inner class PhoneEvents : PhoneLink.Listener {

        override fun onMeta(meta: PageMeta) {
            if (meta.error == PageMeta.UNAVAILABLE) {
                phoneDown = true
                host.toast("Open Orbit Phone and allow it in the background")
                phoneAwaiting?.let {
                    phoneAwaiting = null
                    loadUrl(it)
                }
                return
            }
            val asked = phoneAwaiting
            // The helper restarted (its count began again) but this is the page just asked for.
            val answer = asked != null && sameDoc(meta.url, asked)
            // Older revisions are pages already left (still sending a late capture).
            if (meta.rev <= acceptAbove && !answer) return
            var page = phonePage
            if (page == null || meta.rev != page.rev) {
                // A new page: the one asked for, or one a tap on the phone's page opened.
                if ((page == null && asked == null) || readerOverPhone) return
                page = startPhonePage(meta, page)
            }
            lastMeta = meta
            page.docWidth = meta.width
            page.docHeight = meta.height
            meta.boxes?.let {
                page.boxes = it
                phoneLinks = it.sortedWith(compareBy({ b -> b.y }, { b -> b.x }))
            }
            if (meta.title.isNotBlank()) title = meta.title
            if (meta.url.isNotBlank() && meta.url != url) url = meta.url
            progress = meta.progress
            blockedCount = meta.blocked
            refreshNav()
            if (meta.error != null) {
                val kind = PageError.Kind.entries.firstOrNull { it.name == meta.error } ?: PageError.Kind.OTHER
                error = PageError(url.orEmpty(), kind, meta.errorDetail, connection.current())
                serp = null
                isLoading = false
                awaitingPaint = false
                phoneAwaiting = null
                main.removeCallbacks(phoneTimeout)
                return
            }
            if (meta.live) {
                // Video, a map, a game: pictures won't do. The watch's engine shows it.
                watchOnly.add(key(meta.url))
                loadUrl(meta.url)
                return
            }
            val finished = isLoading && !meta.loading
            isLoading = meta.loading
            if (finished) onPhonePageDone(page)
        }

        private fun startPhonePage(meta: PageMeta, old: PhonePage?): PhonePage {
            old?.let { phoneCache[key(it.url)] = it }
            acceptAbove = meta.rev - 1
            val page = PhonePage(meta.rev, meta.url, geometry.widthPx, geometry.heightPx)
            phoneCache[key(meta.url)]?.let(page::prefill)
            phonePage = page
            // The phone is on it: from here it reports its own load errors.
            main.removeCallbacks(phoneTimeout)
            url = meta.url
            title = meta.title
            error = null
            awaitingPaint = !page.hasStrips
            isLoading = true
            applySiteFor(meta.url)
            prepareSerpOnPhone(meta.url)
            resetPageInput()
            scrollFraction = page.fraction
            pendingTiles.filter { it.rev == meta.rev }.forEach(page::put)
            pendingTiles.clear()
            if (page.hasStrips) gotStrip()
            return page
        }

        override fun onTile(tile: Tile) {
            val page = phonePage
            if (page == null || tile.rev != page.rev) {
                // Strips can overtake their page's meta.
                if (tile.rev > (page?.rev ?: acceptAbove) && pendingTiles.size < MAX_PENDING_TILES) pendingTiles.add(tile)
                return
            }
            page.put(tile)
            gotStrip()
        }

        private fun gotStrip() {
            awaitingPaint = false
            phoneAwaiting = null
            main.removeCallbacks(phoneTimeout)
        }

        override fun onSerp(msg: JSONObject) {
            if (msg.optInt("rev") != phonePage?.rev) return
            gotStrip()
            this@BrowserController.onSerp(msg)
        }

        override fun onArticle(msg: JSONObject) {
            if (msg.optInt("rev") != phonePage?.rev) return
            onArticle(msg, msg.optString("url"))
        }

        override fun onFocus(focus: FieldFocus) {
            if (focus.rev == phonePage?.rev && readerUrl == null) fieldFocus = focus
        }
    }

    private fun onPhonePageDone(page: PhonePage) {
        val pageUrl = page.url
        host.onPageCommitted(pageUrl)
        memoryLog.onPageLoaded(pageUrl)
        scope.launch { repo.recordVisit(pageUrl, title) }
        if (serp != null) return
        if (site.mode == SiteMode.READER) requestExtract(pageUrl, force = true)
        // Back to where this page was left last time.
        scope.launch {
            val pos = repo.position(pageUrl) ?: return@launch
            if (pos.reader || pos.scrollFraction < 0.01f || phonePage !== page || page.offsetY > 0f) return@launch
            page.scrollToDoc(pos.scrollFraction * (page.docHeight - geometry.heightPx / page.scale))
            onPhoneScrolled(page)
        }
    }

    // ------------------------------------------------------------------- reader

    private fun requestExtract(pageUrl: String, force: Boolean) {
        if (onPhone) {
            // The phone runs Readability; the watch's engine starts meanwhile to show the reader.
            if (!ensureGecko()) return
            pendingExtract = pageUrl
            phone.send(Paths.INPUT, Input(Input.Type.EXTRACT, submit = force).toJson())
            return
        }
        if (extension.baseUrl == null) return // the extension isn't up yet: no reader page to show
        pendingExtract = pageUrl
        extension.inject("extract", force)
    }

    private fun showReader(article: Article) {
        scope.launch {
            // Gecko may have only just started (Reader from a phone page): wait for its extension.
            var waited = 0L
            while (extension.baseUrl == null && waited < EXTENSION_WAIT_MS) {
                delay(100)
                waited += 100
            }
            val base = extension.baseUrl ?: return@launch
            val saved = repo.position(article.url)
            val anchor = readerAnchors[key(article.url)]
                ?: saved?.takeIf { it.reader }?.let { Anchor(it.anchorBlock, it.anchorWord) }
            val payload = withContext(Dispatchers.Default) {
                // extract.js sanitises in the page, but the page could tamper with that:
                // clean again here, outside its reach.
                val clean = article.copy(content = ArticleSanitizer.clean(article.content, article.url))
                ReaderTemplate.payload(clean, geometry.diameterCss, geometry.squareCss, geometry.insetCss, readerStyle, anchor)
            }
            if (!sameDoc(url, article.url)) return@launch // the user moved on meanwhile
            val token = newToken()
            extension.readerPayload(token, payload)
            readerOverPhone = phonePage != null
            expectingReader = article.url
            readerTitle = article.title
            session.settings.allowJavascript = true // the reader's own script; the extension's CSP keeps page code out
            session.loadUri(ReaderTemplate.pageUrl(base, article.url, token))
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(9).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun onArticle(msg: JSONObject, from: String) {
        val requested = pendingExtract ?: return
        if (!sameDoc(from, requested)) return
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

    private fun onReaderMessage(msg: JSONObject, from: String) {
        // Only Orbit's own reader page speaks for the reader.
        val article = ReaderTemplate.articleOf(from, extension.baseUrl) ?: return
        if (readerUrl == null) readerUrl = article // arrived on a reader page through history
        val event = msg.optString("event")
        if (event == "missing") {
            // Reopened from history after a restart: its article is gone from memory. Fetch it again.
            loadUrl(article)
            return
        }
        val page = msg.optInt("page")
        val total = msg.optInt("total")
        reader = ReaderProgress(page, total, msg.optBoolean("done"))
        val anchor = msg.optJSONObject("anchor")?.let { Anchor(it.optInt("u"), it.optInt("w")) }
        when (event) {
            "ready" -> {
                readerReady = true
                val known = readerAnchors[key(article)]
                if (known != null && known != anchor) {
                    extension.reader("goToAnchor", JSONObject().put("u", known.unit).put("w", known.word))
                }
                if (readerTitle == null) readerTitle = title
                title = readerTitle
                scope.launch { repo.recordVisit(article, readerTitle) }
                host.onPageCommitted(article)
            }
            "edge" -> host.onEdge()
        }
        // 'page' events before 'ready' are the initial layout of a page restored from history,
        // not reading: they must not overwrite the remembered position.
        if (anchor != null && event == "page" && readerReady) {
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
        if (!linksActive) return
        when (msg.optString("event")) {
            "focus" -> focusedLink = LinkFocus(
                msg.optString("href"),
                msg.optString("label"),
                msg.optString("kind"),
                msg.optInt("index"),
                msg.optInt("count"),
            )
            "activate" -> if (msg.optString("kind") == "input") {
                view.requestFocus() // the keyboard needs the view focused
                session.setFocused(true)
            }
            "none" -> focusedLink = null
            "edge" -> host.onEdge()
        }
    }

    // ---------------------------------------------------------------- PageListener

    override fun onPageMessage(type: String, msg: JSONObject, frameId: Int, url: String) {
        if (frameId != 0) return
        if (BuildConfig.DEBUG && type != "article") Log.v("OrbitBridge", "$type $msg")
        when (type) {
            "article" -> onArticle(msg, url)
            "reader" -> onReaderMessage(msg, url)
            "links" -> onLinksMessage(msg)
            "serp" -> onSerp(msg)
        }
    }

    override fun onPageMetrics(y: Int, max: Int) {
        scroller.position = y
        scroller.max = max
        if (readerUrl != null || !hasPage) return
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

    override fun onPageTap(interactive: Boolean) {
        if (interactive) interactiveTapAt = SystemClock.uptimeMillis()
    }

    /** The page just reported a tap on a link or control, or a tap started a navigation. */
    fun recentInteractiveTap(): Boolean {
        val now = SystemClock.uptimeMillis()
        return now - interactiveTapAt < RECENT_MS || now - gestureLoadAt < RECENT_MS
    }

    /** Gecko just reported a long press on a link. */
    fun recentContextMenu(): Boolean = SystemClock.uptimeMillis() - contextMenuAt < RECENT_MS

    // ---------------------------------------------------------------- positions

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
            if (!sameDoc(url, pageUrl) || scroller.position > 0) return@launch
            extension.page("scrollToFraction", pos.scrollFraction.toDouble())
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun refreshNav() {
        if (!geckoInFront) {
            canGoBack = lastMeta?.canBack == true
            canGoForward = lastMeta?.canForward == true
            return
        }
        canGoBack = backSteps() != null || readerOverPhone || parked != null
        canGoForward = geckoCanGoForward && !readerOverPhone
    }

    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        if (isLoading) main.postDelayed(watchdog, connection.current().stallTimeoutMs)
    }

    private fun key(u: String) = BrowserRepository.normalize(u)

    private fun sameDoc(a: String?, b: String?): Boolean = a != null && b != null && key(a) == key(b)

    /** A snapshot of the page for the tab switcher; Gecko delivers it a frame or two later. */
    fun capture(): GeckoResult<Bitmap>? = if (geckoOpen && geckoInFront && view.width > 0 && view.height > 0) view.capturePixels() else null

    /** Gecko's session state (history and form data) if small enough, and always the URL. */
    fun saveState(out: Bundle) {
        (readerUrl ?: url)?.let { out.putString(STATE_URL, it) }
        if (!geckoInFront) return // the phone keeps that history
        val state = lastState?.toString() ?: return
        if (state.length <= MAX_STATE_CHARS) out.putString(STATE_SESSION, state)
    }

    fun restoreState(saved: Bundle): Boolean {
        val state = saved.getString(STATE_SESSION)?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
        if (state == null || state.isEmpty()) {
            val fallback = saved.getString(STATE_URL) ?: return false
            loadUrl(fallback)
            return isWebUrl(fallback)
        }
        if (!ensureGecko()) return false
        session.restoreState(state)
        hasPage = true
        val current = state.getOrNull(state.currentIndex)
        url = current?.uri?.let { ReaderTemplate.articleOf(it, extension.baseUrl) ?: it }
        title = current?.title
        refreshNav()
        return true
    }

    /** Stops timers, animations and the page itself (Activity stopped or ambient). */
    fun pause() {
        main.removeCallbacks(watchdog)
        scroller.stop()
        if (geckoOpen) session.setActive(false)
    }

    fun resume() {
        if (geckoOpen) session.setActive(true)
        armWatchdog()
    }

    /** Releases the session; the controller is unusable afterwards. */
    fun destroy() {
        main.removeCallbacksAndMessages(null)
        if (extension.listener === this) extension.listener = null
        if (!geckoOpen) return
        runtime().webExtensionController.setTabActive(geckoSession, false)
        view.releaseSession()
        geckoSession.close()
    }

    /** The "Stronger blocking" setting (uBlock Origin). */
    fun setBlocking(on: Boolean) {
        OrbitRuntime.setBlocking(on)
        if (!on) blockedCount = 0
    }

    // ----------------------------------------------------------------- page start/stop

    private fun onStarted(pageUrl: String?) {
        if (!geckoInFront) return
        val expected = expectingReader
        expectingReader = null
        if (pageUrl == GeckoHistory.BLANK) return // a blank tab, not a page
        val reading = ReaderTemplate.articleOf(pageUrl, extension.baseUrl)
            ?: expected?.takeIf { pageUrl?.startsWith("moz-extension:") == true }
        readerUrl = reading
        readerReady = false
        serpRetried = false
        if (reading == null) {
            reader = null
            readerTitle = null
            prepareFor(pageUrl)
        } else {
            serp = null
            // The article's own site settings, but the reader always needs its script.
            applySiteFor(reading)
            session.settings.allowJavascript = true
        }
        isLoading = true
        awaitingPaint = true
        progress = 0
        error = null
        url = reading ?: pageUrl
        scrollFraction = 0f
        scroller.position = 0
        scroller.max = -1
        blockedCount = 0
        focusedLink = null
        linksActive = false
        bezelMode = BezelMode.SCROLL
        pendingExtract = null
        scroller.stop()
        refreshNav()
        armWatchdog()
    }

    private fun onStopped(success: Boolean) {
        if (!geckoInFront) return
        isLoading = false
        awaitingPaint = false
        progress = 100
        main.removeCallbacks(watchdog)
        val pageUrl = url
        if (pageUrl == null || !success) return
        if (clearHistoryOnNextLoad && (readerUrl != null || isWebUrl(pageUrl))) {
            clearHistoryOnNextLoad = false
            session.purgeHistory()
        }
        refreshNav()
        memoryLog.onPageLoaded(pageUrl)
        if (readerUrl != null || error != null || !isWebUrl(pageUrl)) return
        if (serp != null) {
            extension.inject("serp")
            scope.launch { repo.recordVisit(pageUrl, title) }
            return
        }
        host.onPageCommitted(pageUrl)
        scope.launch { repo.recordVisit(pageUrl, title) }
        // Reader only when picked: running Readability on every page cost seconds on the watch.
        if (site.javaScript && site.mode == SiteMode.READER) requestExtract(pageUrl, force = true)
        restoreScroll(pageUrl)
    }

    // ----------------------------------------------------------------- delegates

    private inner class Navigation : NavigationDelegate {

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val uri = request.uri
            if (request.hasUserGesture) gestureLoadAt = SystemClock.uptimeMillis()
            return when (uri.toUri().scheme?.lowercase()) {
                "http", "https" -> {
                    if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                        // One window on a watch: open target=_blank links in place.
                        main.post { loadUrl(uri) }
                        GeckoResult.deny()
                    } else {
                        prepareFor(uri)
                        GeckoResult.allow()
                    }
                }
                "about", "data", "blob", "moz-extension", "resource" -> GeckoResult.allow()
                "intent" -> {
                    // intent://…#Intent;…;S.browser_fallback_url=…;end: follow the web fallback.
                    val fallback = FALLBACK.find(uri)?.groupValues?.get(1)?.let(Uri::decode)
                    if (fallback != null && fallback.startsWith("http")) main.post { loadUrl(fallback) }
                    GeckoResult.deny()
                }
                "mailto", "tel", "sms", "geo", "market" -> {
                    host.openOnPhone(uri)
                    GeckoResult.deny()
                }
                else -> {
                    Log.i(TAG, "Blocked navigation to $uri")
                    GeckoResult.deny()
                }
            }
        }

        override fun onSubframeLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val scheme = request.uri.toUri().scheme?.lowercase()
            return if (scheme in SUBFRAME_SCHEMES) GeckoResult.allow() else GeckoResult.deny()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            if (isWebUrl(uri)) main.post { loadUrl(uri) }
            return null
        }

        override fun onLocationChange(
            session: GeckoSession,
            location: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            // pushState and fragment changes on the same page (WebView's doUpdateVisitedHistory).
            if (geckoInFront && readerUrl == null && location != null && isWebUrl(location)) url = location
            refreshNav()
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            geckoCanGoBack = canGoBack
            refreshNav()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            geckoCanGoForward = canGoForward
            refreshNav()
        }

        override fun onLoadError(session: GeckoSession, uri: String?, err: WebRequestError): GeckoResult<String>? {
            if (!geckoInFront) return null
            val conn = connection.current()
            val kind = if (conn == ConnectionType.NONE) PageError.Kind.OFFLINE else PageError.kindFor(err.category, err.code)
            error = PageError(uri ?: url.orEmpty(), kind, PageError.detailFor(err.code), conn)
            serp = null
            isLoading = false
            awaitingPaint = false
            main.removeCallbacks(watchdog)
            return null // Orbit draws its own error screen over Gecko's page
        }
    }

    private inner class Progress : ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) = onStarted(url)

        override fun onPageStop(session: GeckoSession, success: Boolean) = onStopped(success)

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (geckoInFront && progress != this@BrowserController.progress) {
                this@BrowserController.progress = progress
                armWatchdog()
            }
        }

        override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
            lastState = sessionState
        }
    }

    private inner class Content : ContentDelegate {
        override fun onTitleChange(session: GeckoSession, newTitle: String?) {
            if (!geckoInFront) return
            title = if (readerUrl != null) readerTitle ?: newTitle else newTitle
        }

        override fun onFirstContentfulPaint(session: GeckoSession) {
            if (!geckoInFront) return
            awaitingPaint = false
            // Results pages are read as soon as they paint, not when every last resource is in.
            if (serp is SerpState.Loading) extension.inject("serp")
        }

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContentDelegate.ContextElement) {
            val link = element.linkUri ?: return
            if (!isWebUrl(link)) return
            contextMenuAt = SystemClock.uptimeMillis()
            host.onLinkLongPress(link, element.title ?: element.linkText)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            // Downloads: the phone is better at files.
            host.openOnPhone(response.uri)
        }

        override fun onCrash(session: GeckoSession) {
            Log.w(TAG, "Content process crashed at $url")
            host.onRendererGone(readerUrl ?: url)
        }

        override fun onKill(session: GeckoSession) {
            // On a 2 GB watch the system may kill the content process to reclaim memory.
            Log.w(TAG, "Content process killed at $url")
            host.onRendererGone(readerUrl ?: url)
        }
    }

    private inner class History : HistoryDelegate {
        override fun onHistoryStateChange(session: GeckoSession, historyList: HistoryDelegate.HistoryList) {
            history = historyList.map { it.uri }
            historyIndex = historyList.currentIndex
            refreshNav()
        }
    }

    /** uBlock Origin's toolbar badge is its blocked count for the page ("12", "1k"). */
    private inner class BlockedBadge : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            blockedCount = parseBadge(action.badgeText)
        }
    }

    companion object {
        private const val TAG = "OrbitBrowser"
        const val DEFAULT_SEARCH_TEMPLATE = "https://html.duckduckgo.com/html/?q=%s"
        private const val ZOOM_STEP = 1.12f
        private val FALLBACK = Regex("S\\.browser_fallback_url=([^;]+)")
        private const val SERP_RETRY_MS = 700L
        /** No word from the phone in this time: the watch loads the page itself. */
        private const val PHONE_TIMEOUT_MS = 8000L
        private const val PHONE_CACHE = 3
        private const val MAX_PENDING_TILES = 40
        private const val VIEW_REPORT_MS = 150L
        private const val EXTENSION_WAIT_MS = 10_000L
        /** Compact and Desktop widths in CSS px, as the watch's own engine lays them out. */
        private const val COMPACT_CSS = 360
        private const val DESKTOP_CSS = 980
        private const val RECENT_MS = 700L
        private const val STATE_URL = "orbit.url"
        private const val STATE_SESSION = "orbit.session"
        /** Bundles cross Binder: keep the saved session well under its 1 MB limit. */
        private const val MAX_STATE_CHARS = 256 * 1024
        private val SUBFRAME_SCHEMES = setOf("http", "https", "about", "data", "blob")

        fun isWebUrl(u: String?): Boolean =
            u != null && (u.startsWith("https://", ignoreCase = true) || u.startsWith("http://", ignoreCase = true))

        fun hostOf(url: String?): String? = Suggestions.siteKey(url)

        /** "12" → 12, "1k" → 1000, "" or null → 0. */
        fun parseBadge(text: String?): Int {
            val t = text?.trim()?.lowercase().orEmpty()
            val digits = t.takeWhile { it.isDigit() }.toIntOrNull() ?: return 0
            return if (t.drop(digits.toString().length).startsWith("k")) digits * 1000 else digits
        }
    }
}
