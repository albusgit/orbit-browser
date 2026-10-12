package com.albustech.orbit.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.albustech.orbit.link.Input
import com.albustech.orbit.link.Json
import com.albustech.orbit.link.OpenRequest
import com.albustech.orbit.link.PageMeta
import com.albustech.orbit.link.Paths
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps the page renderer alive while the watch is browsing: a foreground service (Android
 * would otherwise freeze a background app mid-page), with a quiet notification. It stops itself
 * after [IDLE_MS] without a message from the watch.
 */
class RenderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderer: PageRenderer? = null
    private var node: String? = null
    private val idle = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        instance = this
        Blocking.load(this, scope)
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach(::handle)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handle(d: Delivery) {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
        node = d.node
        val r = renderer ?: PageRenderer(this, scope, ::send).also { renderer = it }
        val json = runCatching { Json.unpack(d.data) }.getOrNull() ?: return
        when (d.path) {
            Paths.OPEN -> runCatching { OpenRequest.from(json) }.getOrNull()?.let(r::open)
            Paths.INPUT -> Input.from(json)?.let(r::input)
        }
    }

    private fun send(path: String, bytes: ByteArray) {
        val to = node ?: return
        if (bytes.size > Paths.MAX_MESSAGE) {
            Log.w(TAG, "Not sent, too large for the Data Layer: $path ${bytes.size} B")
            return
        }
        Wearable.getMessageClient(this).sendMessage(to, path, bytes).addOnFailureListener { Log.w(TAG, "Send $path failed", it) }
    }

    override fun onDestroy() {
        main.removeCallbacks(idle)
        renderer?.destroy()
        renderer = null
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private class Delivery(val node: String, val path: String, val data: ByteArray)

    companion object {
        private const val TAG = "OrbitPhone"
        private const val CHANNEL = "pages"
        private const val NOTIFICATION_ID = 1
        private const val IDLE_MS = 2 * 60 * 1000L
        private const val MAX_PENDING = 20

        private val main = Handler(Looper.getMainLooper())
        private var instance: RenderService? = null
        private val pending = ArrayList<Delivery>()

        /** A message from the watch (any thread). Starts the service if it isn't running. */
        fun deliver(context: Context, node: String, path: String, data: ByteArray) {
            val app = context.applicationContext
            main.post {
                val d = Delivery(node, path, data)
                instance?.let {
                    it.handle(d)
                    return@post
                }
                val starting = pending.isNotEmpty()
                if (pending.size < MAX_PENDING) pending.add(d)
                if (starting) return@post
                try {
                    ContextCompat.startForegroundService(app, Intent(app, RenderService::class.java))
                } catch (e: Exception) {
                    // Android 12+ refuses background starts unless the user allowed background
                    // use (the setup screen asks). Tell the watch, which then loads pages itself.
                    Log.w(TAG, "Can't start in the background", e)
                    pending.clear()
                    val meta = PageMeta(rev = 0, url = "", error = PageMeta.UNAVAILABLE)
                    Wearable.getMessageClient(app).sendMessage(node, Paths.META, Json.pack(meta.toJson()))
                }
            }
        }
    }
}
