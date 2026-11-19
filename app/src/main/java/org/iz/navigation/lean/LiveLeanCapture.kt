package org.iz.navigation.lean

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LiveLeanReading(val sessionId: String, val signedDegrees: Double, val measuredElapsedMillis: Long)

internal fun Location.toLeanFix(): LeanGnssRecord? {
    if (elapsedRealtimeNanos <= 0 || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    fun measured(present: Boolean, number: Float) = number.toDouble().takeIf { present && it.isFinite() && it >= 0 }
    @Suppress("DEPRECATION") val mock = isFromMockProvider
    return LeanGnssRecord(elapsedRealtimeNanos, time, latitude, longitude, measured(hasAccuracy(), accuracy),
        measured(hasSpeed(), speed), measured(hasBearing(), bearing), measured(hasSpeedAccuracy(), speedAccuracyMetersPerSecond),
        measured(hasBearingAccuracy(), bearingAccuracyDegrees), elapsedRealtimeUncertaintyNanos.takeIf {
            hasElapsedRealtimeUncertaintyNanos() && it.isFinite() && it >= 0
        }, mock, provider?.take(64))
}

/** Foreground display only. Borrows fixes from the existing navigation service and retains at most two seconds of IMU. */
internal object LiveLeanCapture {
    private class Window(val sessionId: String, val calibration: LeanCalibration) {
        val since = SystemClock.elapsedRealtimeNanos()
        val samples = ArrayDeque<LeanImuRecord>()
        var lastFix: LeanGnssRecord? = null
        fun reset() { lastFix = null; samples.clear() }
    }

    private val mutableReading = MutableStateFlow<LiveLeanReading?>(null)
    val readings = mutableReading.asStateFlow()
    private val mutableDemand = MutableStateFlow<String?>(null)
    val demand = mutableDemand.asStateFlow()
    private var current: Window? = null
    private var sensors: LeanSensorCapture? = null

    @Synchronized fun attach(context: Context, sessionId: String, calibration: LeanCalibration): () -> Unit {
        stop()
        val window = Window(sessionId, calibration)
        val capture = LeanSensorCapture(context, { record -> receiveImu(window, record) }, { invalidate(window) })
        current = window
        if (capture.start()) {
            sensors = capture
            mutableDemand.value = sessionId
        } else stop()
        return { synchronized(this) { if (current === window) stop() } }
    }

    private fun stop() {
        current = null
        sensors?.stop()
        sensors = null
        mutableDemand.value = null
        mutableReading.value = null
    }

    @Synchronized private fun invalidate(owner: Window) {
        if (current === owner) {
            owner.reset()
            mutableReading.value = null
        }
    }

    @Synchronized private fun receiveImu(owner: Window, record: LeanImuRecord) {
        if (current !== owner || record.elapsedNanos < owner.since) return
        val buffer = owner.samples
        while (buffer.isNotEmpty() && record.elapsedNanos - buffer.first().elapsedNanos > 2_000_000_000L) buffer.removeFirst()
        if (buffer.size >= 360) invalidate(owner) else buffer.addLast(record)
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
            HandlebarLeanEstimator.liveDegrees(window.calibration, it, fix, window.samples.toList())
        }
        mutableReading.value = degrees?.let { LiveLeanReading(window.sessionId, it, fix.elapsedNanos / 1_000_000L) }
    }
}
