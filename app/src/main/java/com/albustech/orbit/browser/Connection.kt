package com.albustech.orbit.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** How the watch reaches the internet right now. */
enum class ConnectionType {
    WIFI, CELLULAR, BLUETOOTH, OTHER, NONE;

    /** Bluetooth through the phone is slow: give pages longer before calling it a timeout. */
    val stallTimeoutMs: Long get() = if (this == BLUETOOTH) 45_000 else 25_000

    companion object {
        /** Wi-Fi wins over cellular wins over the Bluetooth proxy when several are up. */
        fun from(wifi: Boolean, cellular: Boolean, bluetooth: Boolean, other: Boolean, connected: Boolean): ConnectionType =
            when {
                !connected -> NONE
                wifi -> WIFI
                cellular -> CELLULAR
                bluetooth -> BLUETOOTH
                other -> OTHER
                else -> NONE
            }
    }
}

/** Watches the default network. Wear's Bluetooth proxy reports TRANSPORT_BLUETOOTH. */
class ConnectionMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun current(): ConnectionType = classify(cm.getNetworkCapabilities(cm.activeNetwork))

    val type: Flow<ConnectionType> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(classify(caps))
            }

            override fun onLost(network: Network) {
                trySend(ConnectionType.NONE)
            }
        }
        trySend(current())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun classify(caps: NetworkCapabilities?): ConnectionType {
        if (caps == null) return ConnectionType.NONE
        return ConnectionType.from(
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            bluetooth = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
            other = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }
}
