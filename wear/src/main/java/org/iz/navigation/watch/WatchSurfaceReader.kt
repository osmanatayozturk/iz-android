package org.iz.navigation.watch

import android.content.Context
import android.util.AtomicFile
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.iz.navigation.wearprotocol.*
import java.io.*

internal data class WatchSurfaceData(val phoneId: String? = null, val version: Int = 1,
    val snapshot: WearSnapshot? = null, val connected: Boolean = false)

/** One bounded Data Layer read; deliberately has no dependency on health-service startup or sensors. */
internal object WatchSurfaceReader {
    private val mutex = Mutex()
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "watch_surface_v1.bin"))
    private fun cached(context: Context): WatchSurfaceData = runCatching {
        val storage = file(context)
        require(storage.baseFile.length() in 1..70_000)
        DataInputStream(storage.openRead()).use { input ->
            val phone = input.readUTF(); val version = input.readInt(); val length = input.readInt()
            require(phone.length <= 300 && version in 1..5 && length in 1..65_536)
            val bytes = ByteArray(length); input.readFully(bytes); require(input.read() == -1)
            require(WearProtocol.frameVersion(bytes) == version)
            WatchSurfaceData(phone, version, requireNotNull(WearProtocol.decodeSnapshot(bytes)))
        }
    }.getOrDefault(WatchSurfaceData())
    private fun persist(context: Context, value: WatchSurfaceData) {
        val snapshot = value.snapshot ?: return
        val phone = value.phoneId ?: return
        val storage = file(context)
        val stream = storage.startWrite()
        try {
            val bytes = WearProtocol.encodeSnapshot(snapshot, value.version)
            val out = DataOutputStream(stream)
            out.writeUTF(phone); out.writeInt(value.version); out.writeInt(bytes.size); out.write(bytes); out.flush()
            storage.finishWrite(stream)
        } catch (failure: Exception) { storage.failWrite(stream); throw failure }
    }
    suspend fun deleted(context: Context, source: String?, path: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prior = cached(context)
            if (source == prior.phoneId && path == WearProtocol.statePath(prior.version)) file(context).delete()
        }
    }
    suspend fun read(context: Context): WatchSurfaceData = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prior = cached(context)
            try {
                withTimeout(4_000) {
                    val caps = Wearable.getCapabilityClient(context).getAllCapabilities(CapabilityClient.FILTER_REACHABLE).await()
                    val candidates = (1..5).flatMap { version -> caps[WearProtocol.phoneCapability(version)]?.nodes.orEmpty().map { it to version } }
                    val selected = candidates.sortedWith(compareByDescending<Pair<Node, Int>> { it.first.id == prior.phoneId }
                        .thenByDescending { it.first.isNearby }.thenByDescending { it.second }.thenBy { it.first.id }).firstOrNull()
                        ?: return@withTimeout prior
                    val phone = selected.first.id; val version = selected.second
                    var next = if (prior.phoneId == phone && prior.version == version) prior.copy(connected = true)
                        else WatchSurfaceData(phone, version, connected = true)
                    val items = Wearable.getDataClient(context).dataItems.await()
                    try {
                        for (item in items) {
                            if (item.uri.host != phone || item.uri.path != WearProtocol.statePath(version)) continue
                            val bytes = runCatching { DataMapItem.fromDataItem(item).dataMap.getByteArray(WearProtocol.STATE_KEY) }.getOrNull() ?: continue
                            if (bytes.size > 65_536 || WearProtocol.frameVersion(bytes) != version) continue
                            val snapshot = WearProtocol.decodeSnapshot(bytes) ?: continue
                            if (WatchSurfaceAcceptance.accept(item.uri.host!!, phone, WearProtocol.frameVersion(bytes) ?: 0,
                                    version, next.snapshot, snapshot, System.currentTimeMillis())) next = next.copy(snapshot = snapshot)
                        }
                    } finally { items.release() }
                    if (next.phoneId != prior.phoneId || next.version != prior.version) file(context).delete()
                    if (next.snapshot != null && next != prior) persist(context, next)
                    next
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                prior
            } catch (_: Exception) { prior }
        }
    }
}

/** Data changes wake this bounded reader even if the app and health service have never opened. */
class WatchSurfaceListener : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            for (request in requests) {
                val data = WatchSurfaceReader.read(this@WatchSurfaceListener)
                val s = data.snapshot
                val transition = "${data.phoneId}:${s?.journeyId}:${s?.recording}:${s?.temporary}:${s?.navigation?.sessionId}:${s?.navigation?.guidance}:${s?.navigation?.gpsStale}:${s?.navigation?.arrived}:${s?.navigation?.offRoute}:${s?.daily?.localDate}"
                WatchSurfaceUpdates.request(this@WatchSurfaceListener, transition)
            }
        }
    }
    private fun refresh() { requests.trySend(Unit) }
    override fun onDataChanged(events: DataEventBuffer) {
        val deleted = events.filter { it.type == DataEvent.TYPE_DELETED }.map { it.dataItem.uri.host to it.dataItem.uri.path }
        if (deleted.isNotEmpty()) scope.launch {
            deleted.forEach { (source, path) -> WatchSurfaceReader.deleted(this@WatchSurfaceListener, source, path) }
            refresh()
        } else if (events.any { it.dataItem.uri.path?.matches(Regex("/iz/v[1-5]/state")) == true }) refresh()
    }
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) = refresh()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Rendering reads the existing in-process cache only. No health service or sensor is started. */
internal fun watchSurfaceFrame(context: Context, data: WatchSurfaceData, now: Long): WatchSurfaceFrame {
    val frame = WatchSurfacePolicy.frame(data.snapshot, data.connected, now)
    val snapshot = data.snapshot ?: return frame
    if (frame.route != WatchSurfaceRoute.RECORDING || !data.connected || !WearProtocol.isFreshSnapshot(snapshot, now)) return frame
    val health = WatchHealthRuntime.state.value
    val heart = safeLiveHeart(context, data, now) ?: return frame
    return frame.copy(lines = frame.lines.map { it.replace("Nabız —", "Nabız ${heart.toInt()} canlı") },
        validUntil = minOf(frame.validUntil ?: Long.MAX_VALUE, health.latestHeartAt!! + 30_001))
}

internal fun safeLiveHeart(context: Context, data: WatchSurfaceData, now: Long): Double? {
    val snapshot = data.snapshot ?: return null
    if (!data.connected || !snapshot.recording || snapshot.temporary ||
        !WearProtocol.isFreshSnapshot(snapshot, now) || !WatchHealthRuntime.permissions(context)) return null
    return WatchLiveHealthPolicy.heart(WatchHealthRuntime.state.value, data.phoneId, snapshot.journeyId,
        WatchHealthRuntime.prefs(context).getString("sessionId", null), now)
}
internal fun WatchSurfaceData.commandPhone(reachable: Set<String>): String? = phoneId?.takeIf { connected && it in reachable }