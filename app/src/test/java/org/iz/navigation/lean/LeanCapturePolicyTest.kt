package org.iz.navigation.lean

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class LeanCapturePolicyTest {
    private val calibration = LeanCalibration("epoch", 1, listOf(0.0, 0.0, 9.81), listOf(0.0, 0.0, 0.0))
    private val settings = LeanCaptureSettings(true, LeanMount.BODY, calibration)
    private val moto = Journey(id = "moto", transport = Transport.MOTORCYCLE, startedAt = 1)

    @Test fun capturesOnlyOptedInConfirmedBodyMountedMotorcycle() {
        assertTrue(leanCaptureEligible(moto, settings))
        assertFalse(leanCaptureEligible(null, settings))
        assertFalse(leanCaptureEligible(moto.copy(transport = Transport.CAR), settings))
        assertFalse(leanCaptureEligible(moto.copy(status = JourneyStatus.TEMPORARY), settings))
        assertFalse(leanCaptureEligible(moto.copy(endedAt = 10), settings))
        assertFalse(leanCaptureEligible(moto, settings.copy(enabled = false)))
        assertFalse(leanCaptureEligible(moto, settings.copy(mount = LeanMount.HANDLEBAR)))
        assertFalse(leanCaptureEligible(moto, settings.copy(calibration = null)))
        assertFalse(leanCaptureEligible(moto, settings.copy(calibration = calibration.copy(bodyConfirmed = false))))
    }

    @Test fun interleavedSensorsKeepIndependentSequencesAcrossBatchesAndDroppedEvents() {
        val sequences = LeanSensorSequences()
        assertEquals(0L, sequences.next(LeanSensorKind.ACCELEROMETER))
        assertEquals(0L, sequences.next(LeanSensorKind.GYROSCOPE))
        assertEquals(1L, sequences.next(LeanSensorKind.ACCELEROMETER)) // Observed but dropped from queue.
        assertEquals(1L, sequences.next(LeanSensorKind.GYROSCOPE))
        assertEquals(2L, sequences.next(LeanSensorKind.ACCELEROMETER))
        assertEquals(2L, sequences.next(LeanSensorKind.GYROSCOPE))
        assertEquals(0L, LeanSensorSequences().next(LeanSensorKind.ACCELEROMETER))
    }
    @Test fun invalidReferenceCannotAuthorizeRecording() {
        assertFalse(leanCaptureEligible(moto, settings.copy(calibration = calibration.copy(epoch = ""))))
        assertFalse(leanCaptureEligible(moto, settings.copy(calibration = calibration.copy(uprightGravity = listOf(0.0, 0.0, 0.0)))))
        assertFalse(leanCaptureEligible(moto, settings.copy(calibration = calibration.copy(gyroBias = listOf(Double.NaN, 0.0, 0.0)))))
    }

    @Test fun shortOrMovingReferenceIsRejectedWithoutInventingFrame() {
        val short = LeanCalibrationAccumulator()
        short.add(LeanImuRecord(LeanSensorKind.ACCELEROMETER, 0, 1, 0.0, 0.0, 9.81, 3))
        assertEquals(CalibrationResult.Failed(CalibrationFailure.INSUFFICIENT_SAMPLES), short.finish("epoch", 1))
        val still = LeanCalibrationAccumulator()
        val moving = LeanCalibrationAccumulator()
        repeat(120) { i ->
            val time = 1L + i * 20_000_000L
            val acc = LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), time, 0.0, 0.0, 9.81, 3)
            val gyro = LeanImuRecord(LeanSensorKind.GYROSCOPE, i.toLong(), time, .01, -.01, 0.0, 3)
            still.add(acc); still.add(gyro)
            moving.add(acc); moving.add(gyro.copy(x = if (i % 2 == 0) 1.0 else -1.0))
        }
        val result = (still.finish("epoch", 1) as CalibrationResult.Ready).profile
        assertEquals(.01, result.gyroBias[0], .00001)
        assertEquals(CalibrationResult.Failed(CalibrationFailure.MOVEMENT), moving.finish("epoch", 1))
    }

    @Test fun calibrationSamplesAreBoundedAndBadAccuracyDoesNotBecomeReference() {
        val reference = LeanCalibrationAccumulator()
        repeat(400) { i ->
            reference.add(LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), i * 20_000_000L + 1, 0.0, 0.0, 9.81, 0))
            reference.add(LeanImuRecord(LeanSensorKind.GYROSCOPE, i.toLong(), i * 20_000_000L + 1, 0.0, 0.0, 0.0, 0))
        }
        assertEquals(CalibrationResult.Failed(CalibrationFailure.UNRELIABLE_SAMPLES), reference.finish("epoch", 1))
    }

    @Test fun fastStationarySensorsUseWholeWindowAndStillRejectMovementLateInCapture() {
        val fast = LeanCalibrationAccumulator()
        val moving = LeanCalibrationAccumulator()
        repeat(600) { i ->
            val acc = LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), 1L + i * 5_000_000L, 0.0, 0.0, 9.81, 3)
            val gyro = acc.copy(kind = LeanSensorKind.GYROSCOPE, z = 0.0)
            fast.add(acc); fast.add(gyro)
            moving.add(acc); moving.add(if (i > 400) gyro.copy(x = 1.0) else gyro)
        }
        assertTrue(fast.finish("epoch", 1) is CalibrationResult.Ready)
        assertEquals(CalibrationResult.Failed(CalibrationFailure.MOVEMENT), moving.finish("epoch", 1))
    }

    @Test fun reportsWhichStationaryCheckFailed() {
        fun result(spacing: Long = 20_000_000L, gravity: Double = 9.81, rotation: Double = 0.0): CalibrationResult {
            val reference = LeanCalibrationAccumulator()
            repeat(120) { i ->
                val record = LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), 1L + i * spacing, 0.0, 0.0, gravity, 3)
                reference.add(record)
                reference.add(record.copy(kind = LeanSensorKind.GYROSCOPE, z = rotation))
            }
            return reference.finish("epoch", 1)
        }
        assertEquals(CalibrationResult.Failed(CalibrationFailure.SHORT_WINDOW), result(spacing = 5_000_000L))
        assertEquals(CalibrationResult.Failed(CalibrationFailure.GRAVITY), result(gravity = 7.0))
        assertEquals(CalibrationResult.Failed(CalibrationFailure.GYRO_DRIFT), result(rotation = .2))
    }
}
