package com.albustech.orbit.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

/**
 * The page → app channel. Injected scripts post JSON strings to `orbitBridge` (a
 * WebMessageListener, available to main-frame scripts only) or, on old WebViews,
 * `OrbitNative.post`. Messages are delivered on the main thread.
 *
 * Messages are hints from page context, never commands: the controller checks each one
 * against its own state (was extraction requested? is a reader page showing?).
 *
 * The one exception to "main frame only" is `cosmetic` (element hiding): any frame may ask, and
 * gets its answer back through [onFrameRequest]'s reply, which goes to that frame alone.
 */
class Bridge(
    private val onMessage: (type: String, msg: JSONObject) -> Unit,
    private val onFrameRequest: (type: String, msg: JSONObject, origin: Uri, reply: (String) -> Unit) -> Unit = { _, _, _, _ -> },
) {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun attach(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                "orbitBridge",
                setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        val (type, msg) = parse(message.data) ?: return
                        if (type in FRAME_REQUESTS) {
                            // Lint can't see the WEB_MESSAGE_LISTENER check around this listener.
                            @SuppressLint("RequiresFeature")
                            val reply = { css: String -> replyProxy.postMessage(css) }
                            onFrameRequest(type, msg, sourceOrigin, reply)
                        } else if (isMainFrame) {
                            deliver(type, msg)
                        }
                    }
                },
            )
        } else {
            webView.addJavascriptInterface(NativeBridge(), "OrbitNative")
        }
    }

    private fun parse(raw: String?): Pair<String, JSONObject>? {
        if (raw == null || raw.length > MAX_MESSAGE) return null
        val msg = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return null
        }
        val type = msg.optString("type")
        return if (type.isEmpty()) null else type to msg
    }

    private fun deliver(type: String, msg: JSONObject) {
        if (Looper.myLooper() == Looper.getMainLooper()) onMessage(type, msg) else main.post { onMessage(type, msg) }
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun post(raw: String?) {
            // No reply channel here, so frame requests (element hiding) simply go unanswered.
            val (type, msg) = parse(raw) ?: return
            if (type !in FRAME_REQUESTS) deliver(type, msg)
        }
    }

    private companion object {
        /** Articles can be large; anything beyond this is not something we asked for. */
        const val MAX_MESSAGE = 4 * 1024 * 1024

        /** Message types any frame may send; each gets a reply instead of a main-thread callback. */
        val FRAME_REQUESTS = setOf("cosmetic")
    }
}

/** Debug logging helper for message traffic. */
internal fun logBridge(type: String, msg: JSONObject) {
    if (com.albustech.orbit.BuildConfig.DEBUG && type != "article") Log.v("OrbitBridge", "$type $msg")
}
