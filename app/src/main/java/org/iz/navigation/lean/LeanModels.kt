package org.iz.navigation.lean

import java.util.Collections

enum class LeanSensorKind { ACCELEROMETER, GYROSCOPE }
enum class LeanMount { UNKNOWN, HANDLEBAR, BODY }

data class LeanCalibration(
    val epoch: String, val createdAt: Long,
    val uprightGravity: List<Double>, val gyroBias: List<Double>,
    val bodyConfirmed: Boolean = true,
    val steeringAxis: List<Double> = emptyList(),
)

sealed interface LeanRecord { val elapsedNanos: Long }

data class LeanImuRecord(
    val kind: LeanSensorKind, val sequence: Long, override val elapsedNanos: Long,
    val x: Double, val y: Double, val z: Double, val accuracy: Int,
) : LeanRecord

data class LeanGnssRecord(
    override val elapsedNanos: Long, val utcMillis: Long,
    val latitude: Double, val longitude: Double, val accuracyMeters: Double?,
    val speedMps: Double?, val bearingDegrees: Double?,
    val speedAccuracyMps: Double?, val bearingAccuracyDegrees: Double?,
    val elapsedUncertaintyNanos: Double?, val mock: Boolean, val provider: String?,
    val singleFixReceivedElapsedNanos: Long? = null,
    val invalidElapsedUncertainty: Boolean = false,
) : LeanRecord

data class LeanSessionToken(val id: String, val journeyId: String, val generation: Long)

enum class LeanLiveDecisionCode {
    ACCEPTED_MEASURED, ACCEPTED_ESTIMATED,
    GNSS_NEVER_RECEIVED, GNSS_STALE, SENSOR_UNAVAILABLE, SENSOR_STREAM_GAP, INVALID_FIX, WAITING_FOR_PAIR,
    INVALID_CALIBRATION, GNSS_BATCH, GNSS_NO_SINGLE_FIX_RECEIPT, GNSS_RECEIPT_STALE, GNSS_RECEIPT_CLOCK,
    GNSS_RECEIPT_JITTER, GNSS_SOURCE_UNSUPPORTED, GNSS_SOURCE_CHANGED, GNSS_CLOCK, GNSS_TIME_UNCERTAIN,
    GNSS_FIX_CONTINUITY, GNSS_QUALITY, GNSS_MOTION, SENSOR_QUALITY, SENSOR_DISAGREEMENT, TURN_SUPPORT, TURN_DIRECTION_CHANGED,
    GNSS_MOCK_FIX, GNSS_LATITUDE_OUT_OF_RANGE, GNSS_LONGITUDE_OUT_OF_RANGE, GNSS_SPEED_MISSING, GNSS_SPEED_OUT_OF_RANGE,
    GNSS_POSITION_ACCURACY_MISSING, GNSS_POSITION_ACCURACY_OUT_OF_RANGE, GNSS_SPEED_ACCURACY_MISSING,
    GNSS_SPEED_ACCURACY_OUT_OF_RANGE, GNSS_TIME_UNCERTAINTY_MISSING, GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE,
    GNSS_BEARING_MISSING, GNSS_BEARING_OUT_OF_RANGE, GNSS_BEARING_ACCURACY_MISSING, GNSS_BEARING_ACCURACY_OUT_OF_RANGE,
    GNSS_DUPLICATE_TIME, GNSS_DATA_GAP, GNSS_RATE_UNSUITABLE, GNSS_INSUFFICIENT_FIXES, CLOCK_DISCONTINUITY,
    LOW_SPEED_UNSUPPORTED, SPEED_TRANSIENT, GNSS_COURSE_UNCERTAIN, GNSS_MOTION_DISAGREEMENT, MODEL_CHANNEL_UNAVAILABLE;

    companion object {
        internal fun from(decision: LiveHandlebarDecision): LeanLiveDecisionCode = when (decision) {
            is LiveHandlebarDecision.Accepted -> if (decision.source == LiveHandlebarSource.MEASURED) ACCEPTED_MEASURED else ACCEPTED_ESTIMATED
            is LiveHandlebarDecision.Rejected -> valueOf(decision.gnssIssue?.name ?: decision.reason.name)
        }
    }
}

/** Immutable cumulative counts only. No measurements, wall clocks, locations or event history. */
class LeanLiveDecisionSummary(val dialOpened: Boolean = false, counts: Map<LeanLiveDecisionCode, Long> = emptyMap()) {
    val counts: Map<LeanLiveDecisionCode, Long>
    init {
        require(counts.size <= allowedCodes.size)
        val frozen = counts.toMap()
        require(frozen.keys.all { it in allowedCodes })
        require(frozen.values.all { it in 0..MAX_DECISIONS } && frozen.values.sum() <= MAX_DECISIONS)
        require(dialOpened || frozen.values.all { it == 0L })
        this.counts = Collections.unmodifiableMap(frozen.filterValues { it > 0 }.toSortedMap())
    }
    val total: Long get() = counts.values.sum()
    fun count(code: LeanLiveDecisionCode): Long = counts[code] ?: 0L
    internal fun follows(previous: LeanLiveDecisionSummary): Boolean =
        (!previous.dialOpened || dialOpened) && previous.counts.all { (code, count) -> this.count(code) >= count }
    override fun equals(other: Any?): Boolean = other is LeanLiveDecisionSummary && dialOpened == other.dialOpened && counts == other.counts
    override fun hashCode(): Int = 31 * dialOpened.hashCode() + counts.hashCode()
    companion object {
        const val MAX_DECISIONS = 10_000_000L
        val allowedCodes: Set<LeanLiveDecisionCode> = Collections.unmodifiableSet(LeanLiveDecisionCode.entries.toSet())
    }
}

data class LeanLiveDecisionOwner(val token: LeanSessionToken, val summary: LeanLiveDecisionSummary)

data class LeanInputSession(
    val token: LeanSessionToken, val calibration: LeanCalibration, val bootId: String,
    val startedAtUtc: Long, val startedAtElapsedNanos: Long, val endedAtElapsedNanos: Long?,
    val inputHash: String, val partialReasons: Set<String>, val droppedSamples: Long,
)

data class LeanCurveResult(
    val startElapsedNanos: Long, val endElapsedNanos: Long,
    val leftDegrees: Double?, val rightDegrees: Double?,
    val latitude: Double?, val longitude: Double?,
    val startUtcMillis: Long? = null, val endUtcMillis: Long? = null,
    val timingEstimated: Boolean = false,
)

/** Peaks are nonnegative magnitudes, with direction carried by the field name. Missing is null. */
data class LeanEstimate(
    val algorithmVersion: String, val calibrationEpoch: String,
    val leftDegrees: Double?, val rightDegrees: Double?,
    val validMillis: Long, val observedMillis: Long,
    val partialReasons: Set<String>, val curves: List<LeanCurveResult>,
    val frameRotation: List<Double> = emptyList(),
    val sensorTiming: LeanSensorTiming? = null,
    val gnssDiagnostics: LeanGnssDiagnostics? = null,
)

interface LeanEstimator {
    fun estimate(session: LeanInputSession, records: Sequence<LeanRecord>, checkAllowed: () -> Unit): LeanEstimate
}

data class LeanJourneyReport(
    val pending: Boolean,
    val leftDegrees: Double?, val rightDegrees: Double?,
    val validMillis: Long, val observedMillis: Long,
    val partialReasons: Set<String>, val curves: List<LeanCurveResult>,
    val algorithmVersions: Set<String>,
    val handlebar: LeanJourneyReport? = null,
    val sessionDetails: List<LeanReportSession> = emptyList(),
)

/** Metadata only; raw BLOBs are streamed under the store snapshot lease. */
data class LeanChunkManifest(
    val partIndex: Int, val byteCount: Int, val sha256: String, val recordCount: Int,
    val firstElapsedNanos: Long, val lastElapsedNanos: Long,
)
data class LeanBackupSession(
    val input: LeanInputSession, val state: String,
    val chunks: List<LeanChunkManifest>, val result: LeanEstimate? = null,
    val liveDecisions: LeanLiveDecisionSummary? = null,
)
