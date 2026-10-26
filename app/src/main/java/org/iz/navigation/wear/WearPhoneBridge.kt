package org.iz.navigation.wear

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.iz.navigation.R
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Transport
import org.iz.navigation.power.PostJourneyAnalysis
import org.iz.navigation.power.PowerDiagnostics
import org.iz.navigation.tracking.TrackingController
import org.iz.navigation.tracking.TrackingNotifications
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.wearprotocol.*
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** The phone owns recordings. A Data Layer message alone never grants foreground-service privileges. */
internal class WearPhoneBridge(private val context: Context) {
    private val store = WearCommandStore(context)
    private val repository = DiaryRepository(context)
    private val controller = TrackingController(context)
    private val watchHealth = WatchHealthPhoneBridge(context)
    private val mutex = Mutex()
    private val publishMutex = Mutex()
    private data class Peer(val version: Int, val requestedAt: Long)
    private val peers = ConcurrentHashMap<String, Peer>()
    private val capabilityGeneration = AtomicLong()
    private val capabilityNodes = ConcurrentHashMap<String, Set<String>>()
    private var capabilityCheckedAt: Long? = null
    private var capabilityCheckedGeneration = -1L
    private var advertisedWatchVersions: Map<String, Int> = emptyMap()
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        if (info.name in WATCH_CAPABILITIES) {
            val nodes = info.nodes.asSequence().take(MAX_PEERS + 1).map { it.id }.toSet()
            if (capabilityNodes.put(info.name, nodes) != nodes) {
                capabilityGeneration.incrementAndGet()
                scope.launch { publish() }
            }
        }
    }
    @Volatile private var publishRequested = false
    private val analysis get() = PostJourneyAnalysis.get(context)
    @Volatile private var resumed = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(application: Application) {
        scope.launch {
            // App-lifetime listener; no additional service or polling timer.
            // https://developers.google.com/android/reference/com/google/android/gms/wearable/CapabilityClient
            try {
                capabilityClient.addListener(capabilityListener, Uri.parse("wear://*/iz_watch_v"), CapabilityClient.FILTER_PREFIX).await()
                awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Timed capability refresh remains available. */ }
            finally { withContext(NonCancellable) { runCatching { withTimeout(2_000) {
                capabilityClient.removeListener(capabilityListener).await()
            } } } }
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed++ }
            override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        scope.launch {
            var wasRecording = false
            var wasGuiding = false
            while (isActive) {
                val recording = TrackingService.isRunning
                val guidance = (application as org.iz.navigation.IzApplication).navigation.state.value.let { it.guidance || it.loading }
                if (WearNavigationFactory.shouldPublish(recording, guidance, wasRecording, wasGuiding, resumed)) publish()
                wasRecording = recording
                wasGuiding = guidance
                delay(5_000)
            }
        }
        scope.launch {
            org.iz.navigation.health.DailyActivityManager.get(context).currentDataFlow.filterNotNull().collect { publish() }
        }
        scope.launch {
            // Project before distinct: GPS invalidations do not become per-point Data Layer publications.
            analysis.state.map { Triple(it.phase, it.pending, it.cacheRevision) }.distinctUntilChanged().collect { publish() }
        }
    }

    suspend fun receive(source: String, bytes: ByteArray) {
        val start = SystemClock.elapsedRealtimeNanos()
        try { receiveCommand(source, bytes) }
        finally { PowerDiagnostics.count(PowerDiagnostics.Metric.WATCH_RECEIVE, SystemClock.elapsedRealtimeNanos() - start) }
    }

    private suspend fun receiveCommand(source: String, bytes: ByteArray) {
        val protocolVersion = WearProtocol.frameVersion(bytes) ?: return
        val command = WearProtocol.decodeCommand(bytes) ?: return
        if (source.isBlank() || source.length > 256) return
        val now = System.currentTimeMillis()
        if (WearProtocol.isFreshCommand(command, now)) {
            synchronized(peers) {
                peers.entries.removeAll { now - it.value.requestedAt !in 0 until LEGACY_PEER_TTL_MS }
                if (peers.containsKey(source) || peers.size < MAX_PEERS) peers[source] = Peer(protocolVersion, now)
            }
            if (command.action == WearAction.REFRESH && analysis.canProcessNow() && !analysis.state.value.pending) {
                scope.launch { runCatching { org.iz.navigation.health.DailyActivityManager.get(context).refresh() } }
            }
        }
        val result = try { handle(source, command, protocolVersion = protocolVersion) } catch (_: Exception) {
            WearResult(command.id, WearResultCode.ERROR, "İstek işlenemedi. Telefonda İz’i kontrol et.")
        }
        sendResult(source, result, protocolVersion)
        publish()
    }

    suspend fun receiveHealth(source: String, bytes: ByteArray) {
        val start = SystemClock.elapsedRealtimeNanos()
        try { watchHealth.receive(source, bytes) }
        finally { PowerDiagnostics.count(PowerDiagnostics.Metric.WATCH_RECEIVE, SystemClock.elapsedRealtimeNanos() - start) }
    }

    internal suspend fun handle(source: String, command: WearCommand, foreground: Boolean = resumed > 0, protocolVersion: Int = WearProtocol.CURRENT_VERSION): WearResult = withContext(NonCancellable) { mutex.withLock {
        val now = System.currentTimeMillis()
        if (protocolVersion !in 1..WearProtocol.CURRENT_VERSION || (protocolVersion == 1 && command.mode == WearMode.RUN))
            return@withLock rejected(command, "Koşu için saat ve telefon uygulamasını güncelle.")
        if (!WearProtocol.isFreshCommand(command, now)) return@withLock rejected(command, "İsteğin süresi doldu. Saatten tekrar dene.")
        store.get(source, command)?.let {
            return@withLock if (it.command == command) it.result else rejected(command, "İstek kimliği daha önce kullanılmış.")
        }
        if (command.action == WearAction.REFRESH) return@withLock WearResult(command.id, WearResultCode.REFRESHED, "Güncellendi.")
        // A crash after this commit cannot replay the side effect on reconnect.
        store.save(source, command, WearResult(command.id, WearResultCode.ERROR, "İstek kesildi. Telefonda kaydı kontrol et."), now, protocolVersion)
        val result = when (command.action) {
            WearAction.START -> when {
                repository.activeJourney() != null -> rejected(command, "Önce devam eden yolculuğu bitir.")
                foreground && TrackingController.hasFineLocation(context) -> start(command)
                store.pending(now) != null -> rejected(command, "Telefonda bekleyen başlatma isteğini tamamla.")
                else -> WearResult(command.id, WearResultCode.NEEDS_PHONE,
                    if (TrackingNotifications.allowed(context)) "Telefondaki İz bildirimine dokun ve Başlat’a bas."
                    else "Telefonda İz’i aç ve Başlat’a bas; bildirim izni kapalı.")
            }
            WearAction.STOP -> {
                val current = repository.activeJourney()
                if (current == null || current.id != command.journeyId) rejected(command, "Bu yolculuk artık aktif değil. Durumu yenile.")
                else try {
                    controller.finish(current.id)
                    withTimeout(5_000) { while (TrackingService.runningJourneyId == current.id) delay(100) }
                    if (repository.getJourney(current.id)?.endedAt == null) error("Recording did not end")
                    WearResult(command.id, WearResultCode.STOPPED,
                        if (repository.getJourney(current.id)?.status == org.iz.navigation.data.JourneyStatus.TEMPORARY)
                            "Geçici ölçüm bitirildi. Kalıcı saklamak için telefonda kaydı açıp Sakla’yı seç."
                        else "Yolculuk kaydedildi.", current.id)
                } catch (_: Exception) { WearResult(command.id, WearResultCode.ERROR, "Bitiş doğrulanamadı. Telefonda kaydı kontrol et.", current.id) }
            }
            WearAction.REFRESH -> error("Handled above")
        }
        val entry = store.save(source, command, result, now, protocolVersion)
        if (result.code == WearResultCode.NEEDS_PHONE) notifyPending(entry)
        result
    } }

    suspend fun pendingKey(): String? = mutex.withLock { store.pending(System.currentTimeMillis())?.key }
    suspend fun pending(key: String): WearCommand? = mutex.withLock {
        store.get(key)?.takeIf { it.result.code == WearResultCode.NEEDS_PHONE &&
            WearProtocol.isFreshCommand(it.command, System.currentTimeMillis()) }?.command
    }

    suspend fun confirmOnPhone(key: String): WearResult = completePending(key, cancel = false)
    suspend fun cancelOnPhone(key: String): WearResult = completePending(key, cancel = true)
    private suspend fun completePending(key: String, cancel: Boolean): WearResult {
        var source: String? = null
        var protocolVersion = 1
        // Rotation/back navigation cannot cancel between the database commit, service start and receipt.
        val result = withContext(NonCancellable) { mutex.withLock {
            val entry = store.get(key) ?: return@withLock WearResult("missing", WearResultCode.REJECTED, "İstek bulunamadı.")
            source = entry.source
            protocolVersion = entry.protocolVersion
            if (entry.result.code != WearResultCode.NEEDS_PHONE) return@withLock entry.result
            val now = System.currentTimeMillis()
            val command = entry.command
            val outcome = when {
                !WearProtocol.isFreshCommand(command, now) -> rejected(command, "İsteğin süresi doldu. Saatten tekrar dene.")
                cancel -> rejected(command, "Başlatma iptal edildi.")
                resumed == 0 || !TrackingController.hasFineLocation(context) -> return@withLock WearResult(command.id,
                    WearResultCode.NEEDS_PHONE, "Telefon açıkken hassas konum izni ver ve tekrar Başlat’a bas.")
                else -> {
                    store.save(entry.source, command, WearResult(command.id, WearResultCode.ERROR, "Başlatma kesildi. Telefonda kaydı kontrol et."), now, protocolVersion)
                    start(command)
                }
            }
            store.save(entry.source, command, outcome, now, protocolVersion)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            outcome
        } }
        source?.let { sendResult(it, result, protocolVersion) }
        publish()
        return result
    }

    private suspend fun start(command: WearCommand): WearResult {
        var createdId: String? = null
        return try {
            val id = controller.startManual(Transport.valueOf(requireNotNull(command.mode).name))
            createdId = id
            withTimeout(8_000) { while (!TrackingService.isRunning || TrackingService.runningJourneyId != id) delay(100) }
            WearResult(command.id, WearResultCode.STARTED, "Telefonda kayıt başladı.", id)
        } catch (_: Exception) {
            withContext(NonCancellable) { createdId?.let { repository.markInterrupted(it); controller.finish(it) } }
            WearResult(command.id, WearResultCode.ERROR, "Kayıt başlatılamadı. Telefonda konum iznini ve aktif yolculuğu kontrol et.")
        }
    }

    internal suspend fun snapshot(): WearSnapshot {
        val active = repository.activeJourney()
        val now = System.currentTimeMillis()
        val app = context.applicationContext as org.iz.navigation.IzApplication
        val navigation = WearNavigationFactory.create(app.navigation.state.value, now)
        val state = analysis.state.value
        val busy = active != null || TrackingService.isRunning || app.navigation.state.value.let {
            it.guidance || it.loading || it.locationActive || it.recording
        }
        if (busy || state.pending) {
            val status = when {
                active != null && (!TrackingService.isRunning || TrackingService.runningJourneyId != active.id) ->
                    "Kayıt durumu doğrulanıyor · İstatistikler yolculuk sonunda hazırlanacak"
                active != null -> if (active.status == org.iz.navigation.data.JourneyStatus.TEMPORARY)
                    "Algılama sürüyor · İstatistikler yolculuk sonunda hazırlanacak"
                    else "Kayıt sürüyor · İstatistikler yolculuk sonunda hazırlanacak"
                busy -> "Navigasyon bitince istatistikler hazırlanacak"
                else -> state.label
            }
            return WearSnapshotFactory.deferred(active, now, TrackingService.runningJourneyId.takeIf { TrackingService.isRunning },
                status, active?.let { app.weatherManager.wearWeather(it.id) }, navigation)
        }
        val entry = state.entries.values.filter { it.journey.endedAt != null }.maxByOrNull { it.journey.endedAt!! }
        // These values are already prepared. Never call snapshot()/journeyPoints()/healthSummaries here.
        return WearSnapshot(generatedAt = now, navigation = navigation,
            daily = org.iz.navigation.health.DailyActivityManager.get(context).currentDataFlow.value,
            statisticsStatus = if (entry != null) "İstatistikler hazır" else "",
            completed = entry?.let { WearSnapshotFactory.completed(it.journey, it.stats, it.health, it.watch) })
    }

    /** True legacy metrics only for reachable legacy nodes or fresh legacy commands; v6 never pays this cost. */
    private suspend fun legacySnapshot(): WearSnapshot {
        val active = repository.activeJourney()
        val points = active?.let { repository.journeyPoints(it.id) }.orEmpty()
        val current = active?.let { repository.getJourney(it.id) }
        val prepared = !analysis.state.value.pending
        val health = current?.takeIf { prepared }?.let { journey -> repository.healthSummaries.first().firstOrNull { it.journeyId == journey.id } }
        val weather = current?.let { journey ->
            (context.applicationContext as org.iz.navigation.IzApplication).weatherManager.wearWeather(journey.id)
        }
        val now = System.currentTimeMillis()
        val app = context.applicationContext as org.iz.navigation.IzApplication
        val daily = org.iz.navigation.health.DailyActivityManager.get(context).currentDataFlow.value.takeIf { prepared }
        return WearSnapshotFactory.create(current, points, now,
            TrackingService.runningJourneyId.takeIf { TrackingService.isRunning }, health, weather).copy(
                navigation = WearNavigationFactory.create(app.navigation.state.value, now), daily = daily)
    }

    suspend fun publish() {
        // Conflate work, retaining a ready transition that arrives during a slow transport call.
        publishRequested = true
        if (!publishMutex.tryLock()) return
        try {
            do {
                publishRequested = false
                val start = SystemClock.elapsedRealtimeNanos()
                try {
                    val state = withTimeout(3_000) { snapshot() }
                    publishVersion(state, WearProtocol.CURRENT_VERSION)
                    val legacyVersions = legacyVersions()
                    if (legacyVersions.isNotEmpty()) {
                        val legacy = withTimeout(3_000) { legacySnapshot() }
                        legacyVersions.forEach { publishVersion(legacy, it) }
                    }
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                } catch (_: Exception) { /* Watch marks a missed snapshot stale; recording continues. */ }
                finally { PowerDiagnostics.count(PowerDiagnostics.Metric.WATCH_PUBLISH, SystemClock.elapsedRealtimeNanos() - start) }
            } while (publishRequested)
        } finally {
            publishMutex.unlock()
            if (publishRequested) scope.launch { publish() }
        }
    }

    /** Under publishMutex: one lookup/minute, except a real capability/node change. */
    private suspend fun legacyVersions(): List<Int> {
        val elapsed = SystemClock.elapsedRealtime()
        val generation = capabilityGeneration.get()
        val checkedAt = capabilityCheckedAt
        if (checkedAt == null || elapsed - checkedAt !in 0 until CAPABILITY_CACHE_MS || generation != capabilityCheckedGeneration) {
            capabilityCheckedAt = elapsed
            capabilityCheckedGeneration = generation
            advertisedWatchVersions = try {
                withTimeout(2_000) {
                    val capabilities = capabilityClient.getAllCapabilities(CapabilityClient.FILTER_REACHABLE).await()
                    val versions = linkedMapOf<String, Int>()
                    var overflow = false
                    for ((index, capability) in WATCH_CAPABILITIES.withIndex()) {
                        for (node in capabilities[capability]?.nodes.orEmpty()) {
                            if (node.id !in versions && versions.size >= MAX_PEERS) { overflow = true; break }
                            versions[node.id] = index + 1
                        }
                        if (overflow) break
                    }
                    if (overflow) emptyMap() else versions
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                emptyMap()
            } catch (_: Exception) { emptyMap() }
        }
        val versions = advertisedWatchVersions.toMutableMap()
        val now = System.currentTimeMillis()
        synchronized(peers) {
            peers.entries.removeAll { now - it.value.requestedAt !in 0 until LEGACY_PEER_TTL_MS }
            peers.forEach { (node, peer) -> versions[node] = maxOf(versions[node] ?: 0, peer.version) }
        }
        // v6 nodes also advertise older capabilities; never treat them as legacy peers.
        return versions.values.filter { it in 1..5 }.distinct().sorted()
    }

    private suspend fun publishVersion(state: WearSnapshot, version: Int) {
        try { withTimeout(3_000) {
            val request = PutDataMapRequest.create(WearProtocol.statePath(version)).apply {
                dataMap.putByteArray(WearProtocol.STATE_KEY, WearProtocol.encodeSnapshot(state, version))
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        } } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
        } catch (_: Exception) { /* Each compatibility channel is independent. */ }
    }

    private suspend fun sendResult(source: String, result: WearResult, protocolVersion: Int) {
        try { withTimeout(3_000) { Wearable.getMessageClient(context)
            .sendMessage(source, WearProtocol.resultPath(protocolVersion), WearProtocol.encodeResult(result, protocolVersion)).await() } }
        catch (_: Exception) { /* Retrying the same fresh command retrieves its durable result. */ }
    }

    private fun notifyPending(entry: WearCommandStore.Entry) {
        if (!TrackingNotifications.allowed(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Saatten başlatma", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, WearStartActivity::class.java).putExtra("requestKey", entry.key)
            .setAction("org.iz.navigation.WEAR_START_${entry.key}")
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Saatten yolculuk başlat")
                    .setContentText("${entry.command.mode?.label()}: dokunup başlatmayı onayla.")
                    .setContentIntent(pending).setAutoCancel(true).setTimeoutAfter(WearProtocol.COMMAND_TTL_MS).build())
        } catch (_: SecurityException) { /* Opening Iz still exposes the pending request. */ }
    }

    private fun rejected(command: WearCommand, text: String) = WearResult(command.id, WearResultCode.REJECTED, text)
    companion object {
        private const val LEGACY_PEER_TTL_MS = 60_000L
        private const val CAPABILITY_CACHE_MS = 60_000L
        private const val MAX_PEERS = 32
        private val WATCH_CAPABILITIES = listOf(WearProtocol.WATCH_CAPABILITY, WearProtocol.V2_WATCH_CAPABILITY,
            WearProtocol.V3_WATCH_CAPABILITY, WearProtocol.V4_WATCH_CAPABILITY, WearProtocol.V5_WATCH_CAPABILITY,
            WearProtocol.V6_WATCH_CAPABILITY)
        private const val CHANNEL = "watch_start"
        private const val NOTIFICATION_ID = 4050
        fun get(context: Context): WearPhoneBridge = (context.applicationContext as org.iz.navigation.IzApplication).wearBridge
    }
}

internal fun WearMode.label(): String = when (this) {
    WearMode.RUN -> "Koşu"
    WearMode.CAR -> "Araba"; WearMode.MOTORCYCLE -> "Motosiklet"; WearMode.BICYCLE -> "Bisiklet"
    WearMode.WALK -> "Yürüyüş"; WearMode.PASSENGER -> "Yolcu"
}
