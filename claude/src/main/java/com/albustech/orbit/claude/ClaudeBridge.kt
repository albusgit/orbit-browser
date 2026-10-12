package com.albustech.orbit.claude

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/** What claude.js last said about the chat on screen. */
data class PageState(
    val path: String = "",
    val title: String = "",
    /** claude.ai's message box is on the page. */
    val composer: Boolean = false,
    /** Claude is answering (the page shows its stop button). */
    val busy: Boolean = false,
    /** The box is replaced by the watch's buttons (false after a send that didn't go through). */
    val native: Boolean = false,
)

/** What the controller hears from the page, through the extension. */
interface BridgeListener {
    fun onPageState(state: PageState)

    /** A send or stop finished; [reason] says why it didn't work ("not-sent", "no-composer"). */
    fun onResult(cmd: String, id: Int, ok: Boolean, reason: String?)

    /** A bezel step ran; [edge] if the chat was already at its end. */
    fun onScrolled(edge: Boolean)
}

/**
 * The app's end of the extension: one native port ("claude") to its background script, which
 * relays to claude.js in the chat (see assets/extensions/claude/background.js).
 *
 * Commands sent before the port is up are queued; the layout is re-sent whenever the
 * background script (re)connects. Messages are hints from the page, never commands.
 */
class ClaudeBridge {

    var listener: BridgeListener? = null

    private val main = Handler(Looper.getMainLooper())
    private var port: WebExtension.Port? = null
    private val queue = ArrayList<JSONObject>()
    private var layout: JSONObject? = null
    private var nextId = 1

    fun attach(extension: WebExtension) {
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
                layout?.let(::post)
                val pending = queue.toList()
                queue.clear()
                pending.forEach(::post)
            }
            "state" -> l?.onPageState(
                PageState(
                    path = msg.optString("path").take(MAX_TEXT),
                    title = msg.optString("title").take(MAX_TEXT),
                    composer = msg.optBoolean("composer"),
                    busy = msg.optBoolean("busy"),
                    native = msg.optBoolean("native"),
                ),
            )
            "result" -> l?.onResult(msg.optString("cmd"), msg.optInt("id", -1), msg.optBoolean("ok"), msg.optString("reason").takeIf { it.isNotEmpty() && it != "null" })
            "scrolled" -> l?.onScrolled(msg.optBoolean("edge"))
            "error" -> Log.w(TAG, "Extension: ${msg.optString("cmd")} failed: ${msg.optString("message")}")
        }
    }

    // ----------------------------------------------------------- commands

    /** Puts [text] in claude.ai's message box and sends it. Returns the id its result will carry. */
    fun send(text: String): Int {
        val id = nextId++
        send(JSONObject().put("cmd", "send").put("text", text).put("id", id))
        return id
    }

    fun stop() = send(JSONObject().put("cmd", "stop").put("id", nextId++))

    /** Scrolls the conversation by [dy] CSS px (positive = down). */
    fun scroll(dy: Float) = send(JSONObject().put("cmd", "scroll").put("dy", dy.toDouble()), queueIfDown = false)

    /** CSS px at the bottom of the chat that the watch's buttons cover. */
    fun setLayout(bottom: Float) {
        val msg = JSONObject().put("cmd", "layout").put("bottom", bottom.toDouble())
        layout = msg
        send(msg, queueIfDown = false)
    }

    /** False gives the page its own message box back, to type in directly. */
    fun setNative(on: Boolean) = send(JSONObject().put("cmd", "native").put("on", on))

    private fun send(msg: JSONObject, queueIfDown: Boolean = true) {
        if (!post(msg) && queueIfDown && queue.size < MAX_QUEUE) queue.add(msg)
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
        const val TAG = "ClaudeGecko"
        const val NATIVE_APP = "claude"
        const val MAX_QUEUE = 10
        const val MAX_TEXT = 300
    }
}
