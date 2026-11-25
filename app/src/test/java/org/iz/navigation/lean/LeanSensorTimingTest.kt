package org.iz.navigation.lean

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LeanSensorTimingTest {
    private fun reading(seq: Long, time: Long, sensor: LeanSensorKind = LeanSensorKind.ACCELEROMETER) =
        LeanImuRecord(sensor, seq, time, 0.0, 0.0, 0.0, 3)

    @Test fun uniqueRecordedSequencesAndChronologicalGapsHaveSeparateMeanings() {
        val timing = LeanSensorTimingCounter(100, 200)
        listOf(reading(1, 140), reading(3, 180), reading(2, 140), reading(3, 180),
            reading(0, 120), reading(8, 99), reading(9, 201), reading(2, 125, LeanSensorKind.GYROSCOPE))
            .forEach(timing::accept)
        val summary = timing.finish()
        assertEquals(100L, summary.durationNanos)
        assertEquals(LeanStreamTiming(4, 20, 80, 40), summary.accelerometer)
        assertEquals(20L, summary.durationNanos - summary.accelerometer.lastOffsetNanos!!)
        assertEquals(60L, summary.accelerometer.lastOffsetNanos!! - summary.accelerometer.firstOffsetNanos!!)
        assertEquals(LeanStreamTiming(1, 25, 25, null), summary.gyroscope)
    }

    @Test fun simultaneousDistinctEventsHaveARealZeroGapAndNoEventsHaveNoGap() {
        val timing = LeanSensorTimingCounter(100, 200)
        listOf(reading(0, 100), reading(Long.MAX_VALUE, 100), reading(Long.MAX_VALUE, 100)).forEach(timing::accept)
        val summary = timing.finish()
        assertEquals(LeanStreamTiming(2, 0, 0, 0), summary.accelerometer)
        assertEquals(LeanStreamTiming(0, null, null, null), summary.gyroscope)
        val original = LeanEstimate("test-v1", "epoch", null, null, 0, 0, emptySet(), emptyList(),
            sensorTiming = summary, gnssDiagnostics = LeanGnssDiagnostics(listOf(LeanGnssIssue("missing_speed", "pair", 1, 50))))
        val encoded = LeanCodec.estimateJson(original)
        assertEquals(original, LeanCodec.estimate(encoded))
        val legacy = JSONObject(encoded).apply { remove("sensorTiming"); remove("gnssIssues") }
        assertNull(LeanCodec.estimate(legacy.toString()).sensorTiming)
        assertNull(LeanCodec.estimate(legacy.toString()).gnssDiagnostics)
    }

    @Test fun invalidRangeOrImpossibleGapIsRejectedBeforeRestore() {
        fun encode(stream: LeanStreamTiming) = LeanCodec.estimateJson(LeanEstimate("test-v1", "epoch",
            null, null, 0, 0, emptySet(), emptyList(), sensorTiming = LeanSensorTiming(100, stream, stream)))
        assertThrows(IllegalArgumentException::class.java) { encode(LeanStreamTiming(2, 10, 40, 29)) }
        assertThrows(IllegalArgumentException::class.java) { encode(LeanStreamTiming(1, 101, 101, null)) }
        assertThrows(IllegalArgumentException::class.java) { encode(LeanStreamTiming(0, 0, 0, 0)) }
    }
}
