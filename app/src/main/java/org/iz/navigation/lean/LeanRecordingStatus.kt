package org.iz.navigation.lean

internal enum class LeanRecordingPhase {
    WAITING_FOR_DATA, RECORDING, SENSOR_UNAVAILABLE, ACCELEROMETER_DATA_MISSING,
    GYROSCOPE_DATA_MISSING, BOTH_SENSORS_DATA_MISSING, STORAGE_UNAVAILABLE,
}

internal data class LeanRecordingStatus(
    val journeyId: String,
    val calibrationEpoch: String,
    val phase: LeanRecordingPhase,
    val sensorFailure: CalibrationFailure? = null,
    val gnssHint: String? = null,
)

/** Timestamps from a committed batch. An accepted empty batch proves nothing. */
internal data class LeanPersistedBatch(
    val accelerometerNanos: Long? = null,
    val gyroscopeNanos: Long? = null,
    val fixes: List<LeanGnssRecord> = emptyList(),
)

internal class LeanSensorCommitProgress(private val startedAt: Long) {
    private var accelerometer: Long? = null
    private var gyroscope: Long? = null

    fun commit(batch: LeanPersistedBatch) {
        batch.accelerometerNanos?.takeIf { it >= startedAt }?.let { accelerometer = maxOf(accelerometer ?: it, it) }
        batch.gyroscopeNanos?.takeIf { it >= startedAt }?.let { gyroscope = maxOf(gyroscope ?: it, it) }
    }

    fun phase(now: Long): LeanRecordingPhase {
        fun fresh(sample: Long?) = sample != null && now - sample in 0..WAIT_NANOS
        val accel = fresh(accelerometer)
        val gyro = fresh(gyroscope)
        return when {
            accel && gyro -> LeanRecordingPhase.RECORDING
            now - startedAt <= WAIT_NANOS -> LeanRecordingPhase.WAITING_FOR_DATA
            accel -> LeanRecordingPhase.GYROSCOPE_DATA_MISSING
            gyro -> LeanRecordingPhase.ACCELEROMETER_DATA_MISSING
            else -> LeanRecordingPhase.BOTH_SENSORS_DATA_MISSING
        }
    }

    private companion object { const val WAIT_NANOS = 10_000_000_000L }
}
