package org.iz.navigation.lean

import java.util.BitSet

/** Offsets and gaps use the sensor's monotonic clock. A span does not imply uninterrupted coverage. */
data class LeanStreamTiming(
    val count: Long,
    val firstOffsetNanos: Long?,
    val lastOffsetNanos: Long?,
    val longestIntervalNanos: Long?,
)

data class LeanSensorTiming(
    val durationNanos: Long,
    val accelerometer: LeanStreamTiming,
    val gyroscope: LeanStreamTiming,
)

data class LeanGnssIssue(val code: String, val unit: String, val count: Long, val durationNanos: Long)
data class LeanGnssDiagnostics(val issues: List<LeanGnssIssue>)

data class LeanReportSession(
    val sessionId: String,
    val calibrationEpoch: String,
    val startedAtUtc: Long,
    val sensorTiming: LeanSensorTiming?,
    val gnssDiagnostics: LeanGnssDiagnostics?,
)

/** Only compact measurement times are retained, never vectors or locations. Raw input is size-bounded by LeanStore. */
internal class LeanSensorTimingCounter(private val start: Long, private val end: Long) {
    private val accel = Stream()
    private val gyro = Stream()

    fun accept(record: LeanRecord) {
        if (record !is LeanImuRecord || record.elapsedNanos !in start..end || record.sequence < 0) return
        val stream = if (record.kind == LeanSensorKind.ACCELEROMETER) accel else gyro
        stream.add(record.sequence, record.elapsedNanos - start)
    }

    fun finish() = LeanSensorTiming(end - start, accel.finish(), gyro.finish())

    private class Stream {
        private val sequences = BitSet()
        private val sparseSequences = mutableSetOf<Long>()
        private var offsets = LongArray(128)
        private var size = 0
        private var outOfOrder = false

        fun add(sequence: Long, offset: Long) {
            // The recorder starts each stream at zero. A sparse imported sequence must not allocate by its value.
            if (sequence <= 4_000_000) {
                val index = sequence.toInt()
                if (sequences.get(index)) return
                sequences.set(index)
            } else if (!sparseSequences.add(sequence)) return
            if (size == offsets.size) offsets = offsets.copyOf(size * 2)
            if (size > 0 && offset < offsets[size - 1]) outOfOrder = true
            offsets[size++] = offset
        }

        fun finish(): LeanStreamTiming {
            if (size == 0) return LeanStreamTiming(0, null, null, null)
            if (outOfOrder) offsets.sort(0, size)
            var previous = offsets[0]
            var longest = 0L
            for (i in 1 until size) {
                val gap = offsets[i] - previous
                longest = maxOf(longest, gap)
                previous = offsets[i]
            }
            return LeanStreamTiming(size.toLong(), offsets[0], previous, longest.takeIf { size > 1 })
        }
    }
}
