package com.albustech.orbit.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/** What the controller hears from pages, through the Orbit extension. */
interface PageListener {
    /** A message from Orbit's page scripts (extract, serp, links, reader) in [frameId] at [url]. */
    fun onPageMessage(type: String, msg: JSONObject, frameId: Int, url: String)

    /** Scroll position of the page on screen, in CSS px. */
    fun onPageMetrics(y: Int, max: Int)

    /** A tap reached the page; [interactive] if it hit a link or control. */
    fun onPageTap(interactive: Boolean)
}

/**
 * The app's end of the Orbit extension: one native port ("orbit") to its background script,
 * which relays to pages (see assets/extensions/orbit/background.js).
 *
 * Commands sent before the port is up are queued. The round-layout, WebGL and image settings are
 * also re-sent whenever the background script (re)connects, so they survive its restarts.
 *
 * Messages are hints from page context, never commands: the controller checks each one against
 * its own state, as before.
 */
class OrbitExtension {

    var listener: PageListener? = null

    /** The extension's base URL (moz-extension://<uuid>/), for the reader page. */
    var baseUrl: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var port: WebExtension.Port? = null
    private val queue = ArrayList<JSONObject>()
    private val sticky = LinkedHashMap<String, JSONObject>()

    fun attach(extension: WebExtension) {
        baseUrl = extension.metaData.baseUrl
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onConnect(p: WebExtension.Port) {
                    port = p
                    p.setDelegate(portDelegate)
                }
            },
            NATIVE_APP,
        )
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, p: WebExtension.Port) {
            val msg = message as? JSONObject ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) dispatch(msg) else main.post { dispatch(msg) }
        }

        override fun onDisconnect(p: WebExtension.Port) {
            if (port === p) port = null
        }
    }

    private fun dispatch(msg: JSONObject) {
        val l = listener
        when (msg.optString("type")) {
            "ready" -> {
                sticky.values.forEach(::post)
                val pending = queue.toList()
                queue.clear()
                pending.forEach(::post)
            }
            "page" -> {
                val raw = msg.optString("data")
                if (raw.isEmpty() || raw.length > MAX_MESSAGE) return
                val page = try {
                    JSONObject(raw)
                } catch (_: JSONException) {
                    return
                }
                val type = page.optString("type")
                if (type.isNotEmpty()) l?.onPageMessage(type, page, msg.optInt("frameId"), msg.optString("url"))
            }
            "metrics" -> if (msg.optInt("frameId") == 0) l?.onPageMetrics(msg.optInt("y"), msg.optInt("max"))
            "tap" -> if (msg.optInt("frameId") == 0) l?.onPageTap(msg.optBoolean("interactive"))
            "error" -> Log.w(TAG, "Extension: ${msg.optString("cmd")} failed: ${msg.optString("message")}")
        }
    }

    // ----------------------------------------------------------- commands

    /** Round Scroll layout for upcoming pages, or off (Zoom view, search results). */
    fun setRoundLayout(on: Boolean, d: Float, sq: Float, inset: Float) {
        val geometry = JSONObject().put("d", d.toDouble()).put("sq", sq.toDouble()).put("inset", inset.toDouble())
        send(JSONObject().put("cmd", "round").put("on", on).put("geometry", geometry), sticky = true)
    }

    /** WebGL stays refused everywhere except [allowed] sites (and their subdomains). */
    fun setWebGlAllowed(allowed: Set<String>) {
        send(JSONObject().put("cmd", "webgl").put("allow", JSONArray(allowed.sorted())), sticky = true)
    }

    fun setBlockImages(block: Boolean) {
        send(JSONObject().put("cmd", "images").put("block", block), sticky = true)
    }

    /** Runs Orbit's page scripts in the main frame: "extract" (Readability), "serp" or "links". */
    fun inject(what: String, force: Boolean = false) {
        send(JSONObject().put("cmd", "inject").put("what", what).put("force", force))
    }

    /** A command for bridge.js in the page: linksStep, linksStop, linksActivate, scrollToFraction. */
    fun page(name: String, arg: Any? = null) {
        send(JSONObject().put("cmd", "page").put("name", name).put("arg", arg ?: JSONObject.NULL))
    }

    /** A command for the reader page: turn, setStyle, goToAnchor. */
    fun reader(name: String, arg: Any? = null) {
        send(JSONObject().put("cmd", "reader").put("name", name).put("arg", arg ?: JSONObject.NULL))
    }

    fun readerPayload(token: String, payload: JSONObject) {
        send(JSONObject().put("cmd", "readerPayload").put("token", token).put("payload", payload))
    }

    private fun send(msg: JSONObject, sticky: Boolean = false) {
        if (sticky) this.sticky[msg.getString("cmd")] = msg
        if (!post(msg) && !sticky && queue.size < MAX_QUEUE) queue.add(msg)
    }

    private fun post(msg: JSONObject): Boolean {
        val p = port ?: return false
        return try {
            p.postMessage(msg)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Port closed", e)
            port = null
            false
        }
    }

    private companion object {
        const val TAG = "OrbitGecko"
        const val NATIVE_APP = "orbit"
        /** Articles can be large; anything beyond this is not something we asked for. */
        const val MAX_MESSAGE = 4 * 1024 * 1024
        const val MAX_QUEUE = 20
    }
}
