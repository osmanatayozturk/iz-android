package org.iz.navigation.lean

/** Keep the full 1.6 s fix interval even when delivery lags by 1.5 s, plus an IMU alignment margin. */
internal class LiveLeanSampleWindow {
    private val acceleration = ArrayDeque<LeanImuRecord>()
    private val rotation = ArrayDeque<LeanImuRecord>()

    fun add(record: LeanImuRecord) {
        val queue = if (record.kind == LeanSensorKind.ACCELEROMETER) acceleration else rotation
        while (queue.isNotEmpty() && record.elapsedNanos - queue.first().elapsedNanos > RETENTION_NANOS) {
            queue.removeFirst()
        }
        if (queue.size == MAX_PER_SENSOR) queue.removeFirst()
        queue.addLast(record)
    }

    fun before(fixNanos: Long): List<LeanImuRecord> =
        (acceleration.asSequence() + rotation.asSequence())
            .filter { it.elapsedNanos < fixNanos && fixNanos - it.elapsedNanos <= 2_000_000_000L }
            .sortedBy { it.elapsedNanos }.toList()

    fun clear() {
        acceleration.clear()
        rotation.clear()
    }

    companion object {
        private const val RETENTION_NANOS = 3_200_000_000L
        private const val MAX_PER_SENSOR = 768 // 641 rows cover 3.2 s at 200 Hz, including both endpoints.
        const val MAX_RECORDS = 2 * MAX_PER_SENSOR
    }
}
