package org.iz.navigation.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.iz.navigation.IzApplication
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

/** Independent, foreground-only diagnostic subscriptions. Never calls the app's navigation/tracker. */
internal class LocationSourceComparison(context: Context) {
    private val app = context.applicationContext as IzApplication
    private val manager = app.getSystemService(LocationManager::class.java)
    private val capture = LocationComparisonCapture(AndroidComparisonSources(app, manager), SystemClock::elapsedRealtimeNanos) {
        val blocked = parkedDiagnosticBlock(app.navigation.state.value, TrackingService.isRunning, System.currentTimeMillis())
        when {
            !preciseGranted(app) -> ComparisonIssue.FINE_PERMISSION
            blocked != null -> blocked
            manager == null || !manager.isLocationEnabled -> ComparisonIssue.LOCATION_DISABLED
            LocationManager.GPS_PROVIDER !in manager.allProviders -> ComparisonIssue.GPS_UNAVAILABLE
            !manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> ComparisonIssue.PROVIDER_DISABLED
            else -> null
        }
    }
    val state get() = capture.state
    fun clear() = capture.clear()
    suspend fun measure(onUpdate: (LocationComparisonSnapshot) -> Unit = {}): LocationComparisonSnapshot =
        withContext(Dispatchers.Main.immediate) { capture.measure(onUpdate) }

    companion object {
        fun preciseGranted(context: Context) = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

private class AndroidComparisonSources(context: Context, private val manager: LocationManager?) : ComparisonSources {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // Capture checks fine permission before registering and throughout the window.
    override suspend fun open(source: ComparisonSourceId, samples: (List<ComparisonSample>) -> Unit,
        availability: (Boolean) -> Unit, failure: (ComparisonIssue) -> Unit): ComparisonRegistration = when (source) {
        ComparisonSourceId.GPS -> {
            val gps = manager ?: throw ComparisonAcquisitionFailure(ComparisonIssue.GPS_UNAVAILABLE, source)
            val closed = AtomicBoolean()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { if (!closed.get()) samples(listOf(location.scalarSample())) }
                override fun onProviderDisabled(provider: String) {
                    if (!closed.get() && provider == LocationManager.GPS_PROVIDER) failure(ComparisonIssue.PROVIDER_DISABLED)
                }
                override fun onProviderEnabled(provider: String) { if (!closed.get()) availability(true) }
                @Deprecated("Required on Android 10")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            val registration = ComparisonRegistration {
                if (closed.compareAndSet(false, true)) gps.removeUpdates(listener)
            }
            try {
                gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000, 0f, listener, Looper.getMainLooper())
                registration
            } catch (security: SecurityException) { registration.close(); throw security }
            catch (_: Exception) { registration.close(); throw ComparisonAcquisitionFailure(ComparisonIssue.REGISTRATION_FAILED, source) }
        }
        ComparisonSourceId.FUSED -> {
            val closed = AtomicBoolean()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (!closed.get()) samples(result.locations.map { it.scalarSample() })
                }
                override fun onLocationAvailability(value: LocationAvailability) {
                    if (!closed.get()) availability(value.isLocationAvailable)
                }
            }
            fun remove() { runCatching { fused.removeLocationUpdates(callback) } }
            val registration = ComparisonRegistration { if (closed.compareAndSet(false, true)) remove() }
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
                    .setMinUpdateIntervalMillis(1_000).setMaxUpdateDelayMillis(0).setMaxUpdateAgeMillis(0)
                    .setGranularity(Granularity.GRANULARITY_FINE).setWaitForAccurateLocation(false)
                    .setDurationMillis(15_000).build()
                val task = fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                // Task cancellation does not cancel an in-flight provider registration.
                task.addOnCompleteListener { if (closed.get()) remove() }
                task.await()
                registration
            } catch (cancelled: CancellationException) { registration.close(); throw cancelled }
            catch (security: SecurityException) { registration.close(); throw security }
            catch (_: Exception) { registration.close(); throw ComparisonAcquisitionFailure(ComparisonIssue.REGISTRATION_FAILED, source) }
        }
    }
}

@Suppress("DEPRECATION")
private fun Location.scalarSample() = ComparisonSample(elapsedRealtimeNanos, mapOf(
    ComparisonField.HORIZONTAL_ACCURACY to accuracy.toDouble().takeIf { hasAccuracy() },
    ComparisonField.SPEED to speed.toDouble().takeIf { hasSpeed() },
    ComparisonField.SPEED_ACCURACY to speedAccuracyMetersPerSecond.toDouble().takeIf { hasSpeedAccuracy() },
    ComparisonField.BEARING to bearing.toDouble().takeIf { hasBearing() },
    ComparisonField.BEARING_ACCURACY to bearingAccuracyDegrees.toDouble().takeIf { hasBearingAccuracy() },
    // Only presence/validity is kept, never altitude or a coordinate value.
    ComparisonField.ALTITUDE to (if (altitude.isFinite()) 1.0 else Double.NaN).takeIf { hasAltitude() },
    ComparisonField.VERTICAL_ACCURACY to verticalAccuracyMeters.toDouble().takeIf { hasVerticalAccuracy() },
    ComparisonField.TIME_UNCERTAINTY_MILLIS to (elapsedRealtimeUncertaintyNanos / 1_000_000.0)
        .takeIf { hasElapsedRealtimeUncertaintyNanos() }),
    mock = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider)
