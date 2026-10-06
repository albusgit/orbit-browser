package com.albustech.orbit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.albustech.orbit.browser.BrowserController
import com.albustech.orbit.browser.BrowserHost
import com.albustech.orbit.browser.ConnectionMonitor
import com.albustech.orbit.browser.Injector
import com.albustech.orbit.browser.MemoryLog
import com.albustech.orbit.browser.OrbitWebView
import com.albustech.orbit.browser.TabManager
import com.albustech.orbit.data.ReaderStyle
import com.albustech.orbit.data.Settings
import com.albustech.orbit.input.BezelInput
import com.albustech.orbit.tile.OrbitTileService
import com.albustech.orbit.ui.AppActions
import com.albustech.orbit.ui.BrowserScreen
import com.albustech.orbit.ui.ErrorScreen
import com.albustech.orbit.ui.OrbitDeps
import com.albustech.orbit.ui.RoundGeometry
import kotlinx.coroutines.launch

/**
 * Orbit's single Activity. Owns the one WebView for the app's lifetime (config changes are
 * handled in place, see the manifest), pauses it whenever the screen isn't ours, and handles
 * ambient mode, keep-screen-on and hand-off to the phone.
 */
class MainActivity : ComponentActivity(), BrowserHost, AppActions {

    private var webView: WebView? = null
    private var controller: BrowserController? = null
    private var tabs: TabManager? = null
    private var bezel: BezelInput? = null
    private var ambient by mutableStateOf(false)
    private var keepScreenOn = false

    private val handler = Handler(Looper.getMainLooper())
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private val remote by lazy { RemoteActivityHelper(this, ContextCompat.getMainExecutor(this)) }

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                // Dim black screen; the page and its position stay exactly as they are.
                ambient = true
                controller?.pause()
                webView?.let {
                    it.onPause()
                    it.pauseTimers()
                }
            }

            override fun onExitAmbient() {
                webView?.let {
                    it.resumeTimers()
                    it.onResume()
                }
                controller?.resume()
                ambient = false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OrbitApp
        val geometry = currentGeometry()
        lifecycle.addObserver(ambientObserver)

        val view = OrbitWebView.create(this)
        webView = view
        if (view == null) {
            setContent {
                MaterialTheme {
                    ErrorScreen(geometry, stringResource(R.string.error_no_webview), detail = null, onRetry = null)
                }
            }
            return
        }

        val connection = ConnectionMonitor(this)
        val browser = BrowserController(
            webView = view,
            injector = Injector(this, geometry),
            repo = app.repository,
            scope = lifecycleScope,
            memoryLog = MemoryLog(this, lifecycleScope),
            connection = connection,
            host = this,
        )
        controller = browser
        val tabManager = TabManager(this, app.database.tabs(), lifecycleScope, browser)
        tabs = tabManager
        val input = BezelInput(view, geometry).also { it.attach() }
        bezel = input

        lifecycleScope.launch {
            app.settings.settings.collect { s ->
                browser.searchTemplate = s.searchTemplate
                browser.readerStyle = s.reader
                browser.setBlocking(s.blockTrackers)
                view.settings.textZoom = s.textZoom
                keepScreenOn = s.keepScreenOn
                if (!keepScreenOn) releaseScreen.run()
            }
        }

        val restored = savedInstanceState?.let(browser::restoreState) ?: false
        if (!restored) intent?.let(::handleIntent)

        val deps = OrbitDeps(browser, input, tabManager, app.repository, app.settings, geometry, connection, this)
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            MaterialTheme {
                BrowserScreen(deps, settings, ambient)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * VIEW intents from other apps, and the Tile's "open this" extra. The Activity is exported,
     * so anything here may come from another app: web URLs only (never javascript: and co.).
     */
    private fun handleIntent(intent: Intent) {
        val url = when {
            intent.action == Intent.ACTION_VIEW -> intent.dataString
            else -> intent.getStringExtra(EXTRA_OPEN_URL)
        }?.takeIf(BrowserController::isWebUrl) ?: return
        controller?.loadUrl(url)
    }

    override fun onStart() {
        super.onStart()
        webView?.let {
            it.onResume()
            it.resumeTimers()
        }
        controller?.resume()
    }

    override fun onStop() {
        // Nothing on a hidden watch screen should run: pause the page and all JS timers.
        controller?.pause()
        webView?.let {
            it.onPause()
            it.pauseTimers()
        }
        releaseScreen.run()
        // The tile shows the last page read and bookmarks: refresh it as we leave.
        OrbitTileService.requestUpdate(this)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only UI_HIDDEN and BACKGROUND are still delivered on API 34+.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) webView?.clearCache(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        controller?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        controller = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ BrowserHost

    /**
     * The renderer was killed (usually to free memory). A WebView can't outlive its renderer,
     * so rebuild the Activity with a fresh one and reopen the page, once. If the same page
     * takes the renderer down again, land on the home screen instead of looping.
     */
    override fun onRendererGone(url: String?) {
        handler.post {
            // Give up reopening pages if the renderer keeps dying (a page that crashes it
            // after a redirect would otherwise loop).
            val now = SystemClock.elapsedRealtime()
            recoveries.removeAll { now - it > RECOVERY_WINDOW_MS }
            recoveries.add(now)
            val repeat = recoveries.size > MAX_RECOVERIES
            controller?.pause() // drop its timers before the WebView goes
            controller = null // keeps onSaveInstanceState away from the dead WebView
            webView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            webView = null
            intent = if (url != null && !repeat) {
                Intent(Intent.ACTION_VIEW, url.toUri(), this, MainActivity::class.java)
            } else {
                Intent(this, MainActivity::class.java)
            }
            recreate()
        }
    }

    override fun openOnPhone(url: String) = openOnPhone(url) { ok -> if (ok) toast(getString(R.string.action_open_on_phone)) }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onEdge() {
        bezel?.haptics?.edge()
    }

    override fun onPageCommitted(url: String) {
        val app = application as OrbitApp
        lifecycleScope.launch { app.settings.setLastUrl(url) }
        tabs?.onPageCommitted(url, controller?.title)
    }

    override fun onReaderStyleChanged(style: ReaderStyle) {
        val app = application as OrbitApp
        lifecycleScope.launch { app.settings.setReaderStyle(style) }
    }

    // ------------------------------------------------------------ AppActions

    override fun openOnPhone(url: String, done: (Boolean) -> Unit) {
        val target = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val future = remote.startRemoteActivity(target)
        future.addListener({
            val ok = try {
                future.get()
                true
            } catch (_: Exception) {
                false
            }
            if (!ok) toast(getString(R.string.phone_unavailable))
            done(ok)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun copyLink(url: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", url))
        toast(getString(R.string.copied))
    }

    /** Keep-screen-on while reading: each bezel turn or scroll buys another stretch. */
    override fun onUserActivity() {
        if (!keepScreenOn || ambient) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(releaseScreen)
        handler.postDelayed(releaseScreen, KEEP_ON_MS)
    }

    private fun currentGeometry(): RoundGeometry {
        val bounds = windowManager.currentWindowMetrics.bounds
        return RoundGeometry(bounds.width(), bounds.height(), resources.displayMetrics.density)
    }

    companion object {
        /** Extra carrying a URL to open (Tile, complication). */
        const val EXTRA_OPEN_URL = "com.albustech.orbit.OPEN_URL"
        /** Renderer losses in this process, for the give-up rule. */
        private val recoveries = ArrayList<Long>()
        private const val MAX_RECOVERIES = 2
        private const val RECOVERY_WINDOW_MS = 60_000L

        /** How long the screen stays on after the last bezel turn when keep-screen-on is set. */
        private const val KEEP_ON_MS = 2 * 60 * 1000L
    }
}
