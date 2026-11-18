package org.iz.navigation.lean

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class HandlebarCalibrationStep(val durationMillis: Long) {
    STRAIGHT(3_000), LEFT(5_000), RIGHT(5_000), CENTER(5_000),
}

/** These bounds reject unusable park samples. They do not certify road accuracy. */
internal fun handlebarCalibration(
    straight: List<LeanImuRecord>, left: List<LeanImuRecord>, right: List<LeanImuRecord>,
    center: List<LeanImuRecord>, epoch: String, createdAt: Long,
): LeanCalibration? {
    val windows = listOf(straight, left, right, center)
    val validated = verifyHandlebarSteps(windows) ?: return null
    val baseline = validated.first()
    val final = validated.last()
    val gravity = mean(baseline.accel + lastSecond(final.accel))
    val bias = mean(baseline.gyro + lastSecond(final.gyro))
    val axis = integrate(validated[1].gyro, bias).unit() ?: return null
    if (!validSteering(validated.drop(1), gravity, bias, axis)) return null
    return LeanCalibration(epoch, createdAt, gravity.values(), bias.values(),
        bodyConfirmed = false, steeringAxis = axis.values())
}

internal fun handlebarStepsAreValid(windows: List<List<LeanImuRecord>>): Boolean = verifyHandlebarSteps(windows) != null

private fun verifyHandlebarSteps(windows: List<List<LeanImuRecord>>): List<CalibrationWindow>? {
    if (windows.size !in 1..4) return null
    val validated = windows.mapIndexed { index, window ->
        calibrationWindow(window, minimumSpan = if (index == 0) 2.4 else 4.4) ?: return null
    }
    val baseline = validated.first()
    val gravity = mean(baseline.accel)
    val bias = mean(baseline.gyro)
    if (!still(baseline.accel, gravity, .15) || gravity.norm() !in 8.8..10.8 ||
        !still(baseline.gyro, bias, .03) || bias.norm() > .1) return null
    val turns = validated.drop(1)
    if (turns.isEmpty()) return validated
    val axis = integrate(turns.first().gyro, bias).unit() ?: return null
    return validated.takeIf { validSteering(turns, gravity, bias, axis) }
}

private fun validSteering(turns: List<CalibrationWindow>, gravity: LeanVector, bias: LeanVector, axis: LeanVector): Boolean {
    val endGravities = turns.map { window ->
        val accel = lastSecond(window.accel)
        val gyro = lastSecond(window.gyro)
        val endGravity = mean(accel)
        if (!still(accel, endGravity, .2) || endGravity.norm() !in 8.8..10.8 ||
            !still(gyro, bias, .045)) return false
        endGravity
    }
    val sweeps = turns.map { window -> steeringSweep(window.gyro, bias, axis) ?: return false }
    if (sweeps.first() !in .25..1.35) return false
    if (sweeps.size >= 2 && (sweeps[1] !in -2.6..-.5 || sweeps[0] + sweeps[1] > -.20)) return false
    if (sweeps.size == 3 && (sweeps[2] !in .25..1.35 || abs(sweeps.sum()) > .12)) return false

    val upright = gravity.unit() ?: return false
    // A rigid steering rotation preserves gravity's component along the steering axis.
    if (abs(axis.dot(upright)) < cos(Math.toRadians(45.0))) return false
    var position = 0.0
    for ((sweep, measuredGravity) in sweeps.zip(endGravities)) {
        position += sweep
        val measuredUp = measuredGravity.unit() ?: return false
        if (abs(axis.dot(measuredGravity) - axis.dot(gravity)) > .35 ||
            measuredUp.dot(rotate(upright, axis, -position)) < cos(Math.toRadians(6.0))) return false
    }
    return turns.size < 3 || (endGravities.last().unit()?.dot(upright) ?: return false) >= cos(Math.toRadians(5.0))
}

private data class CalibrationWindow(val accel: List<LeanImuRecord>, val gyro: List<LeanImuRecord>)

private fun calibrationWindow(records: List<LeanImuRecord>, minimumSpan: Double): CalibrationWindow? {
    fun stream(kind: LeanSensorKind): List<LeanImuRecord>? {
        val samples = records.filter { it.kind == kind }
        if (samples.size < 2 || samples.first().elapsedNanos <= 0 || samples.any {
                it.accuracy <= 0 || !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite()
            }) return null
        val duration = (samples.last().elapsedNanos - samples.first().elapsedNanos) / 1e9
        if (duration < minimumSpan || (samples.size - 1) / duration < 25 ||
            samples.zipWithNext().any { (a, b) -> b.elapsedNanos - a.elapsedNanos !in 1..120_000_000L }) return null
        return samples
    }
    return CalibrationWindow(stream(LeanSensorKind.ACCELEROMETER) ?: return null, stream(LeanSensorKind.GYROSCOPE) ?: return null)
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
