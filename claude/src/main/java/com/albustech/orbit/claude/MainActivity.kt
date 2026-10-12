package com.albustech.orbit.claude

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.wear.compose.material3.Text
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.albustech.orbit.claude.input.Bezel
import com.albustech.orbit.claude.ui.ChatBarHeightDp
import com.albustech.orbit.claude.ui.ClaudeScreen
import com.albustech.orbit.claude.ui.ClaudeTheme
import com.albustech.orbit.claude.ui.ScreenActions
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The app's single Activity. Owns the chat's GeckoSession (and its popup) for its lifetime;
 * config changes are handled in place (see the manifest). The GeckoRuntime, and with it the
 * sign-in cookies, lives in [ClaudeRuntime] for the whole process.
 */
class MainActivity : ComponentActivity(), ClaudeController.Host, ScreenActions {

    private var controller: ClaudeController? = null
    private var bezel: Bezel? = null
    private val handler = Handler(Looper.getMainLooper())
    private val remote by lazy { RemoteActivityHelper(this, ContextCompat.getMainExecutor(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtime = try {
            ClaudeRuntime.get(this)
        } catch (e: Throwable) {
            // Gecko's native libraries failed to load (wrong ABI, damaged install).
            Log.e(TAG, "Engine failed to start", e)
            setContent { ClaudeTheme { EngineError(e.javaClass.simpleName) } }
            return
        }

        val c = ClaudeController(this, runtime, ClaudeRuntime.bridge, this)
        controller = c
        // The conversation is padded at the bottom by what the chat buttons cover.
        ClaudeRuntime.bridge.setLayout(ChatBarHeightDp.value)

        val density = resources.displayMetrics.density
        val bounds = windowManager.currentWindowMetrics.bounds
        // One bezel click scrolls a quarter of the readable square, as in Orbit.
        val stepPx = (minOf(bounds.width(), bounds.height()) / sqrt(2f) / 4f).roundToInt().coerceAtLeast(1)
        val input = Bezel(c.view).also { it.attach(c.view, c.popupView) }
        input.onDetents = { detents -> c.scrollBy(detents * stepPx.toFloat(), density) }
        bezel = input

        c.load(intent?.getStringExtra(EXTRA_URL)?.takeIf(ClaudeUrls::isClaude) ?: ClaudeUrls.HOME)

        setContent {
            ClaudeTheme { ClaudeScreen(c, input, this) }
        }
    }

    override fun onStart() {
        super.onStart()
        controller?.resume()
    }

    override fun onStop() {
        // Nothing on a hidden watch screen should run: an inactive session stops painting.
        controller?.pause()
        keepScreenOn(false)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.let {
            (it.view.parent as? ViewGroup)?.removeView(it.view)
            (it.popupView.parent as? ViewGroup)?.removeView(it.popupView)
            it.destroy()
        }
        controller = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ ClaudeController.Host

    /**
     * The content process was killed (usually to free memory) or crashed. Rebuild the Activity
     * with a fresh session and reopen the chat, unless it keeps happening.
     */
    override fun onRendererGone(url: String?) {
        handler.post {
            val now = SystemClock.elapsedRealtime()
            recoveries.removeAll { now - it > RECOVERY_WINDOW_MS }
            recoveries.add(now)
            controller?.let {
                (it.view.parent as? ViewGroup)?.removeView(it.view)
                it.destroy()
            }
            controller = null
            intent = Intent(this, MainActivity::class.java).apply {
                if (url != null && recoveries.size <= MAX_RECOVERIES) putExtra(EXTRA_URL, url)
            }
            recreate()
        }
    }

    override fun onEdge() {
        bezel?.haptics?.edge()
    }

    override fun onSendFailed() {
        Toast.makeText(this, R.string.send_failed, Toast.LENGTH_LONG).show()
    }

    /** The screen stays on while Claude answers, and a tap on the wrist says the answer is in. */
    override fun onBusyChanged(busy: Boolean) {
        keepScreenOn(busy)
        if (!busy) bezel?.haptics?.confirm()
    }

    override fun openOnPhone(url: String) {
        val target = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val future = remote.startRemoteActivity(target)
        future.addListener({
            val ok = try {
                future.get()
                true
            } catch (_: Exception) {
                false
            }
            Toast.makeText(this, if (ok) R.string.opened_on_phone else R.string.phone_unavailable, Toast.LENGTH_SHORT).show()
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------ ScreenActions

    override fun finishApp() = finish()

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        private const val TAG = "ClaudeGecko"
        /** The chat to reopen after the renderer was lost. */
        private const val EXTRA_URL = "com.albustech.orbit.claude.URL"
        private val recoveries = ArrayList<Long>()
        private const val MAX_RECOVERIES = 2
        private const val RECOVERY_WINDOW_MS = 60_000L
    }
}

@androidx.compose.runtime.Composable
private fun EngineError(detail: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(androidx.compose.ui.res.stringResource(R.string.error_engine), textAlign = TextAlign.Center)
        Text(detail, textAlign = TextAlign.Center)
    }
}
