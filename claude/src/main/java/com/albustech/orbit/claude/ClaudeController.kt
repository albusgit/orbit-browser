package com.albustech.orbit.claude

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * claude.ai in two GeckoSessions: the chat, and at most one popup window over it.
 *
 * The popup is what makes "Continue with Google" work. Google's sign-in opens a real window
 * (window.open) and reports back to claude.ai through window.opener, then closes itself. So a
 * new-window request gets a real second session (onNewSession), shown full screen over the
 * chat, and its window.close() (onCloseRequest) takes it away again. A redirect-style sign-in
 * that finishes inside the popup on claude.ai is handed back to the chat window. Links from
 * answers that open a new window use the same popup, with a close button.
 */
class ClaudeController(
    context: Context,
    private val runtime: GeckoRuntime,
    private val bridge: ClaudeBridge,
    private val host: Host,
) : BridgeListener {

    interface Host {
        fun openOnPhone(url: String)
        fun onRendererGone(url: String?)
        /** The bezel hit the top or bottom of the chat. */
        fun onEdge()
        /** A message didn't reach claude.ai; the page's own box is back. */
        fun onSendFailed()
        /** Claude started or finished answering. */
        fun onBusyChanged(busy: Boolean)
    }

    /** A window the page opened. */
    class Popup(val session: GeckoSession) {
        var url by mutableStateOf<String?>(null)
            internal set
        var title by mutableStateOf<String?>(null)
            internal set
        var loading by mutableStateOf(true)
            internal set
        internal var canGoBack = false
    }

    // ------------------------------------------------------------------ state out

    var url by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var progress by mutableIntStateOf(0)
        private set
    /** The page that failed to load, or null. */
    var loadError by mutableStateOf<String?>(null)
        private set
    var page by mutableStateOf(PageState())
        private set
    var popup by mutableStateOf<Popup?>(null)
        private set
    private var canGoBack = false

    // ------------------------------------------------------------------ engine

    private val settings = GeckoSessionSettings.Builder()
        .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .useTrackingProtection(true)
        .suspendMediaWhenInactive(true)
        .build()

    private val session = GeckoSession(settings)

    /** The chat. Compose shows it in an AndroidView. */
    val view: GeckoView = blankView(context)

    /** The popup window, shown over the chat while [popup] is set. */
    val popupView: GeckoView = blankView(context)

    private val main = Handler(Looper.getMainLooper())

    init {
        session.navigationDelegate = MainNavigation()
        session.progressDelegate = MainProgress()
        session.contentDelegate = MainContent()
        session.promptDelegate = Prompts(context)
        session.open(runtime)
        view.setSession(session)
        runtime.webExtensionController.setTabActive(session, true)
        bridge.listener = this
    }

    // ------------------------------------------------------------------ actions

    fun load(target: String) {
        loadError = null
        session.loadUri(target)
    }

    fun reload() {
        loadError = null
        if (url == null) load(ClaudeUrls.HOME) else session.reload()
    }

    /** Sends [text] as a message in the chat on screen. */
    fun send(text: String) {
        if (text.isBlank()) return
        bridge.send(text.trim())
    }

    fun stop() = bridge.stop()

    /** Shows claude.ai's own message box again (to type in the page). */
    fun usePageComposer() = bridge.setNative(false)

    /** Back: the popup's history, then the popup itself, then the chat's history. False if nothing is left. */
    fun back(): Boolean {
        popup?.let { p ->
            if (p.canGoBack) p.session.goBack() else closePopup()
            return true
        }
        if (canGoBack) {
            session.goBack()
            return true
        }
        return false
    }

    /**
     * Bezel scrolling, [px] screen pixels. claude.ai scrolls the conversation in an inner
     * element that Gecko's pan/zoom can't reach, so in the chat claude.js scrolls it.
     */
    fun scrollBy(px: Float, density: Float) {
        val p = popup
        when {
            p != null -> panZoomScroll(p.session, px)
            ClaudeUrls.isClaude(url) && page.composer -> bridge.scroll(px / density)
            else -> panZoomScroll(session, px)
        }
    }

    private fun panZoomScroll(s: GeckoSession, px: Float) {
        s.panZoomController.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(px.toDouble()), PanZoomController.SCROLL_BEHAVIOR_SMOOTH)
    }

    fun closePopup() {
        val p = popup ?: return
        popup = null
        if (popupView.session === p.session) popupView.releaseSession()
        p.session.close()
    }

    /** Stops painting and timers (Activity stopped). The popup keeps its place. */
    fun pause() {
        session.setActive(false)
        popup?.session?.setActive(false)
    }

    fun resume() {
        session.setActive(true)
        popup?.session?.setActive(true)
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        if (bridge.listener === this) bridge.listener = null
        closePopup()
        runtime.webExtensionController.setTabActive(session, false)
        view.releaseSession()
        session.close()
    }

    // ------------------------------------------------------------------ bridge

    override fun onPageState(state: PageState) {
        val wasBusy = page.busy
        page = state
        if (wasBusy != state.busy) host.onBusyChanged(state.busy)
    }

    override fun onResult(cmd: String, id: Int, ok: Boolean, reason: String?) {
        if (cmd == "send" && !ok) {
            Log.w(TAG, "Send failed: $reason")
            host.onSendFailed()
        }
    }

    override fun onScrolled(edge: Boolean) {
        if (edge) host.onEdge()
    }

    // ------------------------------------------------------------------ popup

    private fun openPopup(uri: String): GeckoSession {
        closePopup()
        // A window the page opened shares its opener's settings (and so its cookies).
        val s = GeckoSession(GeckoSessionSettings(session.settings))
        val p = Popup(s).also { it.url = uri }
        s.navigationDelegate = PopupNavigation(p)
        s.progressDelegate = PopupProgress(p)
        s.contentDelegate = PopupContent(p)
        s.promptDelegate = Prompts(view.context)
        popup = p
        // Gecko opens the session once onNewSession's result is in; only then may a view show it.
        attachWhenOpen(p, 0)
        return s
    }

    private fun attachWhenOpen(p: Popup, tries: Int) {
        if (popup !== p) return
        when {
            p.session.isOpen -> popupView.setSession(p.session)
            tries < ATTACH_TRIES -> main.postDelayed({ attachWhenOpen(p, tries + 1) }, ATTACH_STEP_MS)
            else -> {
                Log.w(TAG, "Popup never opened")
                closePopup()
            }
        }
    }

    /** A redirect-style sign-in that ended on claude.ai inside the popup: show it in the chat. */
    private fun maybeHandBack(p: Popup) {
        val at = p.url
        if (!ClaudeUrls.popupFinished(at)) return
        main.postDelayed({
            // Still open after a moment: the opener isn't going to close it.
            if (popup === p && p.url == at && at != null) {
                closePopup()
                load(at)
            }
        }, HAND_BACK_MS)
    }

    // ------------------------------------------------------------------ delegates

    private inner class MainNavigation : NavigationDelegate {

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
            // Web pages load; a new window goes on to onNewSession, which opens the popup.
            commonLoadRequest(request.uri) { fallback -> load(fallback) }

        override fun onSubframeLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
            if (request.uri.toUri().scheme?.lowercase() in SUBFRAME_SCHEMES) GeckoResult.allow() else GeckoResult.deny()

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? =
            if (ClaudeUrls.isWeb(uri) || uri == ABOUT_BLANK) GeckoResult.fromValue(openPopup(uri)) else null

        override fun onLocationChange(
            session: GeckoSession,
            location: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (location != null && location != ABOUT_BLANK) url = location
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@ClaudeController.canGoBack = canGoBack
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            Log.w(TAG, "Load error ${error.code} at $uri")
            loadError = uri ?: url ?: ClaudeUrls.HOME
            loading = false
            return null // the app draws its own error over Gecko's page
        }
    }

    private inner class MainProgress : ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (url == ABOUT_BLANK) return
            loading = true
            progress = 0
            loadError = null
            this@ClaudeController.url = url
            // claude.js reports the new page's state once it runs; until then, no chat buttons.
            onPageState(PageState())
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            loading = false
            progress = 100
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            this@ClaudeController.progress = progress
        }
    }

    private inner class MainContent : ContentDelegate {
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            // Downloads (exported chats, files from answers): the phone is better at files.
            host.openOnPhone(response.uri)
        }

        override fun onCrash(session: GeckoSession) {
            Log.w(TAG, "Content process crashed at $url")
            host.onRendererGone(url)
        }

        override fun onKill(session: GeckoSession) {
            // On a 2 GB watch the system may kill the content process to reclaim memory.
            Log.w(TAG, "Content process killed at $url")
            host.onRendererGone(url)
        }
    }

    private inner class PopupNavigation(private val p: Popup) : NavigationDelegate {

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            if (request.target == NavigationDelegate.TARGET_WINDOW_NEW && ClaudeUrls.isWeb(request.uri)) {
                // One popup at a time: a window from the popup opens in the popup.
                val uri = request.uri
                main.post { if (popup === p) p.session.loadUri(uri) }
                return GeckoResult.deny()
            }
            return commonLoadRequest(request.uri) { fallback -> if (popup === p) p.session.loadUri(fallback) }
        }

        override fun onSubframeLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
            if (request.uri.toUri().scheme?.lowercase() in SUBFRAME_SCHEMES) GeckoResult.allow() else GeckoResult.deny()

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null

        override fun onLocationChange(
            session: GeckoSession,
            location: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (location != null && location != ABOUT_BLANK) p.url = location
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            p.canGoBack = canGoBack
        }
    }

    private inner class PopupProgress(private val p: Popup) : ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            p.loading = true
            if (url != ABOUT_BLANK) p.url = url
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            p.loading = false
            if (success) maybeHandBack(p)
        }
    }

    private inner class PopupContent(private val p: Popup) : ContentDelegate {
        /** window.close(): Google's sign-in window is done. */
        override fun onCloseRequest(session: GeckoSession) {
            if (popup === p) closePopup()
        }

        override fun onTitleChange(session: GeckoSession, title: String?) {
            p.title = title
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            host.openOnPhone(response.uri)
        }

        override fun onCrash(session: GeckoSession) {
            if (popup === p) closePopup()
        }

        override fun onKill(session: GeckoSession) {
            if (popup === p) closePopup()
        }
    }

    /**
     * What any window may load: web pages and the page-internal schemes. intent:// links follow
     * their web fallback through [loadFallback]; mail, phone and store links go to the phone.
     */
    private fun commonLoadRequest(uri: String, loadFallback: (String) -> Unit): GeckoResult<AllowOrDeny> =
        when (uri.toUri().scheme?.lowercase()) {
            "http", "https", "about", "data", "blob", "moz-extension", "resource" -> GeckoResult.allow()
            "intent" -> {
                val fallback = FALLBACK.find(uri)?.groupValues?.get(1)?.let(Uri::decode)
                if (fallback != null && ClaudeUrls.isWeb(fallback)) main.post { loadFallback(fallback) }
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

    companion object {
        private const val TAG = "ClaudeBrowser"
        private const val ABOUT_BLANK = "about:blank"
        private val FALLBACK = Regex("S\\.browser_fallback_url=([^;]+)")
        private val SUBFRAME_SCHEMES = setOf("http", "https", "about", "data", "blob")
        private const val ATTACH_TRIES = 100
        private const val ATTACH_STEP_MS = 20L
        private const val HAND_BACK_MS = 1500L

        private fun blankView(context: Context) = GeckoView(context).apply {
            coverUntilFirstPaint(Color.BLACK)
            setBackgroundColor(Color.BLACK)
        }
    }
}
