package org.iz.navigation.lean

enum class LeanSensorKind { ACCELEROMETER, GYROSCOPE }
enum class LeanMount { UNKNOWN, HANDLEBAR, BODY }

data class LeanCalibration(
    val epoch: String, val createdAt: Long,
    val uprightGravity: List<Double>, val gyroBias: List<Double>,
    val bodyConfirmed: Boolean = true,
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
) : LeanRecord

data class LeanSessionToken(val id: String, val journeyId: String, val generation: Long)

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
)

/** Peaks are nonnegative magnitudes, with direction carried by the field name. Missing is null. */
data class LeanEstimate(
    val algorithmVersion: String, val calibrationEpoch: String,
    val leftDegrees: Double?, val rightDegrees: Double?,
    val validMillis: Long, val observedMillis: Long,
    val partialReasons: Set<String>, val curves: List<LeanCurveResult>,
    val frameRotation: List<Double> = emptyList(),
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
)

/** Metadata only; raw BLOBs are streamed under the store snapshot lease. */
data class LeanChunkManifest(
    val partIndex: Int, val byteCount: Int, val sha256: String, val recordCount: Int,
    val firstElapsedNanos: Long, val lastElapsedNanos: Long,
)
data class LeanBackupSession(
    val input: LeanInputSession, val state: String,
    val chunks: List<LeanChunkManifest>, val result: LeanEstimate? = null,
)