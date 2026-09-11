package com.atay.iz.watch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atay.iz.wearprotocol.*
import com.google.android.gms.wearable.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await

internal class WatchViewModel(application: Application) : AndroidViewModel(application) {
    private val dataClient = Wearable.getDataClient(application)
    private val messageClient = Wearable.getMessageClient(application)
    private val capabilityClient = Wearable.getCapabilityClient(application)
    private val mutableState = MutableStateFlow(WatchUiState())
    val state = mutableState.asStateFlow()
    private var visible = false

    /** Called only while the activity is STARTED; cancellation removes every Data Layer listener. */
    suspend fun observePhone() = coroutineScope {
        visible = true
        val dataListener = DataClient.OnDataChangedListener { events ->
            // The buffer is released by Google after this callback; copy bytes before launching work.
            events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { receiveItem(it.dataItem) }
        }
        val messageListener = MessageClient.OnMessageReceivedListener { event ->
            val expectedVersion = mutableState.value.let { if (it.pending != null) it.pendingVersion else it.phoneVersion }
            if (event.path == WearProtocol.resultPath(expectedVersion) && WearProtocol.frameVersion(event.data) == expectedVersion) {
                val result = WearProtocol.decodeResult(event.data) ?: return@OnMessageReceivedListener
                val before = mutableState.value
                mutableState.update { it.result(event.sourceNodeId, result) }
                if (before != mutableState.value && result.code != WearResultCode.NEEDS_PHONE) refresh()
            }
        }
        val capabilityListener = CapabilityClient.OnCapabilityChangedListener { refresh() }
        try {
            dataClient.addListener(dataListener).await()
            messageClient.addListener(messageListener).await()
            capabilityClient.addListener(capabilityListener, WearProtocol.PHONE_CAPABILITY).await()
            capabilityClient.addListener(capabilityListener, WearProtocol.V2_PHONE_CAPABILITY).await()
            capabilityClient.addListener(capabilityListener, WearProtocol.V3_PHONE_CAPABILITY).await()
            capabilityClient.addListener(capabilityListener, WearProtocol.V4_PHONE_CAPABILITY).await()
            launch {
                while (isActive) {
                    refreshNow()
                    delay(10_000)
                }
            }
            while (isActive) {
                mutableState.update { it.tick(System.currentTimeMillis()) }
                delay(1_000)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.phone(null, null).copy(message = "Telefon bağlantısı açılamadı. İki cihazda da İz ve Google Play hizmetlerini kontrol et.", messageIsConnectionIssue = true) }
        } finally {
            visible = false
            withContext(NonCancellable) {
                runCatching { dataClient.removeListener(dataListener).await() }
                runCatching { messageClient.removeListener(messageListener).await() }
                runCatching { capabilityClient.removeListener(capabilityListener, WearProtocol.PHONE_CAPABILITY).await() }
                runCatching { capabilityClient.removeListener(capabilityListener, WearProtocol.V2_PHONE_CAPABILITY).await() }
                runCatching { capabilityClient.removeListener(capabilityListener, WearProtocol.V3_PHONE_CAPABILITY).await() }
                runCatching { capabilityClient.removeListener(capabilityListener, WearProtocol.V4_PHONE_CAPABILITY).await() }
            }
        }
    }

    fun start(mode: WearMode) = send(WearCommand(UUID.randomUUID().toString(), WearAction.START, System.currentTimeMillis(), mode = mode))
    fun stop(journeyId: String) = send(WearCommand(UUID.randomUUID().toString(), WearAction.STOP, System.currentTimeMillis(), journeyId = journeyId))
    fun refresh() {
        if (visible) viewModelScope.launch { refreshNow() }
    }

    private fun send(command: WearCommand) {
        if (!visible) return
        mutableState.update { it.begin(command, System.currentTimeMillis()) }
        val pending = mutableState.value
        if (pending.pending?.id != command.id) return
        val phone = pending.phoneId ?: return
        val version = pending.pendingVersion
        viewModelScope.launch {
            try {
                val reachable = capabilityClient.getCapability(WearProtocol.phoneCapability(version), CapabilityClient.FILTER_REACHABLE).await().nodes
                if (!visible || reachable.none { it.id == phone } || mutableState.value.phoneId != phone) {
                    mutableState.update { it.failed(command.id, "Telefon bağlı değil. İstek gönderilmedi.") }
                    return@launch
                }
                messageClient.sendMessage(phone, WearProtocol.commandPath(version), WearProtocol.encodeCommand(command, version)).await()
                // Transport delivery is not a STARTED/STOPPED acknowledgement; keep the command pending.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.failed(command.id, "Gönderim doğrulanamadı. Telefon durumunu yenile.") }
            }
        }
    }

    private suspend fun refreshNow() {
        try {
            val capabilities = capabilityClient.getAllCapabilities(CapabilityClient.FILTER_REACHABLE).await()
            choosePhone(capabilities)
            val phone = mutableState.value.phoneId ?: return
            val items = dataClient.dataItems.await()
            try { items.forEach(::receiveItem) } finally { items.release() }
            if (!visible || mutableState.value.phoneId != phone) return
            val now = System.currentTimeMillis()
            mutableState.update { it.tick(now) }
            val command = mutableState.value.retryCommand(now)
                ?: WearCommand(UUID.randomUUID().toString(), WearAction.REFRESH, now)
            val version = mutableState.value.let { if (it.pending?.id == command.id) it.pendingVersion else it.phoneVersion }
            if (capabilities[WearProtocol.phoneCapability(version)]?.nodes?.none { it.id == phone } != false) return
            messageClient.sendMessage(phone, WearProtocol.commandPath(version), WearProtocol.encodeCommand(command, version)).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(message = "Telefon yanıt vermiyor. Son verinin zamanını kontrol et.", messageIsConnectionIssue = true) }
        }
    }

    private fun choosePhone(capabilities: Map<String, CapabilityInfo>) {
        val v4 = capabilities[WearProtocol.V4_PHONE_CAPABILITY]?.nodes.orEmpty()
        val v3 = capabilities[WearProtocol.V3_PHONE_CAPABILITY]?.nodes.orEmpty()
        val v2 = capabilities[WearProtocol.V2_PHONE_CAPABILITY]?.nodes.orEmpty()
        val nodes = (capabilities[WearProtocol.PHONE_CAPABILITY]?.nodes.orEmpty() + v2 + v3 + v4).distinctBy { it.id }
        fun version(id: String?): Int = when {
            v4.any { it.id == id } -> 4
            v3.any { it.id == id } -> 3
            v2.any { it.id == id } -> 2
            else -> 1
        }
        val currentId = mutableState.value.phoneId
        val node = nodes.firstOrNull { it.id == currentId }
            ?: nodes.sortedWith(compareByDescending<Node> { it.isNearby }
                .thenByDescending { candidate -> version(candidate.id) }.thenBy { it.id }).firstOrNull()
        mutableState.update { it.phone(node?.id, node?.displayName, version(node?.id)) }
    }

    private fun receiveItem(item: DataItem) {
        val version = mutableState.value.phoneVersion
        if (item.uri.path != WearProtocol.statePath(version)) return
        val source = item.uri.host ?: return
        if (source != mutableState.value.phoneId) return
        val bytes = runCatching { DataMapItem.fromDataItem(item).dataMap.getByteArray(WearProtocol.STATE_KEY) }.getOrNull() ?: return
        if (WearProtocol.frameVersion(bytes) != version) return
        val snapshot = WearProtocol.decodeSnapshot(bytes) ?: return
        mutableState.update { it.receive(source, snapshot, System.currentTimeMillis()) }
    }
}
