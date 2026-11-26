package org.iz.navigation.lean

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LiveLeanReading(val sessionId: String, val signedDegrees: Double, val measuredElapsedMillis: Long)

/** A batch has one delivery time for multiple measurements and cannot corroborate their individual times. */
internal fun LocationResult.singleFixReceipt(receivedElapsedNanos: Long): Long? =
    receivedElapsedNanos.takeIf { locations.size == 1 }

internal fun Location.toLeanFix(singleFixReceivedElapsedNanos: Long? = null): LeanGnssRecord? {
    if (elapsedRealtimeNanos <= 0 || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    // Preserve finite out-of-range readings so the estimator can distinguish them from absent metadata.
    fun measured(present: Boolean, number: Float) = number.toDouble().takeIf { present && it.isFinite() }
    @Suppress("DEPRECATION") val mock = isFromMockProvider
    val uncertainty = if (hasElapsedRealtimeUncertaintyNanos()) elapsedRealtimeUncertaintyNanos else null
    val invalidUncertainty = uncertainty != null && !uncertainty.isFinite()
    return LeanGnssRecord(elapsedRealtimeNanos, time, latitude, longitude, measured(hasAccuracy(), accuracy),
        measured(hasSpeed(), speed), measured(hasBearing(), bearing), measured(hasSpeedAccuracy(), speedAccuracyMetersPerSecond),
        measured(hasBearingAccuracy(), bearingAccuracyDegrees), uncertainty?.takeIf { it.isFinite() },
        mock, provider?.take(64), singleFixReceivedElapsedNanos?.takeIf { it >= elapsedRealtimeNanos && !invalidUncertainty },
        invalidUncertainty)
}

/** Borrows navigation fixes for the foreground display. IMU samples stay in a bounded in-memory window. */
internal object LiveLeanCapture {
    private class Window(val sessionId: String, val calibration: LeanCalibration, val publish: (LiveLeanReading?) -> Unit) {
        val since = SystemClock.elapsedRealtimeNanos()
        val samples = LiveLeanSampleWindow()
        var lastFix: LeanGnssRecord? = null
        fun reset() { lastFix = null; samples.clear() }
    }

    private val mutableDemand = MutableStateFlow<String?>(null)
    val demand = mutableDemand.asStateFlow()
    private var current: Window? = null
    private var sensors: LeanSensorCapture? = null

    @Synchronized fun attach(context: Context, sessionId: String, calibration: LeanCalibration,
        publish: (LiveLeanReading?) -> Unit): () -> Unit {
        stop()
        val window = Window(sessionId, calibration, publish)
        val capture = LeanSensorCapture(context, { record -> receiveImu(window, record) }, { invalidate(window) })
        current = window
        if (capture.startOrFailure() == null) {
            sensors = capture
            mutableDemand.value = sessionId
        } else stop()
        return { synchronized(this) { if (current === window) stop() } }
    }

    private fun stop() {
        current?.publish(null)
        current = null
        sensors?.stop()
        sensors = null
        mutableDemand.value = null
    }

    @Synchronized private fun invalidate(owner: Window) {
        if (current === owner) {
            owner.reset()
            owner.publish(null)
        }
    }

    @Synchronized private fun receiveImu(owner: Window, record: LeanImuRecord) {
        if (current !== owner || record.elapsedNanos < owner.since) return
        owner.samples.add(record)
    }

    @Synchronized fun accept(sessionId: String?, location: Location) {
        val window = current?.takeIf { it.sessionId == sessionId } ?: return
        val fix = location.toLeanFix()
        if (fix == null || fix.elapsedNanos < window.since ||
            SystemClock.elapsedRealtimeNanos() - fix.elapsedNanos !in 0 until 1_500_000_000L) {
            invalidate(window)
            return
        }
        val previous = window.lastFix
        window.lastFix = fix
        val degrees = previous?.let {
            HandlebarLeanEstimator.liveDegrees(window.calibration, it, fix, window.samples.before(fix.elapsedNanos))
        }
        window.publish(degrees?.let { LiveLeanReading(window.sessionId, it, fix.elapsedNanos / 1_000_000L) })
    }
}
