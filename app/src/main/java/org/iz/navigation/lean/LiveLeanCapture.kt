package org.iz.navigation.lean

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LiveLeanReading(val sessionId: String, val signedDegrees: Double, val measuredElapsedMillis: Long,
    val estimated: Boolean = false)

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
internal object LiveLeanCapture : LiveLeanEngine(SystemClock::elapsedRealtimeNanos,
    { action, millis -> liveHandler.postDelayed(action, millis) }, { action -> liveHandler.removeCallbacks(action) }) {
    fun attach(context: Context, sessionId: String, calibration: LeanCalibration, journeyId: String? = null,
        hasInitialFix: Boolean = true, publish: (LiveLeanReading?) -> Unit) =
        attach(sessionId, calibration, journeyId, hasInitialFix, { onRecord, onDropped ->
            val capture = LeanSensorCapture(context, onRecord, onDropped)
            if (capture.startOrFailure() == null) capture::stop else null
        }, publish)
}

private val liveHandler by lazy { Handler(Looper.getMainLooper()) }

internal open class LiveLeanEngine(private val elapsed: () -> Long,
    private val postTimeout: (Runnable, Long) -> Unit, private val cancelTimeout: (Runnable) -> Unit) {
    private inner class Window(val sessionId: String, val calibration: LeanCalibration, val journeyId: String?,
        val publish: (LiveLeanReading?) -> Unit) {
        var since = elapsed()
        val samples = LiveLeanSampleWindow()
        val tracker = LiveHandlebarTracker()
        var deadline = LiveFixDeadline(since)
        val timeout = Runnable { expire(this) }
        var lastFix: LeanGnssRecord? = null
        var sensorFailed = false
        var dropped = false
        var recorderEnded = false
        fun reset() { lastFix = null; samples.clear(); tracker.reset() }
    }

    private data class Recorder(val token: LeanSessionToken, val epoch: String, val event: (LiveHandlebarDecision?) -> Unit)
    private var recorder: Recorder? = null
    private val mutableTarget = MutableStateFlow<Pair<String, String>?>(null)
    val recordingTarget = mutableTarget.asStateFlow()
    private val mutableDemand = MutableStateFlow<String?>(null)
    val demand = mutableDemand.asStateFlow()
    private var current: Window? = null
    private var stopSensors: (() -> Unit)? = null

    @Synchronized fun attach(sessionId: String, calibration: LeanCalibration,
        journeyId: String?, hasInitialFix: Boolean,
        subscribe: ((LeanImuRecord) -> Unit, () -> Unit) -> (() -> Unit)?,
        publish: (LiveLeanReading?) -> Unit): () -> Unit {
        stop()
        val window = Window(sessionId, calibration, journeyId, publish)
        current = window
        matched(window)?.event?.invoke(null) // Visible dial. No decision or angle has been produced yet.
        if (!calibration.bodyConfirmed && journeyId != null) mutableTarget.value = journeyId to calibration.epoch
        stopSensors = subscribe({ record -> receiveImu(window, record) }, { sensorDropped(window) })
        if (stopSensors != null) {
            mutableDemand.value = sessionId
            if (!calibration.bodyConfirmed) expire(window, noInitialFix = !hasInitialFix)
        } else {
            window.sensorFailed = true
            reject(window, LiveHandlebarReason.SENSOR_UNAVAILABLE)
        }
        return { synchronized(this) { if (current === window) stop() } }
    }

    private fun matched(window: Window): Recorder? = recorder?.takeIf {
        !window.calibration.bodyConfirmed && it.token.journeyId == window.journeyId && it.epoch == window.calibration.epoch
    }

    @Synchronized fun bindRecorder(token: LeanSessionToken, epoch: String, event: (LiveHandlebarDecision?) -> Unit) {
        recorder = Recorder(token, epoch, event)
        val window = current ?: return
        if (matched(window) == null) return
        // A recorder can start after the visible window. Only subsequent evidence belongs to its token.
        val hadNoFix = window.deadline.expiredWithoutFix
        window.recorderEnded = false
        window.reset()
        window.publish(null)
        window.since = elapsed()
        window.deadline = LiveFixDeadline(window.since)
        event(null)
        if (window.sensorFailed) reject(window, LiveHandlebarReason.SENSOR_UNAVAILABLE) else expire(window, hadNoFix)
    }

    @Synchronized fun unbindRecorder(token: LeanSessionToken) {
        if (recorder?.token != token) return
        recorder = null
        current?.takeIf { it.journeyId == token.journeyId }?.let { window ->
            window.recorderEnded = true
            cancelTimeout(window.timeout)
            window.reset()
            window.publish(null)
        }
    }

    private fun stop() {
        current?.let { cancelTimeout(it.timeout) }
        current?.publish(null)
        current = null
        mutableTarget.value = null
        stopSensors?.invoke()
        stopSensors = null
        mutableDemand.value = null
    }

    private fun reject(window: Window, reason: LiveHandlebarReason) {
        window.reset()
        window.publish(null)
        matched(window)?.event?.invoke(LiveHandlebarDecision.Rejected(reason))
    }

    @Synchronized private fun sensorDropped(window: Window) {
        if (current !== window || window.recorderEnded || window.dropped) return
        window.dropped = true
        reject(window, LiveHandlebarReason.SENSOR_STREAM_GAP)
    }

    @Synchronized private fun expire(window: Window, noInitialFix: Boolean = false) {
        if (current !== window || window.recorderEnded || window.sensorFailed) return
        val now = elapsed()
        cancelTimeout(window.timeout)
        window.deadline.expire(now, noInitialFix)?.let { absence ->
            reject(window, if (absence == LiveFixDeadline.Absence.NEVER_RECEIVED) LiveHandlebarReason.GNSS_NEVER_RECEIVED
                else LiveHandlebarReason.GNSS_STALE)
        }
        window.deadline.remaining(now)?.let { postTimeout(window.timeout, (it + 999_999L) / 1_000_000L) }
    }

    @Synchronized private fun receiveImu(owner: Window, record: LeanImuRecord) {
        if (current !== owner || owner.recorderEnded || record.elapsedNanos < owner.since) return
        owner.samples.add(record)
    }

    @Synchronized fun accept(sessionId: String?, journeyId: String?, location: Location, received: Long?, batched: Boolean,
        navigationFixAvailable: Boolean = true) {
        val window = current?.takeIf { it.sessionId == sessionId && it.journeyId == journeyId &&
            !it.sensorFailed && !it.recorderEnded } ?: return
        if (window.calibration.bodyConfirmed && !navigationFixAvailable) return
        val fix = location.toLeanFix(received)
        if (fix == null) {
            reject(window, LiveHandlebarReason.INVALID_FIX)
            return
        }
        if (fix.elapsedNanos < window.since) {
            // A queued delivery from before attach cannot support this window.
            window.reset()
            window.publish(null)
            return
        }
        val now = elapsed()
        // A busy UI looper may deliver a new callback before the previous expiry runnable gets CPU.
        if (!window.calibration.bodyConfirmed && window.deadline.remaining(now) == 0L) expire(window)
        window.deadline.observed(fix.elapsedNanos, now)
        if (now < fix.elapsedNanos || !window.calibration.bodyConfirmed && received != null &&
            (received < fix.elapsedNanos || received > now)) {
            reject(window, LiveHandlebarReason.GNSS_RECEIPT_CLOCK)
            return
        }
        if (!window.calibration.bodyConfirmed) expire(window)
        if (now - fix.elapsedNanos >= LiveFixDeadline.LIFETIME_NANOS) {
            window.reset()
            window.publish(null)
            return
        }
        window.dropped = false
        if (!window.calibration.bodyConfirmed) {
            val decision = window.tracker.evaluate(window.calibration, fix, window.samples.before(fix.elapsedNanos), now, batched)
            val accepted = decision as? LiveHandlebarDecision.Accepted
            window.publish(accepted?.let { LiveLeanReading(window.sessionId, it.signedDegrees,
                it.measuredElapsedNanos / 1_000_000L, it.source == LiveHandlebarSource.ESTIMATED) })
            matched(window)?.event?.invoke(decision)
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
