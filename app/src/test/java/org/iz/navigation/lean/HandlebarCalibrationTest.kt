package org.iz.navigation.lean

import org.junit.Assert.*
import org.junit.Test

class HandlebarCalibrationTest {
    @Test fun opposedSweepsProduceUnitAxisInTheDirectionOfTheLeftTurn() {
        val result = (estimate(0.42, -0.84, 0.42) as CalibrationResult.Ready).profile
        assertFalse(result.bodyConfirmed)
        assertEquals(0.0, result.steeringAxis[0], 1e-6)
        assertEquals(0.0, result.steeringAxis[1], 1e-6)
        assertEquals(-1.0, result.steeringAxis[2], 1e-6)
        assertEquals(9.80665, result.uprightGravity[2], 1e-6)
        assertEquals(0.02, result.gyroBias[2], 1e-6)
    }

    @Test fun rejectsMissingMotionWrongDirectionAndFailureToReturnToCenter() {
        assertFailure(CalibrationFailure.TURN_RANGE, estimate(0.01, -0.02, 0.01))
        assertFailure(CalibrationFailure.TURN_DIRECTION, estimate(0.42, 0.84, 0.42))
        assertFailure(CalibrationFailure.CENTER, estimate(0.42, -0.84, 0.19))
    }

    @Test fun rejectsMovingReferenceUnreliableSensorAndTimestampGaps() {
        val baseline = window(3, 0.0)
        val left = window(5, 0.42)
        val right = window(5, -0.84)
        val center = window(5, 0.42)
        assertFailure(CalibrationFailure.MOVEMENT, checkSteps(window(3, 0.42), left, right, center))
        assertFailure(CalibrationFailure.UNRELIABLE_SAMPLES, checkSteps(baseline, left.map { it.copy(accuracy = 0) }, right, center))
        assertFailure(CalibrationFailure.SAMPLE_GAP, checkSteps(baseline, left.filter { it.elapsedNanos !in 2_000_000_000..2_300_000_000 },
            right, center))
    }

    @Test fun rejectsOffAxisMotionAndChangedUprightGravity() {
        val baseline = window(3, 0.0)
        val left = window(5, 0.42)
        val right = window(5, -0.84).map {
            if (it.kind == LeanSensorKind.GYROSCOPE) it.copy(x = it.z - 0.02, z = 0.02) else it
        }
        val center = window(5, 0.42)
        assertFailure(CalibrationFailure.TURN_AXIS, checkSteps(baseline, left, right, center))
        val tilted = center.map {
            if (it.kind == LeanSensorKind.ACCELEROMETER) it.copy(x = 3.0, z = 9.336) else it
        }
        assertFailure(CalibrationFailure.MOUNT_SHIFT, checkSteps(baseline, left, window(5, -0.84), tilted))
    }

    @Test fun finalStationaryReadingsContributeToGravityAndBias() {
        val final = window(5, 0.42).map {
            if (it.elapsedNanos < 4_000_000_000L) it
            else if (it.kind == LeanSensorKind.GYROSCOPE) it.copy(z = 0.03)
            else it.copy(z = 9.70665)
        }
        val result = (checkSteps(window(3, 0.0), window(5, 0.42), window(5, -0.84), final) as CalibrationResult.Ready).profile
        assertEquals((150 * 0.02 + 51 * 0.03) / 201, result.gyroBias[2], 1e-6)
        assertEquals((150 * 9.80665 + 51 * 9.70665) / 201, result.uprightGravity[2], 1e-6)
    }

    @Test fun invalidRightMotionLeavesTheAcceptedStraightAndLeftPrefixUsable() {
        val prefix = listOf(window(3, 0.0), window(5, 0.42))
        assertEquals(StepAccepted, evaluateHandlebarSteps(prefix, "epoch", 1))
        assertFailure(CalibrationFailure.TURN_DIRECTION, evaluateHandlebarSteps(prefix + listOf(window(5, 0.84)), "epoch", 1))
        assertEquals(StepAccepted, evaluateHandlebarSteps(prefix + listOf(window(5, -0.84)), "epoch", 1))
    }

    private fun estimate(left: Double, right: Double, center: Double) =
        checkSteps(window(3, 0.0), window(5, left), window(5, right), window(5, center))

    private fun checkSteps(vararg windows: List<LeanImuRecord>): CalibrationProgress =
        evaluateHandlebarSteps(windows.toList(), "fixture", 1)

    private fun assertFailure(expected: CalibrationFailure, actual: CalibrationProgress) =
        assertEquals(CalibrationResult.Failed(expected), actual)

    @Test fun denseFiveSecondWindowFitsAndOverflowIsExplicit() {
        val window = HandlebarSampleWindow()
        val sample = LeanImuRecord(LeanSensorKind.GYROSCOPE, 0, 1, 0.0, 0.0, 0.0, 3)
        window.begin(1)
        repeat(2_000) { window.add(sample) }
        assertEquals(2_000, window.finish()!!.size)
        window.begin(1)
        repeat(3_000) { window.add(sample) }
        assertNull(window.finish())
        window.begin(5)
        window.add(sample.copy(elapsedNanos = 4))
        window.add(sample.copy(elapsedNanos = 5))
        assertEquals(1, window.finish()!!.size)
    }

    private fun window(seconds: Int, signedLeftRadians: Double): List<LeanImuRecord> =
        (1..seconds * 50).flatMap { index ->
            val timestamp = index * 20_000_000L
            val motion = if (index in 51..150) -signedLeftRadians / 2 else 0.0
            listOf(
                LeanImuRecord(LeanSensorKind.ACCELEROMETER, index.toLong(), timestamp, 0.0, 0.0, 9.80665, 3),
                LeanImuRecord(LeanSensorKind.GYROSCOPE, index.toLong(), timestamp, 0.0, 0.0, 0.02 + motion, 3),
            )
        }
}
