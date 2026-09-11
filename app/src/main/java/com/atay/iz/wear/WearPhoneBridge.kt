package com.atay.iz.wear

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.atay.iz.R
import com.atay.iz.data.DiaryRepository
import com.atay.iz.data.Transport
import com.atay.iz.tracking.TrackingController
import com.atay.iz.tracking.TrackingNotifications
import com.atay.iz.tracking.TrackingService
import com.atay.iz.wearprotocol.*
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    @Volatile private var resumed = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(application: Application) {
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
            while (isActive) {
                val recording = TrackingService.isRunning
                if (recording || wasRecording || resumed > 0) publish()
                wasRecording = recording
                delay(5_000)
            }
        }
    }

    suspend fun receive(source: String, bytes: ByteArray) {
        val protocolVersion = WearProtocol.frameVersion(bytes) ?: return
        val command = WearProtocol.decodeCommand(bytes) ?: return
        if (source.isBlank() || source.length > 256) return
        val result = try { handle(source, command, protocolVersion = protocolVersion) } catch (_: Exception) {
            WearResult(command.id, WearResultCode.ERROR, "İstek işlenemedi. Telefonda İz’i kontrol et.")
        }
        sendResult(source, result, protocolVersion)
        publish()
    }

    suspend fun receiveHealth(source: String, bytes: ByteArray) { watchHealth.receive(source, bytes) }

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
                        if (repository.getJourney(current.id)?.status == com.atay.iz.data.JourneyStatus.TEMPORARY)
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
        val points = active?.let { repository.journeyPoints(it.id) }.orEmpty()
        val current = active?.let { repository.getJourney(it.id) }
        val health = current?.let { journey -> repository.healthSummaries.first().firstOrNull { it.journeyId == journey.id } }
        val weather = current?.let { journey ->
            (context.applicationContext as com.atay.iz.IzApplication).weatherManager.wearWeather(journey.id)
        }
        return WearSnapshotFactory.create(current, points, System.currentTimeMillis(),
            TrackingService.runningJourneyId.takeIf { TrackingService.isRunning }, health, weather)
    }

    suspend fun publish() {
        // GMS availability or a disconnected watch must never interrupt GPS recording.
        if (!publishMutex.tryLock()) return
        try {
            val state = withTimeout(3_000) { snapshot() }
            for (version in 1..WearProtocol.CURRENT_VERSION) {
                try { withTimeout(3_000) {
                    val request = PutDataMapRequest.create(WearProtocol.statePath(version)).apply {
                        dataMap.putByteArray(WearProtocol.STATE_KEY, WearProtocol.encodeSnapshot(state, version))
                    }.asPutDataRequest().setUrgent()
                    Wearable.getDataClient(context).putDataItem(request).await()
                } } catch (_: Exception) { /* Each compatibility channel is independent. */ }
            }
        } catch (_: Exception) { /* Watch will mark a missed snapshot stale. */ }
        finally { publishMutex.unlock() }
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
            .setAction("com.atay.iz.WEAR_START_${entry.key}")
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
        private const val CHANNEL = "watch_start"
        private const val NOTIFICATION_ID = 4050
        fun get(context: Context): WearPhoneBridge = (context.applicationContext as com.atay.iz.IzApplication).wearBridge
    }
}

internal fun WearMode.label(): String = when (this) {
    WearMode.RUN -> "Koşu"
    WearMode.CAR -> "Araba"; WearMode.MOTORCYCLE -> "Motosiklet"; WearMode.BICYCLE -> "Bisiklet"
    WearMode.WALK -> "Yürüyüş"; WearMode.PASSENGER -> "Yolcu"
}
