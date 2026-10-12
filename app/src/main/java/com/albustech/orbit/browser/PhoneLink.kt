package com.albustech.orbit.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.albustech.orbit.link.FieldFocus
import com.albustech.orbit.link.Json
import com.albustech.orbit.link.PageMeta
import com.albustech.orbit.link.Paths
import com.albustech.orbit.link.Tile
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * The watch's end of the phone helper (Orbit Phone, see phone/ and com.albustech.orbit.link):
 * finds a reachable phone running it, sends it what to open and what the user does, and hands
 * back what it renders. Callbacks arrive on the main thread.
 */
class PhoneLink(context: Context) : MessageClient.OnMessageReceivedListener, CapabilityClient.OnCapabilityChangedListener {

    interface Listener {
        fun onMeta(meta: PageMeta)
        fun onTile(tile: Tile)
        fun onSerp(msg: JSONObject)
        fun onArticle(msg: JSONObject)
        fun onFocus(focus: FieldFocus)
    }

    private val messages = Wearable.getMessageClient(context.applicationContext)
    private val capabilities = Wearable.getCapabilityClient(context.applicationContext)
    private var node: String? = null

    /** A phone with the helper is reachable now. */
    var connected by mutableStateOf(false)
        private set

    var listener: Listener? = null

    fun start() {
        messages.addListener(this)
        capabilities.addListener(this, Paths.CAPABILITY)
        capabilities.getCapability(Paths.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { pick(it.nodes) }
            .addOnFailureListener { Log.i(TAG, "No Data Layer here: pages load on the watch", it) }
    }

    fun stop() {
        messages.removeListener(this)
        capabilities.removeListener(this, Paths.CAPABILITY)
    }

    /** Sends [json] to the phone; false if none is reachable. */
    fun send(path: String, json: JSONObject): Boolean {
        val to = node ?: return false
        messages.sendMessage(to, path, Json.pack(json)).addOnFailureListener { Log.w(TAG, "Send $path failed", it) }
        return true
    }

    override fun onCapabilityChanged(info: CapabilityInfo) = pick(info.nodes)

    private fun pick(nodes: Set<Node>) {
        node = (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
        connected = node != null
    }

    override fun onMessageReceived(event: MessageEvent) {
        val l = listener ?: return
        try {
            when (event.path) {
                Paths.TILE -> Tile.decode(event.data)?.let(l::onTile)
                Paths.META -> l.onMeta(PageMeta.from(Json.unpack(event.data)))
                Paths.SERP -> l.onSerp(Json.unpack(event.data))
                Paths.ARTICLE -> l.onArticle(Json.unpack(event.data))
                Paths.FOCUS -> l.onFocus(FieldFocus.from(Json.unpack(event.data)))
            }
        } catch (e: Exception) {
            // A malformed message is dropped; the page times out to the watch's engine if need be.
            Log.w(TAG, "Bad message on ${event.path}", e)
        }
    }

    private companion object {
        const val TAG = "OrbitPhoneLink"
    }
}
