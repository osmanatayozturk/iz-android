package org.iz.navigation.lean

import org.junit.Assert.*
import org.junit.Test

class HandlebarCalibrationTest {
    @Test fun opposedSweepsProduceUnitAxisInTheDirectionOfTheLeftTurn() {
        val result = requireNotNull(estimate(0.42, -0.84, 0.42))
        assertFalse(result.bodyConfirmed)
        assertEquals(0.0, result.steeringAxis[0], 1e-6)
        assertEquals(0.0, result.steeringAxis[1], 1e-6)
        assertEquals(-1.0, result.steeringAxis[2], 1e-6)
        assertEquals(9.80665, result.uprightGravity[2], 1e-6)
        assertEquals(0.02, result.gyroBias[2], 1e-6)
    }

    @Test fun rejectsMissingMotionWrongDirectionAndFailureToReturnToCenter() {
        assertNull(estimate(0.01, -0.02, 0.01))
        assertNull(estimate(0.42, 0.84, 0.42))
        assertNull(estimate(0.42, -0.84, 0.19))
    }

    @Test fun rejectsMovingReferenceUnreliableSensorAndTimestampGaps() {
        val baseline = window(3, 0.0)
        val left = window(5, 0.42)
        val right = window(5, -0.84)
        val center = window(5, 0.42)
        assertNull(handlebarCalibration(window(3, 0.42), left, right, center, "fixture", 1))
        assertNull(handlebarCalibration(baseline, left.map { it.copy(accuracy = 0) }, right, center, "fixture", 1))
        assertNull(handlebarCalibration(baseline, left.filter { it.elapsedNanos !in 2_000_000_000..2_300_000_000 },
            right, center, "fixture", 1))
    }

    @Test fun rejectsOffAxisMotionAndChangedUprightGravity() {
        val baseline = window(3, 0.0)
        val left = window(5, 0.42)
        val right = window(5, -0.84).map {
            if (it.kind == LeanSensorKind.GYROSCOPE) it.copy(x = it.z - 0.02, z = 0.02) else it
        }
        val center = window(5, 0.42)
        assertNull(handlebarCalibration(baseline, left, right, center, "fixture", 1))
        val tilted = center.map {
            if (it.kind == LeanSensorKind.ACCELEROMETER) it.copy(x = 3.0, z = 9.336) else it
        }
        assertNull(handlebarCalibration(baseline, left, window(5, -0.84), tilted, "fixture", 1))
    }

    @Test fun finalStationaryReadingsContributeToGravityAndBias() {
        val final = window(5, 0.42).map {
            if (it.elapsedNanos < 4_000_000_000L) it
            else if (it.kind == LeanSensorKind.GYROSCOPE) it.copy(z = 0.03)
            else it.copy(z = 9.70665)
        }
        val result = requireNotNull(handlebarCalibration(window(3, 0.0), window(5, 0.42),
            window(5, -0.84), final, "fixture", 1))
        assertEquals((150 * 0.02 + 51 * 0.03) / 201, result.gyroBias[2], 1e-6)
        assertEquals((150 * 9.80665 + 51 * 9.70665) / 201, result.uprightGravity[2], 1e-6)
    }

    @Test fun invalidRightMotionLeavesTheAcceptedStraightAndLeftPrefixUsable() {
        val prefix = listOf(window(3, 0.0), window(5, 0.42))
        assertTrue(handlebarStepsAreValid(prefix))
        assertFalse(handlebarStepsAreValid(prefix + listOf(window(5, 0.84))))
        assertTrue(handlebarStepsAreValid(prefix + listOf(window(5, -0.84))))
    }

    private fun estimate(left: Double, right: Double, center: Double) =
        handlebarCalibration(window(3, 0.0), window(5, left), window(5, right), window(5, center), "fixture", 1)

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
