package com.atay.iz.navigation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.atay.iz.MainActivity
import com.atay.iz.R

internal class NavigationNotifications(private val context: Context) {
    private var lastContent: String? = null
    fun update(state: NavigationState) {
        if (!state.guidance) {
            if (lastContent != null) NotificationManagerCompat.from(context).cancel(ID)
            lastContent = null
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val maneuver = state.route?.maneuvers?.getOrNull(state.progress?.maneuverIndex ?: -1)
        val title = when {
            state.gpsStale -> "Konum bekleniyor"
            state.progress?.offRoute == true -> "Yeni rota bekleniyor"
            else -> maneuver?.instruction?.takeIf { it.isNotBlank() } ?: "Rotayı takip et"
        }
        val meters = state.progress?.nextManeuverDistanceMeters?.toInt()?.coerceAtLeast(0)
        val text = if (state.gpsStale) "Konum güncellenince yönlendirme devam edecek."
            else listOfNotNull(meters?.let { "$it m" }, state.route?.stops?.lastOrNull()?.label).joinToString(" · ")
        val content = "$title|$text"
        if (content == lastContent) return
        lastContent = content
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigasyon", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("openNavigation", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .extend(CarAppExtender.Builder().setContentTitle(title).setContentText(text).setContentIntent(pending).build())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }
    companion object { private const val CHANNEL = "navigation_v1"; private const val ID = 4025 }
}
