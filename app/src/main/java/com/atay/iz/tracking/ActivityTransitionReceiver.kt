package com.atay.iz.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.atay.iz.data.Transport
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = TrackerSettings(context)
        if (TrackingService.suppressesAutomaticRecording || !settings.enabled || !ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        result.transitionEvents.forEach { event ->
            if (!settings.acceptTransition(event.activityType, event.transitionType, event.elapsedRealTimeNanos, nowNanos)) return@forEach
            val label = when (event.activityType) {
                DetectedActivity.IN_VEHICLE -> "Araç"
                DetectedActivity.ON_BICYCLE -> "Bisiklet"
                DetectedActivity.WALKING -> "Yürüyüş"
                DetectedActivity.RUNNING -> "Koşu"
                DetectedActivity.STILL -> "Hareketsizlik"
                else -> "Hareket"
            }
            settings.lastActivityEvent = "$label: " + if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "başladı" else "bitti"
            settings.lastActivityEventAt = System.currentTimeMillis()
            if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                if (settings.currentActivity == event.activityType) settings.currentActivity = -1
                if (settings.suppressedActivity == event.activityType) settings.suppressedActivity = -1
                return@forEach
            }
            settings.currentActivity = event.activityType
            if (event.activityType == DetectedActivity.STILL) {
                settings.suppressedActivity = -1
                return@forEach
            }
            val transport = when (event.activityType) {
                DetectedActivity.IN_VEHICLE -> Transport.CAR
                DetectedActivity.ON_BICYCLE -> Transport.BICYCLE
                DetectedActivity.WALKING -> Transport.WALK
                DetectedActivity.RUNNING -> Transport.RUN
                else -> return@forEach
            }
            if (settings.suppressedActivity == event.activityType) {
                settings.lastDetectionDecision = "Bu hareketin önceki kaydı bitirildi veya reddedildi; yeni hareket geçişi bekleniyor."
                return@forEach
            }
            // Delayed batches must not start a new route for movement that happened long ago.
            if (nowNanos - event.elapsedRealTimeNanos > 2L * 60 * 1_000_000_000) {
                settings.lastDetectionDecision = "Hareket olayı iki dakikadan geç geldi; geçmişe dönük kayıt başlatılmadı."
                return@forEach
            }
            val issue = TrackingController.detectionPermissionError(context)
            if (issue != null) {
                settings.lastError = issue
                settings.lastDetectionDecision = issue
                TrackingNotifications.prompt(context, null, "permission", "Yolculuğu elle başlat", issue)
                return@forEach
            }
            try {
                // Start directly from the activity transition exemption, before doing any database work.
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_DETECTED).putExtra("suggestedTransport", transport.name))
                settings.lastDetectionDecision = "Hareket algılandı; kayıt servisine başlangıç isteği iletildi."
            } catch (_: RuntimeException) {
                settings.lastError = "Android arka planda kaydı başlatamadı. Uygulamayı açıp yolculuğu elle başlat."
                TrackingNotifications.prompt(context, null, "permission", "Kayıt başlatılamadı", settings.lastError!!)
                settings.lastDetectionDecision = settings.lastError
            }
        }
    }
}
