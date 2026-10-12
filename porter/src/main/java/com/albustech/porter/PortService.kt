package com.albustech.porter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Porter alive while porting: a foreground notification, a Wi-Fi lock so the phone's radio
 * doesn't doze, and a partial wake lock so the keep-alive still runs with the screen off.
 */
class PortService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Porting", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(porter.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(mode, "porter:wifi").apply { setReferenceCounted(false); acquire() }
        // Held for the whole port, which the person ends; released in onDestroy.
        @Suppress("WakelockTimeout")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "porter:port").apply { setReferenceCounted(false); acquire() }
        scope.launch {
            porter.state.map { Triple(it.phase, it.host, it.port) }.distinctUntilChanged().collect {
                nm.notify(NOTIFICATION, notification(porter.state.value))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) porter.stop()
        // Not sticky: after the process dies there's no session to resume.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun notification(s: PorterState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PortService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (s.phase) {
            Phase.Connected -> "Connected to watch"
            Phase.Off -> "Stopping…"
            else -> "Connecting to watch…"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("${s.host}:${s.port}")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "port"
        private const val NOTIFICATION = 1
        private const val ACTION_STOP = "com.albustech.porter.STOP"
    }
}
