package org.iz.navigation.lean

import kotlin.math.*

/** Two-pole Butterworth, forward/backward, with odd reflection and steady-state initialization.
 * Stanglmayr2020 sec2.3 cutoffs: acceleration1Hz, gyro5Hz, actual GNSS speed0.3Hz.
 * Filter order/padding are versioned adaptation choices, not parameters validated by that paper.
 * Coefficients: https://webaudio.github.io/Audio-EQ-Cookbook/audio-eq-cookbook.html (LPF, Q=1/sqrt2).
 */
internal object LeanSignalFilters {
    fun lowPassZeroPhase(input: DoubleArray, sampleHz: Double, cutoffHz: Double,
        checkAllowed: () -> Unit): DoubleArray {
        require(input.size in 12..12_000 && input.all { it.isFinite() })
        require(sampleHz.isFinite() && cutoffHz.isFinite() && cutoffHz > 0 && sampleHz > 2.5 * cutoffHz)
        val padding = minOf(input.lastIndex, maxOf(12, ceil(3 * sampleHz / cutoffHz).toInt()))
        val padded = DoubleArray(input.size + 2 * padding) { index -> when {
            index < padding -> 2 * input.first() - input[padding - index]
            index >= padding + input.size -> 2 * input.last() - input[2 * input.lastIndex - (index - padding)]
            else -> input[index - padding]
        } }
        val w = 2 * PI * cutoffHz / sampleHz
        val alpha = sin(w) / sqrt(2.0)
        val a0 = 1 + alpha
        val b0 = (1 - cos(w)) / (2 * a0)
        val b1 = 2 * b0
        val b2 = b0
        val a1 = -2 * cos(w) / a0
        val a2 = (1 - alpha) / a0
        fun pass(values: DoubleArray) {
            var z1 = (1 - b0) * values.first()
            var z2 = (b2 - a2) * values.first()
            for (i in values.indices) {
                if (i % 256 == 0) checkAllowed()
                val x = values[i]
                val y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                check(y.isFinite())
                values[i] = y
            }
        }
        pass(padded); padded.reverse(); pass(padded); padded.reverse()
        return padded.copyOfRange(padding, padding + input.size)
    }

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
}

internal data class LeanVector(val x: Double, val y: Double, val z: Double) {
    fun dot(other: LeanVector) = x * other.x + y * other.y + z * other.z
    fun cross(other: LeanVector) = LeanVector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    operator fun minus(other: LeanVector) = LeanVector(x - other.x, y - other.y, z - other.z)
    operator fun times(value: Double) = LeanVector(x * value, y * value, z * value)
    fun norm() = sqrt(dot(this))
    fun unit(): LeanVector? = norm().takeIf { it.isFinite() && it > 1e-8 }?.let { this * (1 / it) }
    fun values() = listOf(x, y, z)
}

internal data class LeanFrame(val forward: LeanVector, val right: LeanVector, val down: LeanVector) {
    fun transform(value: LeanVector) = LeanVector(forward.dot(value), right.dot(value), down.dot(value))
    fun rotation() = forward.values() + right.values() + down.values()
}