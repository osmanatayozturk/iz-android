package org.iz.navigation.lean

import org.junit.Assert.*
import org.junit.Test

class LeanCodecTest {
    @Test fun rawRoundTripPreservesMeasurementClockAndAbsentAccuracy() {
        val records = listOf<LeanRecord>(
            LeanImuRecord(LeanSensorKind.GYROSCOPE, 7, 9007199254740993L, .1, -.2, .3, 3),
            LeanGnssRecord(9007199254740994L, 1700000000000L, 40.0, 29.0, 4.0,
                null, null, null, null, null, false, "gps"),
        )
        assertEquals(records, LeanCodec.decodeRecords(LeanCodec.encodeRecords(records)).toList())
    }
    @Test fun nonFiniteRawCannotEnterDurableInput() {
        val invalid = LeanImuRecord(LeanSensorKind.ACCELEROMETER, 1, 10, Double.NaN, 0.0, 1.0, 0)
        assertThrows(IllegalArgumentException::class.java) { LeanCodec.encodeRecords(listOf(invalid)) }
    }
    @Test fun unavailableResultRemainsNullWithVersionAndCoverage() {
        val value = LeanEstimate("stanglmayr-v1", "epoch-1", null, null, 0, 1200,
            setOf("NO_VALID_FRAME"), emptyList())
        assertEquals(value, LeanCodec.estimate(LeanCodec.estimateJson(value)))
    }
}
