package org.iz.navigation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.iz.navigation.integration.PreparedOsmGpxTrace
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
    var running by mutableStateOf(false)
        private set
    private var accountId: Long? = null
    private var accountGeneration = 0
    private var foreground: AtomicBoolean? = null

    fun bind(account: Long?) {
        if (accountId == account) return
        accountId = account
        accountGeneration++
        stopDispatching()
        batch = null
    }

    fun stopDispatching() {
        foreground?.set(false)
    }

    fun start(
        controller: OsmGpxController,
        confirmation: OsmGpxConfirmation,
        prepare: suspend (OsmGpxReceiptKey) -> PreparedOsmGpxTrace?,
    ) {
        if (running || confirmation.user.id != accountId) return
        val started = Batch(confirmation.user.id, confirmation.keys)
        val generation = accountGeneration
        val allowed = AtomicBoolean(true)
        foreground = allowed
        running = true
        batch = started
        scope.launch {
            try {
                val result = controller.upload(confirmation, allowed::get, prepare) { _, position ->
                    if (generation == accountGeneration) batch = started.copy(current = position)
                }
                if (generation == accountGeneration) batch = started.copy(result = result)
            } finally {
                receiptRevision++
                foreground = null
                running = false
            }
        }
    }
}
