package org.iz.navigation.lean

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class HandlebarCalibrationStep(val durationMillis: Long) {
    STRAIGHT(3_000), LEFT(5_000), RIGHT(5_000), CENTER(5_000),
}

/** These bounds reject unusable park samples. They do not certify road accuracy. */
internal fun evaluateHandlebarSteps(
    windows: List<List<LeanImuRecord>>, epoch: String, createdAt: Long,
): CalibrationProgress {
    require(windows.size in 1..HandlebarCalibrationStep.entries.size)
    val validated = windows.mapIndexed { index, samples ->
        val window = CalibrationWindow(samples.filter { it.kind == LeanSensorKind.ACCELEROMETER },
            samples.filter { it.kind == LeanSensorKind.GYROSCOPE })
        val minimumSpan = if (index == 0) 2.4 else 4.4
        (sensorWindowFailure(window.accel, minimumSpan) ?: sensorWindowFailure(window.gyro, minimumSpan))?.let {
            return CalibrationResult.Failed(it)
        }
        window
    }
    val baseline = validated.first()
    val gravity = mean(baseline.accel)
    val bias = mean(baseline.gyro)
    if (gravity.norm() !in 8.8..10.8) return CalibrationResult.Failed(CalibrationFailure.GRAVITY)
    if (!still(baseline.accel, gravity, .15) || !still(baseline.gyro, bias, .03))
        return CalibrationResult.Failed(CalibrationFailure.MOVEMENT)
    if (bias.norm() > .1) return CalibrationResult.Failed(CalibrationFailure.GYRO_DRIFT)
    val turns = validated.drop(1)
    if (turns.isEmpty()) return StepAccepted
    val axis = integrate(turns.first().gyro, bias).unit() ?: return CalibrationResult.Failed(CalibrationFailure.TURN_RANGE)
    steeringFailure(turns, gravity, bias, axis)?.let { return CalibrationResult.Failed(it) }
    if (windows.size < HandlebarCalibrationStep.entries.size) return StepAccepted

    // Recheck using the combined initial and final stationary reference, as used by the estimator.
    val finalGravity = mean(baseline.accel + lastSecond(turns.last().accel))
    val finalBias = mean(baseline.gyro + lastSecond(turns.last().gyro))
    val finalAxis = integrate(turns.first().gyro, finalBias).unit() ?: return CalibrationResult.Failed(CalibrationFailure.TURN_RANGE)
    steeringFailure(turns, finalGravity, finalBias, finalAxis)?.let { return CalibrationResult.Failed(it) }
    return CalibrationResult.Ready(LeanCalibration(epoch, createdAt, finalGravity.values(), finalBias.values(),
        bodyConfirmed = false, steeringAxis = finalAxis.values()))
}

private fun steeringFailure(turns: List<CalibrationWindow>, gravity: LeanVector, bias: LeanVector,
    axis: LeanVector): CalibrationFailure? {
    val endGravities = turns.map { window ->
        val accel = lastSecond(window.accel)
        val gyro = lastSecond(window.gyro)
        val endGravity = mean(accel)
        if (endGravity.norm() !in 8.8..10.8) return CalibrationFailure.GRAVITY
        if (!still(accel, endGravity, .2) || !still(gyro, bias, .045)) return CalibrationFailure.MOVEMENT
        endGravity
    }
    val sweeps = turns.map { window -> steeringSweep(window.gyro, bias, axis) ?: return CalibrationFailure.TURN_AXIS }
    if (sweeps.first() !in .25..1.35) return CalibrationFailure.TURN_RANGE
    if (sweeps.size >= 2) {
        if (sweeps[1] >= 0 || sweeps[0] + sweeps[1] > -.20) return CalibrationFailure.TURN_DIRECTION
        if (sweeps[1] !in -2.6..-.5) return CalibrationFailure.TURN_RANGE
    }
    if (sweeps.size == 3 && (sweeps[2] !in .25..1.35 || abs(sweeps.sum()) > .12)) return CalibrationFailure.CENTER

    val upright = gravity.unit() ?: return CalibrationFailure.GRAVITY
    // A rigid steering rotation preserves gravity's component along the steering axis.
    if (abs(axis.dot(upright)) < cos(Math.toRadians(45.0))) return CalibrationFailure.TURN_AXIS
    var position = 0.0
    for ((sweep, measuredGravity) in sweeps.zip(endGravities)) {
        position += sweep
        val measuredUp = measuredGravity.unit() ?: return CalibrationFailure.GRAVITY
        if (abs(axis.dot(measuredGravity) - axis.dot(gravity)) > .35 ||
            measuredUp.dot(rotate(upright, axis, -position)) < cos(Math.toRadians(6.0))) return CalibrationFailure.MOUNT_SHIFT
    }
    if (turns.size == 3 && (endGravities.last().unit()?.dot(upright) ?: 0.0) < cos(Math.toRadians(5.0)))
        return CalibrationFailure.CENTER
    return null
}

private data class CalibrationWindow(val accel: List<LeanImuRecord>, val gyro: List<LeanImuRecord>)

private fun sensorWindowFailure(samples: List<LeanImuRecord>, minimumSpan: Double): CalibrationFailure? {
    if (samples.size < 2) return CalibrationFailure.INSUFFICIENT_SAMPLES
    if (samples.any { it.accuracy <= 0 }) return CalibrationFailure.UNRELIABLE_SAMPLES
    if (samples.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return CalibrationFailure.INVALID_SAMPLE
    if (samples.first().elapsedNanos <= 0 || samples.zipWithNext().any { (a, b) ->
            b.elapsedNanos - a.elapsedNanos !in 1..120_000_000L }) return CalibrationFailure.SAMPLE_GAP
    val duration = (samples.last().elapsedNanos - samples.first().elapsedNanos) / 1e9
    return when {
        duration < minimumSpan -> CalibrationFailure.SHORT_WINDOW
        (samples.size - 1) / duration < 25 -> CalibrationFailure.INSUFFICIENT_SAMPLES
        else -> null
    }
}

private fun LeanImuRecord.vector() = LeanVector(x, y, z)
private fun mean(samples: List<LeanImuRecord>) =
    LeanVector(samples.map { it.x }.average(), samples.map { it.y }.average(), samples.map { it.z }.average())

private fun lastSecond(samples: List<LeanImuRecord>) =
    samples.takeLastWhile { samples.last().elapsedNanos - it.elapsedNanos <= 1_000_000_000L }

private fun still(samples: List<LeanImuRecord>, expected: LeanVector, tolerance: Double) =
    sqrt(samples.map { (it.vector() - expected).let { delta -> delta.dot(delta) } }.average()) <= tolerance

private fun integrate(samples: List<LeanImuRecord>, bias: LeanVector): LeanVector {
    var total = LeanVector(0.0, 0.0, 0.0)
    for ((first, second) in samples.zipWithNext()) {
        val delta = (first.vector() - bias + (second.vector() - bias)) *
            ((second.elapsedNanos - first.elapsedNanos) / 2e9)
        total += delta
    }
    return total
}

private operator fun LeanVector.plus(other: LeanVector) = LeanVector(x + other.x, y + other.y, z + other.z)

private fun rotate(vector: LeanVector, axis: LeanVector, radians: Double): LeanVector =
    vector * cos(radians) + axis.cross(vector) * sin(radians) + axis * (axis.dot(vector) * (1 - cos(radians)))

private fun steeringSweep(samples: List<LeanImuRecord>, bias: LeanVector, axis: LeanVector): Double? {
    var along = 0.0
    var travel = 0.0
    var offAxis = 0.0
    for ((first, second) in samples.zipWithNext()) {
        val rate = (first.vector() - bias + (second.vector() - bias)) * .5
        val parallel = rate.dot(axis)
        val dt = (second.elapsedNanos - first.elapsedNanos) / 1e9
        along += parallel * dt
        travel += abs(parallel) * dt
        offAxis += (rate - axis * parallel).norm() * dt
    }
    if (travel - abs(along) > .12 || offAxis > .12 + .22 * abs(along)) return null
    return along
}
