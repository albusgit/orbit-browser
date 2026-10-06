package com.albustech.orbit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.albustech.orbit.browser.MemoryLog
import com.albustech.orbit.browser.OrbitRuntime
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
 * Orbit's single Activity. Owns the one GeckoSession for its lifetime (config changes are
 * handled in place, see the manifest), pauses it whenever the screen isn't ours, and handles
 * ambient mode, keep-screen-on and hand-off to the phone. The GeckoRuntime lives in
 * [OrbitRuntime] for the whole process.
 */
class MainActivity : ComponentActivity(), BrowserHost, AppActions {

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
            }

            override fun onExitAmbient() {
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

        val runtime = try {
            OrbitRuntime.get(this)
        } catch (e: Throwable) {
            // Gecko's native libraries failed to load (wrong ABI, damaged install).
            Log.e("OrbitGecko", "Engine failed to start", e)
            setContent {
                MaterialTheme {
                    ErrorScreen(geometry, getString(R.string.error_engine), detail = e.javaClass.simpleName, onRetry = ::recreate)
                }
            }
            return
        }

        val connection = ConnectionMonitor(this)
        val browser = BrowserController(
            context = this,
            runtime = runtime,
            extension = OrbitRuntime.extension,
            geometry = geometry,
            repo = app.repository,
            scope = lifecycleScope,
            memoryLog = MemoryLog(this, lifecycleScope),
            connection = connection,
            host = this,
        )
        controller = browser
        val tabManager = TabManager(this, app.database.tabs(), lifecycleScope, browser)
        tabs = tabManager
        val input = BezelInput(browser.view, geometry).also { it.attach() }
        input.wasInteractiveTap = browser::recentInteractiveTap
        input.hadContextMenu = browser::recentContextMenu
        bezel = input

        lifecycleScope.launch {
            app.settings.settings.collect { s ->
                browser.searchTemplate = s.searchTemplate
                browser.readerStyle = s.reader
                browser.setBlocking(s.blockTrackers)
                runtime.settings.fontSizeFactor = s.textZoom / 100f
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
        controller?.resume()
    }

    override fun onStop() {
        // Nothing on a hidden watch screen should run: an inactive session stops painting and
        // throttles the page's timers.
        controller?.pause()
        releaseScreen.run()
        // The tile shows the last page read and bookmarks: refresh it as we leave.
        OrbitTileService.requestUpdate(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        controller?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bezel?.detach()
        controller?.let {
            (it.view.parent as? ViewGroup)?.removeView(it.view)
            it.destroy()
        }
        controller = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ BrowserHost

    /**
     * The content process was killed (usually to free memory) or crashed. Rebuild the Activity
     * with a fresh session and reopen the page, once. If the same page takes it down again,
     * land on the home screen instead of looping.
     */
    override fun onRendererGone(url: String?) {
        handler.post {
            // Give up reopening pages if the renderer keeps dying (a page that crashes it
            // after a redirect would otherwise loop).
            val now = SystemClock.elapsedRealtime()
            recoveries.removeAll { now - it > RECOVERY_WINDOW_MS }
            recoveries.add(now)
            val repeat = recoveries.size > MAX_RECOVERIES
            controller?.let {
                it.pause() // drop its timers before the session goes
                (it.view.parent as? ViewGroup)?.removeView(it.view)
                it.destroy()
            }
            controller = null // keeps onSaveInstanceState away from the dead session
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

    override fun onLinkLongPress(url: String, title: String?) {
        bezel?.onLinkLongPress?.invoke(url, title)
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
