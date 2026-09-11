package org.iz.navigation.wear

import org.iz.navigation.wearprotocol.WearProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class WearCommandListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == org.iz.navigation.wearprotocol.WatchHealthProtocol.PATH) {
            runBlocking { withTimeoutOrNull(25_000) {
                runCatching { WearPhoneBridge.get(this@WearCommandListener).receiveHealth(event.sourceNodeId, event.data) }
            } }
            return
        }
        val version = WearProtocol.commandVersion(event.path) ?: return
        if (WearProtocol.frameVersion(event.data) != version) return
        // WearableListenerService dispatches here on a background thread and keeps this callback alive.
        runBlocking { withTimeoutOrNull(25_000) { WearPhoneBridge.get(this@WearCommandListener)
            .receive(event.sourceNodeId, event.data) } }
    }
}
