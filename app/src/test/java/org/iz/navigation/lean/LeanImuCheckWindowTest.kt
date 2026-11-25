package org.iz.navigation.lean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeanImuCheckWindowTest {
    @Test fun countsTheTwoStreamsIndependentlyAndRejectsInvalidEvents() {
        val start = 1_000_000_000L
        val window = LeanImuCheckWindow(start)
        val accel = LeanSensorKind.ACCELEROMETER
        val gyro = LeanSensorKind.GYROSCOPE
        window.accept(accel, start + 10, start + 20, valuesUsable = true)
        window.accept(gyro, start + 10, start + 22, valuesUsable = true)
        window.accept(accel, start + 10, start + 23, valuesUsable = true)
        window.accept(accel, start - 1, start + 25, valuesUsable = true)
        window.accept(gyro, start + 24, start + 23, valuesUsable = true)
        window.accept(gyro, start + 25, start + 30, valuesUsable = false)
        assertEquals(LeanImuCheckCounts(3, 1), window.counts().getValue(accel))
        assertEquals(LeanImuCheckCounts(3, 1), window.counts().getValue(gyro))
    }

    @Test fun excludesLateDeliveryAndStopsAtTheMonotonicDeadline() {
        val start = 2_500_000_000L
        val window = LeanImuCheckWindow(start)
        val accel = LeanSensorKind.ACCELEROMETER
        val end = start + LEAN_IMU_CHECK_DURATION_NANOS
        window.accept(accel, start - 2, start - 1, valuesUsable = true)
        window.accept(accel, end - 2, end - 1, valuesUsable = true)
        window.accept(accel, end - 1, end, valuesUsable = true)
        assertEquals(LeanImuCheckCounts(1, 1), window.counts().getValue(accel))
        assertEquals(0L, window.remainingNanos(end))
        assertEquals(1L, window.remainingNanos(end - 1))
        assertEquals(LEAN_IMU_CHECK_DURATION_NANOS, window.remainingNanos(start - 1))
    }

    @Test fun measuresRatesOverTenSecondsAndGapsFromUsableTimestamps() {
        val start = 1_000_000_000L
        val window = LeanImuCheckWindow(start)
        val accel = LeanSensorKind.ACCELEROMETER
        val gyro = LeanSensorKind.GYROSCOPE
        assertEquals(0.0, window.counts().getValue(gyro).usableHertz, 0.0001)
        assertNull(window.counts().getValue(gyro).longestIntervalNanos)

        window.accept(accel, start + 25_000_000L, start + 33_000_000L, valuesUsable = true)
        window.accept(gyro, start + 40_000_000L, start + 45_000_000L, valuesUsable = true)
        window.accept(accel, start + 50_000_000L, start + 55_000_000L, valuesUsable = false)
        window.accept(accel, start + 100_000_000L, start + 151_000_000L, valuesUsable = true)
        window.accept(accel, start + 120_000_000L, start + 170_000_000L, valuesUsable = true)
        assertEquals(0.1, window.counts().getValue(gyro).usableHertz, 0.0001)
        assertNull(window.counts().getValue(gyro).longestIntervalNanos)
        window.accept(gyro, start + 68_000_000L, start + 200_000_000L, valuesUsable = true)

        val counts = window.counts()
        assertEquals(LeanImuCheckCounts(4, 3, 75_000_000L), counts.getValue(accel))
        assertEquals(0.3, counts.getValue(accel).usableHertz, 0.0001)
        assertEquals(LeanImuCheckCounts(2, 2, 28_000_000L), counts.getValue(gyro))
        assertEquals(0.2, counts.getValue(gyro).usableHertz, 0.0001)
    }
}
