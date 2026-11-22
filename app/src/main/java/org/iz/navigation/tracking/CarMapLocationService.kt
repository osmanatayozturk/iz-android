package org.iz.navigation.tracking

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.iz.navigation.R
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate

/** Bound by the map only. No journey, navigation session or diary storage is involved. */
class CarMapLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consumers = mutableSetOf<(NavigationFix?, Boolean) -> Unit>()
    private val binder = LocalBinder()
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private var listener: LocationListener? = null
    private var lastFix: NavigationFix? = null
    private var recoveryNeeded = false
    private var expiry: Job? = null
    private var retryAt = 0L

    internal inner class LocalBinder : Binder() {
        fun add(consumer: (NavigationFix?, Boolean) -> Unit) {
            consumers.add(consumer)
            consumer(lastFix, recoveryNeeded)
            sync()
        }
        fun remove(consumer: (NavigationFix?, Boolean) -> Unit) {
            consumers.remove(consumer)
            sync()
        }
        fun retryIfNeeded() {
            if (listener == null && consumers.isNotEmpty() && !TrackingService.isRunning &&
                SystemClock.elapsedRealtime() >= retryAt) sync()
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch { TrackingService.running.collect { sync() } }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun publish(fix: NavigationFix?, failed: Boolean = false) {
        lastFix = fix
        recoveryNeeded = failed
        consumers.toList().forEach { it(fix, failed) }
    }

    private fun stopUpdates(failed: Boolean = false) {
        val old = listener
        listener = null
        if (old != null) runCatching { manager.removeUpdates(old) }
        expiry?.cancel()
        expiry = null
        publish(null, failed)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @SuppressLint("MissingPermission")
    private fun sync() {
        if (consumers.isEmpty() || TrackingService.isRunning || !TrackingController.hasFineLocation(this)) {
            stopUpdates()
            return
        }
        if (listener != null) return
        val window = CarMapFixWindow(SystemClock.elapsedRealtimeNanos())
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this || TrackingService.isRunning) return
                val now = System.currentTimeMillis()
                if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
                val fix = NavigationFix(WeatherCoordinate(location.latitude, location.longitude), location.time,
                    if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
                    location.speed.takeIf { location.hasSpeed() && it.isFinite() && it >= 0 },
                    location.bearing.takeIf { location.hasBearing() && it.isFinite() })
                val remaining = window.accept(fix, location.elapsedRealtimeNanos, now) ?: return
                expiry?.cancel()
                publish(fix)
                val source = this
                expiry = scope.launch {
                    delay(remaining)
                    if (listener === source) publish(null)
                }
            }
            override fun onProviderDisabled(provider: String) {
                if (listener === this) { expiry?.cancel(); publish(null) }
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy location callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            val channel = "car_map_location"
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "Android Auto haritası", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("İz · Android Auto haritası").setContentText("Konum araç haritasında gösteriliyor.")
                .setOngoing(true).setOnlyAlertOnce(true).build()
            startForeground(4011, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            listener = callback
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, callback, Looper.getMainLooper())
            publish(null)
        } catch (_: RuntimeException) {
            // Missing GPS or a platform restriction on foreground location leaves the map without live data.
            retryAt = SystemClock.elapsedRealtime() + 15_000
            stopUpdates(failed = true)
        }
    }

    override fun onDestroy() {
        stopUpdates()
        consumers.clear()
        scope.cancel()
        super.onDestroy()
    }
}
