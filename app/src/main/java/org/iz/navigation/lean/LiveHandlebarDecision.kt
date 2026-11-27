package org.iz.navigation.lean

import kotlin.math.abs

internal enum class LiveHandlebarSource { MEASURED, ESTIMATED }

internal enum class LiveHandlebarReason {
    GNSS_NEVER_RECEIVED,
    GNSS_STALE,
    SENSOR_UNAVAILABLE,
    SENSOR_STREAM_GAP,
    INVALID_FIX,
    WAITING_FOR_PAIR,
    INVALID_CALIBRATION,
    GNSS_BATCH,
    GNSS_NO_SINGLE_FIX_RECEIPT,
    GNSS_RECEIPT_STALE,
    GNSS_RECEIPT_CLOCK,
    GNSS_RECEIPT_JITTER,
    GNSS_SOURCE_UNSUPPORTED,
    GNSS_SOURCE_CHANGED,
    GNSS_CLOCK,
    GNSS_TIME_UNCERTAIN,
    GNSS_FIX_CONTINUITY,
    GNSS_QUALITY,
    GNSS_MOTION,
    SENSOR_QUALITY,
    SENSOR_DISAGREEMENT,
    TURN_SUPPORT,
    TURN_DIRECTION_CHANGED,
}

internal sealed interface LiveHandlebarDecision {
    data class Accepted(
        val signedDegrees: Double,
        val source: LiveHandlebarSource,
        val measuredElapsedNanos: Long,
    ) : LiveHandlebarDecision

    data class Rejected(
        val reason: LiveHandlebarReason,
        val gnssIssue: LeanGnssIssueCode? = null,
    ) : LiveHandlebarDecision
}

/** One live owner calls this serially. Reset on session/sensor lifecycle changes. */
internal class LiveHandlebarTracker {
    private var calibration: LeanCalibration? = null
    private var previous: LeanGnssRecord? = null
    private val turnFixes = ArrayDeque<LeanGnssRecord>(MIN_TURN_PAIRS + 1)
    private var turnDirection = 0

    fun reset() {
        calibration = null
        previous = null
        clearSupport()
    }

    fun evaluate(
        calibration: LeanCalibration,
        fix: LeanGnssRecord,
        imu: List<LeanImuRecord>,
        nowElapsedNanos: Long,
        fromBatch: Boolean = false,
    ): LiveHandlebarDecision {
        if (this.calibration != calibration) {
            reset()
            this.calibration = calibration
        }
        if (!HandlebarLeanEstimator.validLiveHandlebarCalibration(calibration))
            return invalidate(LiveHandlebarReason.INVALID_CALIBRATION)
        if (fix.elapsedNanos <= 0 || fix.elapsedNanos > nowElapsedNanos || fix.utcMillis <= 0 ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0)
            return invalidate(LiveHandlebarReason.INVALID_FIX)
        if (nowElapsedNanos - fix.elapsedNanos >= MAX_LIVE_FIX_AGE_NANOS)
            return invalidate(LiveHandlebarReason.GNSS_STALE)

        val first = previous
        val estimated = fix.elapsedUncertaintyNanos == null
        val issues = LeanGnssQuality.fixIssues(fix, LeanMount.HANDLEBAR)
        if (LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE in issues)
            return invalidate(LiveHandlebarReason.GNSS_TIME_UNCERTAIN, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE)
        if (fromBatch && (estimated || first?.let { it.elapsedUncertaintyNanos == null } == true))
            return invalidate(LiveHandlebarReason.GNSS_BATCH)
        val issue = issues.firstOrNull { it != LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_MISSING }
        if (issue != null) return invalidate(LiveHandlebarReason.GNSS_QUALITY, issue)
        if (estimated) HandlebarLeanEstimator.liveSingleFixIssue(fix, nowElapsedNanos)?.let { return invalidate(it) }
        if (first != null && fix.elapsedNanos <= first.elapsedNanos) {
            return invalidate(LiveHandlebarReason.GNSS_FIX_CONTINUITY,
                if (fix.elapsedNanos == first.elapsedNanos) LeanGnssIssueCode.GNSS_DUPLICATE_TIME
                else LeanGnssIssueCode.GNSS_RATE_UNSUITABLE)
        }
        previous = fix
        if (first == null) return LiveHandlebarDecision.Rejected(LiveHandlebarReason.WAITING_FOR_PAIR)
        if ((first.elapsedUncertaintyNanos == null) != estimated) {
            clearSupport()
            return LiveHandlebarDecision.Rejected(LiveHandlebarReason.GNSS_TIME_UNCERTAIN)
        }
        val pair = HandlebarLeanEstimator.liveHandlebarPair(calibration, first, fix, imu, estimated)
        if (!estimated || pair !is LiveHandlebarDecision.Accepted) {
            clearSupport()
            return pair
        }
        return updateSupport(first, fix, pair)
    }

    private fun invalidate(reason: LiveHandlebarReason, issue: LeanGnssIssueCode? = null): LiveHandlebarDecision.Rejected {
        previous = null
        clearSupport()
        return LiveHandlebarDecision.Rejected(reason, issue)
    }

    private fun updateSupport(
        first: LeanGnssRecord,
        last: LeanGnssRecord,
        pair: LiveHandlebarDecision.Accepted,
    ): LiveHandlebarDecision {
        val direction = if (pair.signedDegrees < 0.0) -1 else 1
        if (turnDirection != 0 && turnDirection != direction) {
            clearSupport()
            return LiveHandlebarDecision.Rejected(LiveHandlebarReason.TURN_DIRECTION_CHANGED)
        }
        turnDirection = direction
        if (turnFixes.isEmpty()) turnFixes.addLast(first)
        turnFixes.addLast(last)
        if (turnFixes.size > MIN_TURN_PAIRS + 1) turnFixes.removeFirst()
        val earliest = turnFixes.first()
        val utcDifference = (last.utcMillis - earliest.utcMillis) -
            (last.elapsedNanos - earliest.elapsedNanos) / NANOS_PER_MILLI
        if (abs(utcDifference) > MAX_CHAIN_UTC_DRIFT_MILLIS) {
            clearSupport()
            return LiveHandlebarDecision.Rejected(LiveHandlebarReason.GNSS_CLOCK)
        }
        fun age(fix: LeanGnssRecord) = requireNotNull(fix.singleFixReceivedElapsedNanos) - fix.elapsedNanos
        if (turnFixes.maxOf(::age) - turnFixes.minOf(::age) > MAX_CHAIN_RECEIPT_JITTER_NANOS) {
            clearSupport()
            return LiveHandlebarDecision.Rejected(LiveHandlebarReason.GNSS_RECEIPT_JITTER)
        }
        return if (turnFixes.size == MIN_TURN_PAIRS + 1) pair
        else LiveHandlebarDecision.Rejected(LiveHandlebarReason.TURN_SUPPORT)
    }

    private fun clearSupport() {
        turnDirection = 0
        turnFixes.clear()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_LIVE_FIX_AGE_NANOS = 1_500_000_000L
        const val MIN_TURN_PAIRS = 4
        const val MAX_CHAIN_UTC_DRIFT_MILLIS = 150L
        const val MAX_CHAIN_RECEIPT_JITTER_NANOS = 60_000_000L
    }
}
