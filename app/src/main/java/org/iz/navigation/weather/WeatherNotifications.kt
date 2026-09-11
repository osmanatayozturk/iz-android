package org.iz.navigation.weather

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.iz.navigation.MainActivity
import org.iz.navigation.R
import java.util.Locale
import kotlin.math.roundToInt

internal class WeatherNotifications(context: Context) : RideWeatherAlertOutput {
    private val app = context.applicationContext
    private val speech = org.iz.navigation.navigation.NavigationSpeech.get(app)

    override fun announce(journeyId: String, hazards: Set<WeatherHazard>, assessment: WeatherAssessment, voiceEnabled: Boolean,
        transport: org.iz.navigation.data.Transport) {
        val names = hazards.map { when (it) {
            WeatherHazard.RAIN -> "yağış"
            WeatherHazard.WIND -> "rüzgâr"
            WeatherHazard.COLD -> "düşük sıcaklık"
            WeatherHazard.HEAT -> "yüksek sıcaklık"
        } }.joinToString(", ")
        val now = System.currentTimeMillis()
        val first = assessment.samples.firstOrNull { it.arrivalAt >= now && it.hazards.any(hazards::contains) }
        val minutes = first?.let { ((it.arrivalAt - now) / 60_000.0).roundToInt().coerceAtLeast(0) }
        val message = if (minutes != null && minutes > 0)
            "Yaklaşık $minutes dakika sonra geçeceğin bölgede $names tahmini belirlediğin eşiği aşıyor."
        else "Kalan rotanda $names tahmini belirlediğin eşiği aşıyor."
        channels(transport)
        if ((Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            val intent = Intent(app, MainActivity::class.java)
                .setAction("org.iz.navigation.OPEN_WEATHER")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openWeather", true).putExtra("journeyId", journeyId)
            val pending = PendingIntent.getActivity(app, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                NotificationManagerCompat.from(app).notify(NOTIFICATION_ID,
                    NotificationCompat.Builder(app, channelId(transport)).setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("İz · Yolculuk havası").setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentIntent(pending).setAutoCancel(true).build())
            } catch (_: SecurityException) { /* A permission can be revoked after the check. */ }
        }
        if (voiceEnabled) speech.speakWeather(message)
    }

    override fun clear() {
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
        speech.cancelWeather()
    }
    override fun suspendVoice() = speech.suspendAutomaticWeather()
    override fun testVoice() = speech.testWeather()

    private fun channelId(transport: org.iz.navigation.data.Transport) =
        if (transport == org.iz.navigation.data.Transport.MOTORCYCLE) CHANNEL_ID else "${CHANNEL_ID}_${transport.name.lowercase(Locale.ROOT)}"

    private fun channels(transport: org.iz.navigation.data.Transport) {
        val label = when (transport) {
            org.iz.navigation.data.Transport.CAR -> "Araba"
            org.iz.navigation.data.Transport.MOTORCYCLE -> "Motosiklet"
            org.iz.navigation.data.Transport.BICYCLE -> "Bisiklet"
            org.iz.navigation.data.Transport.WALK -> "Yürüyüş"
            org.iz.navigation.data.Transport.RUN -> "Koşu"
            org.iz.navigation.data.Transport.PASSENGER -> "Yolcu"
            org.iz.navigation.data.Transport.UNKNOWN -> "Yolculuk"
        }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId(transport), "$label hava uyarıları", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Planlanan ${label.lowercase(Locale.forLanguageTag("tr-TR"))} rotasında yaklaşan hava koşulları"
                enableVibration(true)
            })
    }

    companion object {
        const val CHANNEL_ID = "ride_weather_v1"
        const val NOTIFICATION_ID = 4015
    }
}
