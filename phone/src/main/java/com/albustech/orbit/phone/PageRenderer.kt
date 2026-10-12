package com.albustech.orbit.phone

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import com.albustech.orbit.browser.Serp
import com.albustech.orbit.browser.filters.Types
import com.albustech.orbit.link.Box
import com.albustech.orbit.link.FieldFocus
import com.albustech.orbit.link.Input
import com.albustech.orbit.link.Json
import com.albustech.orbit.link.OpenRequest
import com.albustech.orbit.link.PageMeta
import com.albustech.orbit.link.Paths
import com.albustech.orbit.link.Tile
import com.albustech.orbit.link.UrlSafety
import com.albustech.orbit.link.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Renders the watch's page on the phone, exactly as Chrome draws it, and sends it as strips.
 *
 * The WebView lives in a Presentation on a private virtual display the size of the watch screen
 * (× [OpenRequest.scale]), with a density that gives the page the watch's CSS width. Frames land
 * in an ImageReader. To capture, the page is scrolled one screen at a time and each settled
 * frame is cut into watch-screen strips, encoded as WebP, and sent only if it changed.
 *
 * Fixed and sticky elements (headers, cookie bars) show in the first screen only: in every
 * later screen they would repeat down the page, so they are hidden while those are captured.
 *
 * Main thread only, apart from shouldInterceptRequest and the frame listener.
 */
@SuppressLint("SetJavaScriptEnabled")
class PageRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val send: (path: String, bytes: ByteArray) -> Unit,
) {
    private var request: OpenRequest? = null
    private var web: WebView? = null
    private var presentation: Presentation? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameThread = HandlerThread("orbit-frames").apply { start() }

    /** The latest frame of the display, padded to the buffer's row stride. Guarded by itself. */
    private val frameLock = Any()
    private var frame: Bitmap? = null
    @Volatile private var frameSeq = 0

    private val captureLock = Mutex()
    /**
     * Goes up with every navigation. Starts from the clock, so it keeps going up when the
     * service restarts and the watch (which ignores older revisions) still listens.
     */
    private var rev = ((System.currentTimeMillis() / 1000) - REV_EPOCH).toInt()
    private var url = ""
    private var title = ""
    private var loading = false
    private var progress = 100
    private var lastProgressMeta = 0L
    private var error: String? = null
    private var errorDetail = ""
    private var width = 0
    private var height = 0
    private var live = false
    private var boxes: List<Box> = emptyList()

    /** Where the watch is reading, in document px: strips around it are rendered first. */
    private var watchY = 0
    private var roundJob: Job? = null
    private val sentHashes = HashMap<Int, Int>()
    private var clearHistoryOnFinish = false

    /** Results pages are read by serp.js and drawn natively on the watch: no strips unless that fails. */
    private var serpPending = false
    private var serpRetried = false
    private val serpFallback = HashSet<String>()

    @Volatile private var topHost: String? = null
    @Volatile private var pageFlags = 0
    private val blocked = AtomicInteger()

    private val scripts by lazy {
        val assets = context.assets
        fun read(name: String) = assets.open(name).bufferedReader().use { it.readText() }
        Scripts(
            map = read("map.js"),
            serp = read("page/serp.js"),
            extract = listOf("page/Readability-readerable.js", "page/Readability.js", "page/extract.js").joinToString("\n", transform = ::read),
            cosmetic = read("cosmetic.js"),
        )
    }

    // ------------------------------------------------------------------ from the watch

    fun open(req: OpenRequest) {
        if (!UrlSafety.isPublicWebUrl(req.url)) {
            Log.w(TAG, "Refused to open ${req.url}")
            return
        }
        val w = ensureView(req)
        request = req
        w.settings.textZoom = req.textZoom
        w.settings.userAgentString = userAgent(req.view)
        w.settings.loadWithOverviewMode = req.view == View.DESKTOP
        clearHistoryOnFinish = req.fresh
        watchY = 0
        w.loadUrl(req.url)
    }

    fun input(i: Input) {
        val w = web ?: return
        when (i.type) {
            Input.Type.TAP -> scope.launch { tap(w, i.x, i.y) }
            Input.Type.TEXT -> scope.launch {
                eval(w, "(${TYPE_JS})(${JSONObject.quote(i.text)}, ${i.submit})")
                scheduleRound(400)
            }
            Input.Type.BACK -> if (w.canGoBack()) w.goBack()
            Input.Type.FORWARD -> if (w.canGoForward()) w.goForward()
            Input.Type.RELOAD -> w.reload()
            Input.Type.STOP -> w.stopLoading()
            Input.Type.VIEW -> {
                watchY = i.y.coerceAtLeast(0)
                scheduleRound(120)
            }
            Input.Type.EXTRACT -> w.evaluateJavascript("(function(){var orbitForce=${i.submit};\n${scripts.extract}\n})();", null)
        }
    }

    fun destroy() {
        roundJob?.cancel()
        tearDown()
        frameThread.quitSafely()
    }

    // ------------------------------------------------------------------ the view

    private fun ensureView(req: OpenRequest): WebView {
        val old = request
        val w = web
        if (w != null && old != null && old.widthPx == req.widthPx && old.heightPx == req.heightPx &&
            old.scale == req.scale && old.cssWidth == req.cssWidth
        ) {
            return w
        }
        tearDown()
        val pxW = req.widthPx * req.scale
        val pxH = req.heightPx * req.scale
        // One CSS px is densityDpi / 160 device px: this gives the page the watch's CSS width.
        val dpi = (160f * pxW / req.cssWidth).toInt().coerceAtLeast(60)
        val images = ImageReader.newInstance(pxW, pxH, PixelFormat.RGBA_8888, 3)
        images.setOnImageAvailableListener(::onFrame, Handler(frameThread.looper))
        val vd = context.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("orbit-page", pxW, pxH, dpi, images.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        // A dark theme: pages that have a dark style (prefers-color-scheme) use it, as on the watch.
        val p = Presentation(context, vd.display, android.R.style.Theme_DeviceDefault)
        p.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val view = WebView(p.context)
        configure(view)
        p.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.show()
        reader = images
        display = vd
        presentation = p
        web = view
        return view
    }

    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            useWideViewPort = true
        }
        view.setBackgroundColor(Color.BLACK)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        Bridge(::onPageMessage, ::onCosmetic).attach(view)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, scripts.cosmetic, setOf("*"))
        }
    }

    private fun tearDown() {
        web?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        synchronized(frameLock) {
            frame?.recycle()
            frame = null
        }
    }

    private fun userAgent(view: View): String {
        val base = WebSettings.getDefaultUserAgent(context)
        if (view == View.DESKTOP) {
            val chrome = CHROME.find(base)?.value ?: "Chrome/140.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
        // WebView's "wv" and "Version/4.0" make some sites serve broken in-app pages.
        var ua = base.replace("; wv)", ")").replace(Regex("\\s?Version/\\d+(\\.\\d+)*"), "")
        if (!ua.contains("Mobile")) ua = ua.replace(" Safari/", " Mobile Safari/")
        return ua
    }

    // ------------------------------------------------------------------ capture

    private fun onFrame(images: ImageReader) {
        val image = try {
            images.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val padded = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                val f = frame?.takeIf { it.width == padded && it.height == image.height }
                    ?: Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888).also { frame?.recycle(); frame = it }
                f.copyPixelsFromBuffer(plane.buffer)
            }
            frameSeq++
        } finally {
            image.close()
        }
    }

    /** Captures again soon (debounced): the strips around [watchY], and the map. */
    private fun scheduleRound(delayMs: Long) {
        roundJob?.cancel()
        roundJob = scope.launch {
            delay(delayMs)
            round()
        }
    }

    private suspend fun round() = captureLock.withLock {
        val w = web ?: return@withLock
        val req = request ?: return@withLock
        val myRev = rev
        scrollTo(w, 0)
        readMap(w)
        sendMeta(withBoxes = true)
        if (serpPending || error != null || rev != myRev) return@withLock
        val screen = req.heightPx * req.scale
        val maxIndex = ((height - 1).coerceAtLeast(0) / screen)
        val first = ((watchY - screen) / screen).coerceIn(0, maxIndex)
        val last = ((watchY + AHEAD_SCREENS * screen) / screen).coerceIn(0, maxIndex.coerceAtMost(MAX_SCREENS))
        var hidden = false
        for (k in first..last) {
            if (rev != myRev) break
            if (k >= 1 && !hidden) {
                eval(w, HIDE_FIXED_JS)
                hidden = true
            }
            val y = scrollTo(w, k * screen) ?: break
            val shot = settledFrame(w) ?: break
            sendStrips(myRev, y, shot, req)
            shot.recycle()
        }
        if (hidden) eval(w, SHOW_FIXED_JS)
        scrollTo(w, 0)
    }

    private suspend fun readMap(w: WebView) {
        val raw = eval(w, scripts.map) as? String ?: return
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        width = m.optInt("width")
        height = m.optInt("height")
        live = m.optBoolean("live")
        m.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
        val raw2 = m.optJSONArray("boxes") ?: JSONArray()
        boxes = List(raw2.length()) { i -> runCatching { Box.from(raw2.getJSONArray(i)) }.getOrNull() }.filterNotNull()
    }

    /** Scrolls the page to document px [y]; returns where it actually is (clamped at the end). */
    private suspend fun scrollTo(w: WebView, y: Int): Int? =
        (eval(w, "window.scrollTo(0,$y/devicePixelRatio);Math.round(window.scrollY*devicePixelRatio)") as? Number)?.toInt()

    /** A copy of the display once the page has drawn its current state. */
    private suspend fun settledFrame(w: WebView): Bitmap? {
        val before = frameSeq
        withTimeoutOrNull(VISUAL_STATE_MS) {
            suspendCancellableCoroutine { c ->
                w.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (c.isActive) c.resume(Unit)
                    }
                })
            }
        }
        // The frame after that state reaches the display (none comes if nothing changed).
        withTimeoutOrNull(FRAME_MS) { while (frameSeq == before) delay(8) }
        val req = request ?: return null
        val pxW = req.widthPx * req.scale
        val pxH = req.heightPx * req.scale
        return synchronized(frameLock) {
            frame?.takeIf { it.width >= pxW && it.height >= pxH }?.let { Bitmap.createBitmap(it, 0, 0, pxW, pxH) }
        }
    }

    /** Cuts one screen into watch-screen strips and sends the ones that changed. */
    private fun sendStrips(myRev: Int, y: Int, shot: Bitmap, req: OpenRequest) {
        val stripH = req.heightPx
        var top = 0
        while (top < shot.height) {
            val h = minOf(stripH, shot.height - top)
            val strip = Bitmap.createBitmap(shot, 0, top, shot.width, h)
            val pixels = IntArray(strip.width * strip.height)
            strip.getPixels(pixels, 0, strip.width, 0, 0, strip.width, strip.height)
            val hash = pixels.contentHashCode()
            if (sentHashes[y + top] != hash) {
                val image = encode(strip)
                if (image != null) {
                    sentHashes[y + top] = hash
                    send(Paths.TILE, Tile(myRev, y + top, strip.width, h, image).encode())
                }
            }
            strip.recycle()
            top += h
        }
    }

    /** WebP that fits one Data Layer message: quality steps down for busy strips. */
    private fun encode(strip: Bitmap): ByteArray? {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        for (q in QUALITIES) {
            val out = ByteArrayOutputStream()
            strip.compress(format, q, out)
            if (out.size() <= Paths.MAX_MESSAGE - 64) return out.toByteArray()
        }
        return null
    }

    // ------------------------------------------------------------------ input

    private suspend fun tap(w: WebView, x: Int, y: Int) {
        val req = request ?: return
        val screen = req.heightPx * req.scale
        captureLock.withLock {
            // The first screen as captured (fixed bars where they were); lower down, centred.
            val top = scrollTo(w, if (y < screen) 0 else y - screen / 2) ?: return
            val vx = x.toFloat()
            val vy = (y - top).toFloat()
            val down = SystemClock.uptimeMillis()
            touch(w, MotionEvent.ACTION_DOWN, down, down, vx, vy)
            delay(40)
            touch(w, MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis(), vx, vy)
        }
        delay(FOCUS_CHECK_MS)
        val focused = eval(w, FOCUSED_FIELD_JS) as? String
        if (focused != null) {
            val f = runCatching { JSONObject(focused) }.getOrNull()
            if (f != null) send(Paths.FOCUS, Json.pack(FieldFocus(rev, f.optString("value"), f.optString("label")).toJson()))
        }
        scheduleRound(AFTER_TAP_MS)
    }

    private fun touch(w: WebView, action: Int, down: Long, at: Long, x: Float, y: Float) {
        val ev = MotionEvent.obtain(down, at, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        w.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // ------------------------------------------------------------------ to the watch

    private fun sendMeta(withBoxes: Boolean) {
        val w = web
        val history = w?.copyBackForwardList()
        val back = history?.takeIf { it.currentIndex > 0 }?.getItemAtIndex(history.currentIndex - 1)?.url
        val meta = PageMeta(
            rev = rev,
            url = url,
            title = title,
            width = width,
            height = height,
            loading = loading,
            progress = progress,
            canBack = w?.canGoBack() == true,
            canForward = w?.canGoForward() == true,
            backUrl = back,
            live = live,
            blocked = blocked.get(),
            error = error,
            errorDetail = errorDetail,
            boxes = if (withBoxes) boxes else null,
        )
        send(Paths.META, Json.pack(meta.toJson()))
    }

    private fun onPageMessage(type: String, msg: JSONObject) {
        when (type) {
            "serp" -> onSerp(msg)
            "article" -> {
                val packed = Json.pack(msg.put("rev", rev))
                // shortcut: an article over one message (~300 KB of text) isn't sent; split it if that matters.
                if (packed.size <= Paths.MAX_MESSAGE) send(Paths.ARTICLE, packed) else Log.w(TAG, "Article too large to send: ${packed.size} B")
            }
        }
    }

    private fun onSerp(msg: JSONObject) {
        if (!serpPending) return
        val found = (msg.optJSONArray("results")?.length() ?: 0) > 0
        if (!found && loading) return // the finished page gets another try
        if (!found && !serpRetried) {
            serpRetried = true
            scope.launch {
                delay(SERP_RETRY_MS)
                web?.evaluateJavascript(scripts.serp, null)
            }
            return
        }
        send(Paths.SERP, Json.pack(msg.put("rev", rev)))
        if (!found) {
            // CAPTCHA, consent screen or an unknown layout: the watch shows the page instead.
            serpFallback.add(url)
            serpPending = false
            scheduleRound(0)
        }
    }

    private fun onCosmetic(msg: JSONObject, origin: Uri, reply: (String) -> Unit) {
        if (serpPending) return
        val c = Blocking.cosmetics ?: return
        val host = origin.host ?: return
        val flags = pageFlags
        val css = buildString {
            if (msg.optBoolean("first")) append(c.siteCss(host, flags))
            append(c.genericCss(host, flags, msg.optJSONArray("ids").strings(), msg.optJSONArray("classes").strings()))
        }
        if (css.isNotEmpty()) reply(css)
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return List(minOf(length(), 4000)) { optString(it) }.filter { it.isNotEmpty() && it.length <= 200 }
    }

    // ------------------------------------------------------------------ clients

    @SuppressLint("MissingOnRenderProcessGone") // overridden below; lint misses it on WebViewClientCompat
    private inner class Client : WebViewClientCompat() {

        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
            rev++
            url = pageUrl.orEmpty()
            title = ""
            loading = true
            progress = 0
            error = null
            errorDetail = ""
            width = 0
            height = 0
            live = false
            boxes = emptyList()
            sentHashes.clear()
            Blocking.addToToday(context, blocked.getAndSet(0))
            serpRetried = false
            serpPending = Serp.detect(url) != null && url !in serpFallback
            roundJob?.cancel()
            sendMeta(withBoxes = true)
        }

        override fun onPageCommitVisible(view: WebView, pageUrl: String) {
            // First paint of the new page: the first screen goes out now, the rest when it's loaded.
            if (serpPending) view.evaluateJavascript(scripts.serp, null) else scheduleRound(FIRST_PAINT_MS)
        }

        override fun onPageFinished(view: WebView, pageUrl: String?) {
            if (view.progress < 100) return // a sub-navigation; onPageFinished fires again
            loading = false
            progress = 100
            if (clearHistoryOnFinish) {
                clearHistoryOnFinish = false
                view.clearHistory()
            }
            if (serpPending) view.evaluateJavascript(scripts.serp, null)
            val myRev = rev
            roundJob?.cancel()
            roundJob = scope.launch {
                round()
                // Late images and fonts.
                delay(SETTLE_MS)
                if (rev == myRev) round()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> false
                "about", "data", "blob" -> false
                "intent" -> {
                    // intent://…#Intent;…;S.browser_fallback_url=…;end: follow the web fallback.
                    val fallback = FALLBACK.find(uri.toString())?.groupValues?.get(1)?.let(Uri::decode)
                    if (fallback != null && UrlSafety.isPublicWebUrl(fallback)) view.loadUrl(fallback)
                    true
                }
                else -> true
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) {
                // The earliest sign of the new page: judge its subresources against it.
                topHost = request.url.host
                pageFlags = Blocking.filters?.pageFlags(request.url.toString()) ?: 0
                return null
            }
            val engine = Blocking.filters ?: return null
            if (pageFlags and Types.DOCUMENT != 0) return null
            val target = request.url.toString()
            val type = Types.guess(target, request.requestHeaders["Accept"] ?: request.requestHeaders["accept"])
            if (!engine.shouldBlock(target, topHost, type)) return null
            blocked.incrementAndGet()
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceErrorCompat) {
            if (!request.isForMainFrame) return
            val code = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) err.errorCode else 0
            error = when (code) {
                ERROR_HOST_LOOKUP -> "HOST"
                ERROR_TIMEOUT -> "TIMEOUT"
                ERROR_FAILED_SSL_HANDSHAKE -> "SECURITY"
                else -> "OTHER"
            }
            errorDetail = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) err.description.toString() else ""
            loading = false
            sendMeta(withBoxes = false)
        }

        @SuppressLint("WebViewClientOnReceivedSslError") // Always cancels: never past a bad certificate.
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, err: SslError) {
            handler.cancel()
            if (err.url != url) return
            error = "SECURITY"
            loading = false
            sendMeta(withBoxes = false)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "Renderer gone (crashed: ${detail.didCrash()})")
            // The view is unusable: rebuild it on the next open.
            web = null
            presentation?.dismiss()
            presentation = null
            error = "OTHER"
            errorDetail = "The page crashed."
            sendMeta(withBoxes = false)
            request = null
            return true
        }
    }

    private inner class Chrome : android.webkit.WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            val now = SystemClock.uptimeMillis()
            if (loading && now - lastProgressMeta > PROGRESS_META_MS) {
                lastProgressMeta = now
                sendMeta(withBoxes = false)
            }
        }

        override fun onReceivedTitle(view: WebView, t: String?) {
            title = t.orEmpty()
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs [js] in the page; the result as a JSON value (String, Number, Boolean) or null. */
    private suspend fun eval(w: WebView, js: String): Any? = withTimeoutOrNull(EVAL_MS) {
        suspendCancellableCoroutine { c ->
            w.evaluateJavascript(js) { result ->
                val value = runCatching { JSONArray("[$result]").opt(0) }.getOrNull()
                if (c.isActive) c.resume(if (value == JSONObject.NULL) null else value)
            }
        }
    }

    private class Scripts(val map: String, val serp: String, val extract: String, val cosmetic: String)

    private companion object {
        const val TAG = "OrbitRender"
        const val REV_EPOCH = 1_780_000_000L
        val CHROME = Regex("Chrome/[\\d.]+")
        val FALLBACK = Regex("S\\.browser_fallback_url=([^;]+)")

        /** Screens rendered ahead of where the watch is reading; the rest follow as it scrolls. */
        const val AHEAD_SCREENS = 3

        /** A page longer than this many screens stops there (memory on the watch). */
        const val MAX_SCREENS = 60
        val QUALITIES = intArrayOf(82, 70, 55, 40)
        const val FIRST_PAINT_MS = 120L
        const val SETTLE_MS = 1500L
        const val AFTER_TAP_MS = 600L
        const val FOCUS_CHECK_MS = 250L
        const val SERP_RETRY_MS = 700L
        const val PROGRESS_META_MS = 700L
        const val VISUAL_STATE_MS = 600L
        const val FRAME_MS = 250L
        const val EVAL_MS = 3000L

        // WebViewClient error codes.
        const val ERROR_HOST_LOOKUP = -2
        const val ERROR_TIMEOUT = -8
        const val ERROR_FAILED_SSL_HANDSHAKE = -11

        const val HIDE_FIXED_JS = """(function(){var a=document.querySelectorAll('body *');for(var i=0;i<a.length;i++){var e=a[i],p=getComputedStyle(e).position;if(p==='fixed'||p==='sticky'){e.setAttribute('data-orbit-fixed',e.style.visibility||'');e.style.visibility='hidden';}}})();"""
        const val SHOW_FIXED_JS = """(function(){var a=document.querySelectorAll('[data-orbit-fixed]');for(var i=0;i<a.length;i++){a[i].style.visibility=a[i].getAttribute('data-orbit-fixed');a[i].removeAttribute('data-orbit-fixed');}})();"""

        /** The focused element, if it takes text: {value, label} as JSON, else null. */
        const val FOCUSED_FIELD_JS = """(function(){var e=document.activeElement;if(!e||e===document.body)return null;var t=e.tagName;var ok=e.isContentEditable||t==='TEXTAREA'||(t==='INPUT'&&!/^(button|submit|reset|checkbox|radio|file|image|range|color|hidden)$/i.test(e.type));if(!ok)return null;return JSON.stringify({value:String(e.value!==undefined?e.value:e.textContent||'').slice(0,4000),label:e.getAttribute('aria-label')||e.placeholder||e.name||''});})()"""

        /**
         * Sets the focused field's text the way typing would (through the native setter, so
         * React and co. see it), then Enter if asked: a keydown, and the form's submit unless
         * the page handled the key itself.
         */
        const val TYPE_JS = """function(text,submit){var e=document.activeElement;if(!e)return false;if(e.isContentEditable){e.textContent=text;}else{var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');if(d&&d.set)d.set.call(e,text);else e.value=text;}e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));if(submit){var k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};var go=e.dispatchEvent(new KeyboardEvent('keydown',k));e.dispatchEvent(new KeyboardEvent('keyup',k));if(go&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}}return true;}"""
    }
}
