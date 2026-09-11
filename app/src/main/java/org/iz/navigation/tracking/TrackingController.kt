package org.iz.navigation.tracking

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import kotlinx.coroutines.sync.withLock

class TrackingController(context: Context) {
    private val context = context.applicationContext
    private val repository = DiaryRepository(this.context)
    val settings = TrackerSettings(this.context)

    fun enableDetection(onResult: (String?) -> Unit = {}) =
        DetectionRegistrationRuntime.get(context).enable(onResult)

    /** Re-establish the requested subscription without changing the user's on/off preference. */
    fun restoreDetection(onResult: (String?) -> Unit = {}) =
        DetectionRegistrationRuntime.get(context).restore(onResult)

    fun disableDetection() = DetectionRegistrationRuntime.get(context).disable()

    suspend fun startManual(transport: Transport, stillCurrent: () -> Boolean = { true }): String = TrackingCoordinator.mutex.withLock {
        check(stillCurrent()) { "Yolculuk başlatma işlemi iptal edildi." }
        check(hasFineLocation(context)) { "Yolculuk için hassas konum izni ver." }
        check(TrackingService.compatibleSessionTransport(transport)) { "Canlı yolculuğun türü farklı. Önce mevcut yolculuğu bitir." }
        repository.activeJourney()?.let { current ->
            if (TrackingService.runningJourneyId != current.id && !TrackingCoordinator.isManualStartPending(current.id)) {
                repository.markInterrupted(current.id)
            } else error("Önce mevcut yolculuğu bitir.")
        }
        check(stillCurrent()) { "Yolculuk başlatma işlemi iptal edildi." }
        val journey = repository.createJourney(transport, temporary = false)
        TrackingCoordinator.markPendingManualStart(journey.id)
        try {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START).putExtra("journeyId", journey.id))
        } catch (error: RuntimeException) {
            TrackingCoordinator.clearPendingManualStart(journey.id)
            repository.markInterrupted(journey.id)
            settings.lastError = "Konum servisi başlatılamadı. Uygulamayı açık tutup tekrar dene."
            throw IllegalStateException(settings.lastError, error)
        }
        ExpirationWorker.schedule(context)
        journey.id
    }

    suspend fun confirm(id: String, transport: Transport) = TrackingCoordinator.mutex.withLock {
        val journey = repository.getJourney(id) ?: return@withLock
        if (journey.status != JourneyStatus.TEMPORARY) return@withLock
        check(!TrackingPolicy.expired(journey.expiresAt, System.currentTimeMillis())) { "Geçici kaydın onay süresi doldu." }
        repository.confirmJourney(id, transport)
        androidx.core.app.NotificationManagerCompat.from(context).cancel(TrackingNotifications.PROMPT_ID)
        TrackingService.refresh(context)
    }

    suspend fun reject(id: String) = TrackingCoordinator.mutex.withLock {
        val journey = repository.getJourney(id) ?: return@withLock
        check(journey.status == JourneyStatus.TEMPORARY) { "Yalnızca geçici kayıt reddedilebilir." }
        TrackingCoordinator.clearPendingManualStart(id)
        if (journey.endedAt == null) settings.suppressedActivity = settings.currentActivity
        repository.rejectJourney(id)
        if (TrackingService.runningJourneyId == id) context.stopService(Intent(context, TrackingService::class.java))
    }

    suspend fun finish(id: String, preserveSession: Boolean = false) = TrackingCoordinator.mutex.withLock {
        TrackingCoordinator.clearPendingManualStart(id)
        val journey = repository.getJourney(id) ?: return@withLock
        if (journey.endedAt == null) {
            repository.finishJourney(id)
            settings.suppressedActivity = settings.currentActivity
        }
        if (TrackingService.runningJourneyId == id && !TrackingCoordinator.hasPendingManualStartOtherThan(id)) {
            if (preserveSession) TrackingService.detachRecording(context, id)
            else context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    suspend fun switchTransport(transport: Transport): String = TrackingCoordinator.mutex.withLock {
        check(hasFineLocation(context)) { "Yolculuk için hassas konum izni ver." }
        val old = repository.activeJourney() ?: error("Devam eden yolculuk bulunamadı.")
        check(old.status == JourneyStatus.CONFIRMED) { "Önce geçici yolculuğu onayla." }
        repository.finishJourney(old.id)
        TrackingCoordinator.clearPendingManualStart(old.id)
        val next = repository.createJourney(transport, temporary = false)
        TrackingCoordinator.markPendingManualStart(next.id)
        try {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START).putExtra("journeyId", next.id))
        } catch (error: RuntimeException) {
            TrackingCoordinator.clearPendingManualStart(next.id)
            repository.markInterrupted(next.id)
            throw IllegalStateException("Yeni yolculuk için konum servisi başlatılamadı.", error)
        }
        next.id
    }

    /** Call once when opening the app. A dead service is not proof of uninterrupted travel. */
    suspend fun recoverInterrupted() = TrackingCoordinator.mutex.withLock {
        if (!TrackingService.isRunning) {
            repository.activeJourney()?.let {
                if (!TrackingCoordinator.isManualStartPending(it.id)) {
                    repository.markInterrupted(it.id)
                    settings.suppressedActivity = settings.currentActivity
                }
            }
        }
        repository.cleanupExpired()
        ExpirationWorker.schedule(context)
    }

    /** The successful replacement already ended imported records. Never mutate a diary row here. */
    suspend fun stopAfterRestore() = TrackingCoordinator.mutex.withLock {
        TrackingCoordinator.clearPendingManualStarts()
        (context.applicationContext as org.iz.navigation.IzApplication).navigation.invalidateAfterDiaryReplacement()
        (context.applicationContext as org.iz.navigation.IzApplication).weatherManager.stop()
        settings.suppressedActivity = settings.currentActivity
        runCatching { context.stopService(Intent(context, TrackingService::class.java)) }
            .onFailure { settings.lastError = "Yedek yüklendi. Eski kayıt servisi kapanmayı bekliyor." }
        androidx.core.app.NotificationManagerCompat.from(context).cancel(TrackingNotifications.PROMPT_ID)
    }

    companion object {
        fun hasFineLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun detectionPermissionError(context: Context): String? = when {
            !hasFineLocation(context) -> "Otomatik kayıt için hassas konum izni gerekli."
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "Otomatik kayıt için konum erişimini Her zaman izin ver olarak ayarla."
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED ->
                "Otomatik kayıt için fiziksel aktivite izni gerekli."
            !TrackingNotifications.allowed(context) -> "Onay isteğini görebilmen için bildirimlere izin ver."
            else -> null
        }

        internal fun transitionIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 4020, Intent(context, ActivityTransitionReceiver::class.java).setAction("org.iz.navigation.ACTIVITY_TRANSITION"),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        )
    }
}
