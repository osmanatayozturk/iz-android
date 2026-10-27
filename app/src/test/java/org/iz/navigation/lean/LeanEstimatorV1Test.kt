package org.iz.navigation.lean

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LeanEstimatorV1Test {
    private val g = 9.80665
    private fun session() = LeanInputSession(LeanSessionToken("session", "journey", 1),
        LeanCalibration("body-epoch", 1000, listOf(0.0, 0.0, g), listOf(0.0, 0.0, 0.0)),
        "boot", 1000, 0, 60_000_000_000, "a".repeat(64), emptySet(), 0)

    private fun ride(direction: Int = 1, gnssStep: Int = 50, braking: Boolean = true): Sequence<LeanRecord> = sequence {
        fun speed(t: Double) = if (!braking || t < 15) 25.0 else if (t < 20) 25.0 - 3.0 * (t - 15) else 10.0
        fun angle(t: Double) = direction * when {
            t < 30 -> 0.0
            t < 35 -> (t - 30) / 5 * Math.toRadians(20.0)
            t < 45 -> Math.toRadians(20.0)
            t < 50 -> (50 - t) / 5 * Math.toRadians(20.0)
            else -> 0.0
        }
        for (i in 0..3000) {
            val t = i / 50.0
            val phi = angle(t)
            val lateral = if (abs(phi) > .01) sign(phi) * .15 else 0.0
            val vertical = -g / cos(abs(phi) + if (abs(phi) > .01) .015 else 0.0)
            val longitudinal = if (braking && t in 15.0..19.98) -3.0 else 0.0
            val roll = (angle(t + .01) - angle(max(0.0, t - .01))) / .02
            val yaw = g * sin(phi) / speed(t)
            val time = i * 20_000_000L
            yield(LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), time, longitudinal, -lateral, -vertical, 3))
            yield(LeanImuRecord(LeanSensorKind.GYROSCOPE, i.toLong(), time, roll, 0.0, -yaw, 3))
            if (i % gnssStep == 0) yield(LeanGnssRecord(time, 1000 + time / 1_000_000, 40.0, 29.0,
                3.0, speed(t), null, .3, null, 1_000_000.0, false, "gps"))
        }
    }

    @Test fun absentFrameCannotTurnTiltOrGpsOnlyIntoChassisBank() {
        val estimate = LeanEstimatorV1().estimate(session(), ride(braking = false)) {}
        assertNull(estimate.leftDegrees); assertNull(estimate.rightDegrees)
        assertTrue(estimate.partialReasons.contains("FRAME_UNAVAILABLE"))
    }

    @Test fun sparseGnssCannotBeUpsampledIntoSupportForPointThreeHertz() {
        val estimate = LeanEstimatorV1().estimate(session(), ride(gnssStep = 250)) {}
        assertNull(estimate.leftDegrees); assertNull(estimate.rightDegrees)
        assertTrue(estimate.partialReasons.contains("GNSS_RATE_UNSUITABLE"))
    }

    @Test fun paperChannelsGiveSignedWholeDegreeExperimentalCurves() {
        for (direction in listOf(-1, 1)) {
            val estimate = LeanEstimatorV1().estimate(session(), ride(direction)) {}
            val peak = if (direction < 0) estimate.leftDegrees else estimate.rightDegrees
            assertNotNull(peak)
            assertTrue("peak=$peak", peak!! in 17.0..24.0)
            assertEquals(round(peak), peak, 0.0)
            assertTrue(estimate.validMillis in 1..estimate.observedMillis)
            assertEquals(9, estimate.frameRotation.size)
            assertTrue(estimate.curves.isNotEmpty())
            assertTrue(estimate.curves.all { it.startUtcMillis != null && it.endUtcMillis != null })
        }
    }

    @Test fun rotatedBodyMountProducesTheSameBankWithoutAssumingDeviceForward() {
        fun rotate(record: LeanRecord): LeanRecord = when (record) {
            is LeanImuRecord -> record.copy(x = -record.y, y = record.x)
            is LeanGnssRecord -> record
        }
        val base = LeanEstimatorV1().estimate(session(), ride()) {}
        val rotated = LeanEstimatorV1().estimate(session(), ride().map(::rotate)) {}
        assertNotNull(base.rightDegrees); assertNotNull(rotated.rightDegrees)
        assertEquals(base.rightDegrees!!, rotated.rightDegrees!!, 1.0)
        assertEquals(9, rotated.frameRotation.size)
    }

    @Test fun actualDataGapsAreNeverIncludedInReportedCurves() {
        val gapped = ride().filter { it.elapsedNanos !in 30_000_000_000L..40_000_000_000L }
        val estimate = LeanEstimatorV1().estimate(session(), gapped) {}
        assertTrue(estimate.partialReasons.contains("DATA_GAP"))
        assertTrue(estimate.validMillis < estimate.observedMillis)
        assertTrue(estimate.curves.none { it.startElapsedNanos < 30_000_000_000L && it.endElapsedNanos > 40_000_000_000L })
    }
    @Test fun unknownMountAndMissingQualityNeverProduceNumbers() {
        val mount = session().copy(calibration = session().calibration.copy(bodyConfirmed = false))
        assertNull(LeanEstimatorV1().estimate(mount, ride()) {}.rightDegrees)
        val noAccuracy = ride().map { if (it is LeanGnssRecord) it.copy(speedAccuracyMps = null) else it }
        assertNull(LeanEstimatorV1().estimate(session(), noAccuracy) {}.rightDegrees)
    }

    @Test fun clockMismatchAndNanAreExcludedWithoutInventingZero() {
        val records = ride().map { when (it) {
            is LeanGnssRecord -> it.copy(utcMillis = it.utcMillis + 60_000)
            is LeanImuRecord -> if (it.kind == LeanSensorKind.ACCELEROMETER) it.copy(x = Double.NaN) else it
        } }
        val estimate = LeanEstimatorV1().estimate(session(), records) {}
        assertNull(estimate.leftDegrees); assertNull(estimate.rightDegrees)
        assertEquals(0L, estimate.validMillis)
    }

    @Test fun monotonicClockAndInputLimitsBoundAnInfiniteMalformedStream() {
        var count = 0
        val records = generateSequence { count++; LeanImuRecord(LeanSensorKind.ACCELEROMETER, 0, 0, 0.0, 0.0, g, 3) }
        val result = LeanEstimatorV1().estimate(session(), records) {}
        assertTrue(count <= 12_001)
        assertTrue(result.partialReasons.contains("WINDOW_RECORD_LIMIT"))
        assertNull(result.rightDegrees)
    }

    @Test fun interleavedPerSensorCountersDoNotCreateFalseSequenceGaps() {
        val counters = ride().filterIsInstance<LeanImuRecord>().take(4).map { it.sequence }.toList()
        assertEquals(listOf(0L, 0L, 1L, 1L), counters)
        val result = LeanEstimatorV1().estimate(session(), ride()) {}
        assertFalse(result.partialReasons.contains("IMU_SEQUENCE_GAP"))
    }

    @Test fun skippedPerSensorCounterIsStillDetectedWithOtherSensorIntact() {
        val input = ride().filterNot { it is LeanImuRecord &&
            it.kind == LeanSensorKind.ACCELEROMETER && it.sequence == 800L }
        val result = LeanEstimatorV1().estimate(session(), input) {}
        assertTrue(result.partialReasons.contains("IMU_SEQUENCE_GAP"))
    }
    @Test fun processingCheckPropagatesInsteadOfPublishingPartialAfterCancellation() {
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            LeanEstimatorV1().estimate(session(), ride()) { if (++checks == 3) error("paused") }
        }
    }

    @Test fun zeroPhaseFilterPreservesDcAndDoesNotShiftAnInteriorPulse() {
        val dc = DoubleArray(1000) { 9.0 }
        val filtered = LeanSignalFilters.lowPassZeroPhase(dc, 50.0, 1.0) {}
        assertTrue(filtered.all { abs(it - 9.0) < 1e-8 })
        val pulse = DoubleArray(1001).also { it[500] = 1.0 }
        val result = LeanSignalFilters.lowPassZeroPhase(pulse, 50.0, 1.0) {}
        assertEquals(500, result.indices.maxByOrNull { result[it] })
        for (i in 1..100) assertEquals(result[500 - i], result[500 + i], 1e-8)
    }
}