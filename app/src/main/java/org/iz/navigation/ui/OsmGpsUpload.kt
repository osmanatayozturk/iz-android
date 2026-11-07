package org.iz.navigation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.iz.navigation.integration.osm.OsmGpxBatchResult
import org.iz.navigation.integration.osm.OsmGpxConfirmation
import org.iz.navigation.integration.osm.OsmGpxController
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import java.util.concurrent.atomic.AtomicBoolean

internal class OsmGpsUpload(private val scope: CoroutineScope) {
    data class Batch(
        val accountId: Long,
        val keys: List<OsmGpxReceiptKey>,
        val current: Int = 0,
        val result: OsmGpxBatchResult? = null,
    )

    var batch by mutableStateOf<Batch?>(null)
        private set
    var receiptRevision by mutableStateOf(0)
        private set
    val running: Boolean get() = batch?.result == null && batch != null
    private var foreground: AtomicBoolean? = null

    fun stopDispatching() {
        foreground?.set(false)
    }

    fun start(controller: OsmGpxController, confirmation: OsmGpxConfirmation) {
        if (running) return
        val started = Batch(confirmation.user.id, confirmation.traces.map { OsmGpxReceiptKey.of(it) })
        val allowed = AtomicBoolean(true)
        foreground = allowed
        batch = started
        scope.launch {
            val result = controller.upload(confirmation, allowed::get) { _, position ->
                batch = started.copy(current = position)
            }
            batch = started.copy(result = result)
            receiptRevision++
            foreground = null
        }
    }
}
