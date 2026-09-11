package com.atay.iz.tracking

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.atay.iz.MainActivity
import com.atay.iz.R
import com.atay.iz.data.Journey
import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.Transport

internal object TrackingNotifications {
    const val RECORDING_ID = 4001
    const val PROMPT_ID = 4002
    const val CHANNEL_RECORDING = "route_recording"
    const val CHANNEL_PROMPTS = "journey_prompts"

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING, "Yolculuk kaydı", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROMPTS, "Yolculuk önerileri", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun open(context: Context, id: String?, prompt: String, suggestion: Transport? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("com.atay.iz.OPEN_" + prompt + "_" + (id ?: "new"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("journeyId", id)
            .putExtra("trackingPrompt", prompt)
            .putExtra("suggestedTransport", suggestion?.name)
        return PendingIntent.getActivity(context, prompt.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun session(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL_RECORDING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("İz • Canlı yolculuk")
        .setContentText("Konum navigasyon veya grup paylaşımı için kullanılıyor. Günlük kaydı kapalı.")
        .setContentIntent(open(context, null, "navigation"))
        .setOngoing(true).setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()

    fun recording(context: Context, journey: Journey?, distanceMeters: Double = 0.0): Notification {
        if (journey == null && TrackingService.suppressesAutomaticRecording) return session(context)
        val temporary = journey?.status == JourneyStatus.TEMPORARY
        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (temporary) "Yolculuk için mesafe ölçülüyor" else "İz • Yolculuk kaydediliyor")
            .setContentText(if (temporary) "${distanceMeters.toInt().coerceIn(0, 500)} / 500 m · 15 dakikada tamamlanmazsa sıfırlanır." else "Rotanı, duraklarını ve anılarını biriktir.")
            .setContentIntent(open(context, journey?.id, "journey"))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (journey != null) {
                    addAction(0, if (temporary) "Mesafeyi incele" else "Yolculuğu aç", open(context, journey.id, "journey"))
                    val stop = Intent(context, TrackingService::class.java)
                        .setAction(TrackingService.ACTION_STOP).putExtra("journeyId", journey.id)
                    addAction(0, "Takibi durdur", PendingIntent.getService(context, 4010, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }.build()
    }

    fun prompt(context: Context, id: String?, kind: String, title: String, text: String, suggestion: Transport? = null) {
        if (!allowed(context)) return
        channels(context)
        try {
            NotificationManagerCompat.from(context).notify(PROMPT_ID,
                NotificationCompat.Builder(context, CHANNEL_PROMPTS)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open(context, id, kind, suggestion)).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Permission may have been revoked between checks. */ }
    }
}
