package com.albustech.orbit.phone

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

/**
 * The page → app channel, the same one the watch's own scripts use: they post JSON strings to
 * `orbitBridge` (a WebMessageListener). Main-frame messages go to [onMessage] on the main
 * thread; `cosmetic` (element hiding) may come from any frame and is answered to that frame.
 *
 * Messages are hints from page context, never commands: what they carry is checked again on the
 * watch (result links are unwrapped, articles sanitised) before anything is shown.
 */
class Bridge(
    private val onMessage: (type: String, msg: JSONObject) -> Unit,
    private val onCosmetic: (msg: JSONObject, origin: Uri, reply: (String) -> Unit) -> Unit,
) {
    fun attach(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "This WebView has no message listener: no search cards, Reader or element hiding")
            return
        }
        WebViewCompat.addWebMessageListener(webView, "orbitBridge", setOf("*"), object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                val raw = message.data
                if (raw == null || raw.length > MAX_MESSAGE) return
                val msg = try {
                    JSONObject(raw)
                } catch (_: JSONException) {
                    return
                }
                when (val type = msg.optString("type")) {
                    // Lint can't see the WEB_MESSAGE_LISTENER check around this listener.
                    @SuppressLint("RequiresFeature")
                    "cosmetic" -> onCosmetic(msg, sourceOrigin) { css -> replyProxy.postMessage(css) }
                    "" -> Unit
                    else -> if (isMainFrame) onMessage(type, msg)
                }
            }
        })
    }

    private companion object {
        const val TAG = "OrbitBridge"

        /** Articles can be large; anything beyond this is not something we asked for. */
        const val MAX_MESSAGE = 4 * 1024 * 1024
    }
}
