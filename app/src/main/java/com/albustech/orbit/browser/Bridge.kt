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
 */
class Bridge(private val onMessage: (type: String, msg: JSONObject) -> Unit) {

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
                        if (isMainFrame) dispatch(message.data)
                    }
                },
            )
        } else {
            webView.addJavascriptInterface(NativeBridge(), "OrbitNative")
        }
    }

    private fun dispatch(raw: String?) {
        if (raw == null || raw.length > MAX_MESSAGE) return
        val msg = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return
        }
        val type = msg.optString("type")
        if (type.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) onMessage(type, msg) else main.post { onMessage(type, msg) }
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun post(raw: String?) = dispatch(raw)
    }

    private companion object {
        /** Articles can be large; anything beyond this is not something we asked for. */
        const val MAX_MESSAGE = 4 * 1024 * 1024
    }
}

/** Debug logging helper for message traffic. */
internal fun logBridge(type: String, msg: JSONObject) {
    if (com.albustech.orbit.BuildConfig.DEBUG && type != "article") Log.v("OrbitBridge", "$type $msg")
}
