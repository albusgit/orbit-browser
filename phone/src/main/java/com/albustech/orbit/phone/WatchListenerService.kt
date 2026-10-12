package com.albustech.orbit.phone

import com.albustech.orbit.link.Paths
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wakes the helper when Orbit on the watch sends something. The Data Layer only delivers
 * messages between apps with the same applicationId and signing key.
 */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Paths.OPEN, Paths.INPUT -> RenderService.deliver(this, event.sourceNodeId, event.path, event.data)
        }
    }
}
