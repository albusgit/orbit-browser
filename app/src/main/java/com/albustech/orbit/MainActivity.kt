package com.albustech.orbit

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.MaterialTheme
import com.albustech.orbit.browser.BrowserController
import com.albustech.orbit.browser.Injector
import com.albustech.orbit.browser.MemoryLog
import com.albustech.orbit.browser.OrbitWebView
import com.albustech.orbit.data.Settings
import com.albustech.orbit.input.BezelInput
import com.albustech.orbit.ui.BrowserScreen
import com.albustech.orbit.ui.ErrorScreen
import com.albustech.orbit.ui.RoundGeometry
import kotlinx.coroutines.launch

/**
 * Orbit's single Activity. Owns the one WebView for the app's lifetime (config changes are
 * handled in place, see the manifest) and pauses it whenever the screen isn't ours.
 */
class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var controller: BrowserController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OrbitApp
        val geometry = currentGeometry()

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

        val browser = BrowserController(
            webView = view,
            injector = Injector(this, geometry),
            memoryLog = MemoryLog(this, lifecycleScope),
            onPageCommitted = { url -> lifecycleScope.launch { app.settings.setLastUrl(url) } },
            onRendererGone = { url, _ -> window.decorView.post { recoverFromRendererLoss(url) } },
        )
        controller = browser
        val bezel = BezelInput(view, geometry).also { it.attach() }

        lifecycleScope.launch {
            app.settings.settings.collect { s ->
                browser.searchTemplate = s.searchTemplate
                browser.setTextZoom(s.textZoom)
            }
        }

        val restored = savedInstanceState?.let(browser::restoreState) ?: false
        if (!restored) intent?.viewUrl()?.let(browser::loadUrl)

        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            MaterialTheme {
                BrowserScreen(browser, bezel, geometry, lastUrl = settings.lastUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.viewUrl()?.let { controller?.loadUrl(it) }
    }

    override fun onStart() {
        super.onStart()
        webView?.let {
            it.onResume()
            it.resumeTimers()
        }
    }

    override fun onStop() {
        // Nothing on a hidden watch screen should run: pause the page and all JS timers.
        webView?.let {
            it.onPause()
            it.pauseTimers()
        }
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
        webView?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        controller = null
        super.onDestroy()
    }

    /**
     * The renderer was killed (usually to free memory). A WebView can't outlive its renderer,
     * so rebuild the Activity with a fresh one and reopen the page, once. If the same page
     * takes the renderer down again, land on the home screen instead of looping.
     */
    private fun recoverFromRendererLoss(url: String?) {
        val repeat = intent.getBooleanExtra(EXTRA_RECOVERED, false) && intent.dataString == url
        controller = null // keeps onSaveInstanceState away from the dead WebView
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        intent = if (url != null && !repeat) {
            Intent(Intent.ACTION_VIEW, url.toUri(), this, MainActivity::class.java).putExtra(EXTRA_RECOVERED, true)
        } else {
            Intent(this, MainActivity::class.java)
        }
        recreate()
    }

    private fun currentGeometry(): RoundGeometry {
        val bounds = windowManager.currentWindowMetrics.bounds
        return RoundGeometry(bounds.width(), bounds.height(), resources.displayMetrics.density)
    }

    private fun Intent.viewUrl(): String? =
        if (action == Intent.ACTION_VIEW) data?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString() else null

    private companion object {
        const val EXTRA_RECOVERED = "com.albustech.orbit.RECOVERED_FROM_RENDERER_LOSS"
    }
}
