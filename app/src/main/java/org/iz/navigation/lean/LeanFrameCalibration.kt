package org.iz.navigation.lean

import kotlin.math.*

/** Experimental sec2.2 adaptation: natural straight/steady gravity and natural straight braking.
 * Upright setup alone leaves yaw undetermined; never guess a device axis as motorcycle-forward.
 * https://www.ifz.de/IMC/Session8/IMC2020-Session8-IFZ_2020_TUD_Paper_Smartphones_MotorcycleDynamics.pdf
 * Rows map Android raw device axes to a right-handed forward/right/down body frame.
 */
internal object LeanFrameCalibration {
    const val GRAVITY = 9.80665
    private val threeDegrees = Math.toRadians(3.0)
    private fun yawThreshold(speed: Double) = tan(threeDegrees) * GRAVITY / speed

    fun derive(accel: Array<LeanVector>, gyro: Array<LeanVector>, speed: DoubleArray,
        stepSeconds: Double, expectedUpright: LeanVector, checkAllowed: () -> Unit): LeanFrame? {
        if (accel.size != gyro.size || speed.size != accel.size || speed.size < 100) return null
        val gravity = ArrayList<LeanVector>()
        for (i in 1 until speed.lastIndex) {
            if (i % 256 == 0) checkAllowed()
            val acceleration = (speed[i + 1] - speed[i - 1]) / (2 * stepSeconds)
            val threshold = yawThreshold(speed[i].coerceAtLeast(1.0)) * (sin(threeDegrees) + cos(threeDegrees))
            val rates = gyro[i]
            if (speed[i] >= 30 / 3.6 && abs(acceleration) <= .3 &&
                abs(rates.x) + abs(rates.y) + abs(rates.z) <= threshold &&
                accel[i].norm() in 9.0..10.6) gravity.add(accel[i])
        }
        if (gravity.size * stepSeconds < 2.0) return null
        val up = componentMedian(gravity).unit() ?: return null
        val setup = expectedUpright.unit() ?: return null
        if (up.dot(setup) < cos(Math.toRadians(10.0))) return null
        val down = up * -1.0
        val forwards = ArrayList<LeanVector>()
        for (i in 1 until speed.lastIndex) {
            if (i % 256 == 0) checkAllowed()
            val acceleration = (speed[i + 1] - speed[i - 1]) / (2 * stepSeconds)
            val horizontal = accel[i] - up * accel[i].dot(up)
            // Paper's natural-braking thresholds; no user-requested maneuver.
            if (speed[i] >= 30 / 3.6 && acceleration <= -2.5 && horizontal.norm() >= 2.5 &&
                abs(gyro[i].dot(down)) <= cos(threeDegrees) * yawThreshold(speed[i])) {
                horizontal.unit()?.let { forwards.add(it * -1.0) }
            }
        }
        if (forwards.size * stepSeconds < .4) return null
        val forward = componentMedian(forwards).let { it - up * it.dot(up) }.unit() ?: return null
        if (forwards.count { it.dot(forward) >= cos(Math.toRadians(15.0)) } < forwards.size * .9) return null
        val right = down.cross(forward).unit() ?: return null
        val correctedForward = right.cross(down).unit() ?: return null
        return LeanFrame(correctedForward, right, down)
    }

    private fun componentMedian(values: List<LeanVector>) = LeanVector(
        LeanSignalFilters.median(values.map { it.x }), LeanSignalFilters.median(values.map { it.y }),
        LeanSignalFilters.median(values.map { it.z }))
}