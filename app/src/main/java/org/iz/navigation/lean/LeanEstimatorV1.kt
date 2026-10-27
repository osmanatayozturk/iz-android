package org.iz.navigation.lean

import kotlin.math.*

/** Experimental post-trip adaptation of Stanglmayr2020 secs2.2–2.4, not a validated instrument.
 * https://www.ifz.de/IMC/Session8/IMC2020-Session8-IFZ_2020_TUD_Paper_Smartphones_MotorcycleDynamics.pdf
 * Right-positive bank about the vertical world reference, device→body forward/right/down.
 * Actual speed timing is checked BEFORE filtering at0.3Hz; interpolation never repairs lost bandwidth.
 * Bounded60s windows/10s overlap; edge5s on each side is unavailable. No full-trip materialization.
 */
class LeanEstimatorV1 : LeanEstimator {
    override fun estimate(session: LeanInputSession, records: Sequence<LeanRecord>, checkAllowed: () -> Unit): LeanEstimate {
        val result = Accumulator(session, checkAllowed)
        if (!result.validSession()) return result.finish()
        val window = ArrayList<LeanRecord>()
        var first = Long.MIN_VALUE
        var latest = Long.MIN_VALUE
        var attempts = 0
        var total = 0
        for (record in records) {
            if (total++ % 128 == 0) checkAllowed()
            if (total > MAX_TOTAL_RECORDS) { result.reasons.add("INPUT_RECORD_LIMIT"); break }
            if (++attempts > MAX_WINDOW_RECORDS) { result.reasons.add("WINDOW_RECORD_LIMIT"); break }
            val time = record.elapsedNanos
            if (time < session.startedAtElapsedNanos || time > session.endedAtElapsedNanos!!) {
                result.reasons.add("OUTSIDE_SESSION"); continue
            }
            if (first == Long.MIN_VALUE) first = time
            latest = maxOf(latest, time)
            if (time < latest - WINDOW_NANOS) { result.reasons.add("LATE_RECORD"); continue }
            window.add(record)
            if (latest - first >= WINDOW_NANOS) {
                result.process(window)
                val retainFrom = latest - OVERLAP_NANOS
                window.removeAll { it.elapsedNanos < retainFrom }
                first = window.minOfOrNull { it.elapsedNanos } ?: latest
                attempts = window.size
            }
        }
        checkAllowed()
        if (window.isNotEmpty()) result.process(window)
        return result.finish()
    }

    private class Accumulator(val session: LeanInputSession, val check: () -> Unit) {
        val reasons = linkedSetOf<String>().apply { addAll(session.partialReasons.asSequence().filter { it.matches(Regex("[A-Z0-9_]{1,64}")) }.take(64).toList()) }
        private var frame: LeanFrame? = null
        private var left: Double? = null
        private var right: Double? = null
        private var validNanos = 0L
        private var lastEmitted = Long.MIN_VALUE
        private val curves = ArrayList<LeanCurveResult>()
        private val origin = session.startedAtElapsedNanos
        private fun seconds(time: Long) = (time - origin) / 1e9

        fun validSession(): Boolean {
            val c = session.calibration
            val valid = session.token.id.isNotBlank() && session.token.journeyId.isNotBlank() &&
                session.token.generation >= 0 && session.bootId.isNotBlank() && origin >= 0 &&
                session.startedAtUtc > 0 && session.endedAtElapsedNanos?.let { it >= origin && it - origin <= DAY_NANOS } == true &&
                session.inputHash.matches(Regex("[A-Fa-f0-9]{64}")) &&
                c.bodyConfirmed && c.epoch.isNotBlank() && c.createdAt > 0 &&
                c.uprightGravity.size == 3 && c.gyroBias.size == 3 &&
                c.uprightGravity.all { it.isFinite() } && c.gyroBias.all { it.isFinite() && abs(it) <= .5 } &&
                LeanVector(c.uprightGravity[0], c.uprightGravity[1], c.uprightGravity[2]).norm() in 8.5..11.0
            if (!valid) reasons.add("INVALID_SESSION_OR_CALIBRATION")
            if (session.droppedSamples > 0) reasons.add("DROPPED_INPUT")
            return valid
        }

        fun process(records: List<LeanRecord>) {
            check()
            val cuts = ArrayList<Pair<Double, Double>>()
            fun bad(time: Long, reason: String) { reasons.add(reason); cuts.add(seconds(time) - .5 to seconds(time) + .5) }
            fun imu(kind: LeanSensorKind): List<LeanImuRecord> {
                val rows = records.filterIsInstance<LeanImuRecord>().filter { it.kind == kind }.sortedBy { it.elapsedNanos }
                var previous: LeanImuRecord? = null
                return rows.filter { value ->
                    val old = previous
                    previous = value
                    val valid = value.sequence >= 0 && value.accuracy in 1..3 &&
                        listOf(value.x, value.y, value.z).all { it.isFinite() && abs(it) <= if (kind == LeanSensorKind.ACCELEROMETER) 100.0 else 30.0 } &&
                        (old == null || value.elapsedNanos > old.elapsedNanos && value.sequence > old.sequence)
                    if (!valid) bad(value.elapsedNanos, "IMU_QUALITY")
                    else if (old != null && value.sequence != old.sequence + 1) bad(value.elapsedNanos, "IMU_SEQUENCE_GAP")
                    valid
                }
            }
            val accelerometer = imu(LeanSensorKind.ACCELEROMETER)
            val gyroscope = imu(LeanSensorKind.GYROSCOPE)
            val gnss = records.filterIsInstance<LeanGnssRecord>().sortedBy { it.elapsedNanos }.filter { value ->
                val valid = !value.mock && value.latitude.isFinite() && value.latitude in -90.0..90.0 &&
                    value.longitude.isFinite() && value.longitude in -180.0..180.0 &&
                    value.speedMps?.let { it.isFinite() && it in 0.0..100.0 } == true &&
                    value.accuracyMeters?.let { it.isFinite() && it in 0.0..20.0 } == true &&
                    value.speedAccuracyMps?.let { it.isFinite() && it in 0.0..1.5 } == true &&
                    value.elapsedUncertaintyNanos?.let { it.isFinite() && it in 0.0..100_000_000.0 } == true
                val expectedUtc = utc(value.elapsedNanos)
                val clockValid = expectedUtc != null && value.utcMillis > 0 && abs(value.utcMillis.toDouble() - expectedUtc) <= 2000
                if (!valid || !clockValid) bad(value.elapsedNanos, if (!clockValid) "CLOCK_DISCONTINUITY" else "GNSS_QUALITY")
                valid && clockValid
            }.distinctBy { it.elapsedNanos }
            if (accelerometer.size < 2 || gyroscope.size < 2) { reasons.add("IMU_UNAVAILABLE"); return }
            if (gnss.size < 12) { reasons.add("GNSS_RATE_UNSUITABLE"); return }
            val aTimes = accelerometer.map { seconds(it.elapsedNanos) }
            val wTimes = gyroscope.map { seconds(it.elapsedNanos) }
            val gTimes = gnss.map { seconds(it.elapsedNanos) }
            fun rate(times: List<Double>, maximumMedian: Double, reason: String): Boolean {
                val steps = times.zipWithNext { a, b -> b - a }
                val median = LeanSignalFilters.median(steps)
                val valid = median > 0 && median <= maximumMedian &&
                    steps.count { it > 1.8 * median } <= maxOf(1, steps.size / 10)
                if (!valid) reasons.add(reason)
                return valid
            }
            if (!rate(aTimes, .04, "IMU_RATE_UNSUITABLE") || !rate(wTimes, .04, "IMU_RATE_UNSUITABLE") ||
                !rate(gTimes, 1.25, "GNSS_RATE_UNSUITABLE")) return
            fun gaps(times: List<Double>, maxGap: Double) {
                times.zipWithNext().forEach { (a, b) -> if (b - a > maxGap) {
                    cuts.add(a to b); reasons.add("DATA_GAP")
                } }
            }
            gaps(aTimes, .08); gaps(wTimes, .08); gaps(gTimes, 1.5)
            val start = maxOf(aTimes.first(), wTimes.first(), gTimes.first())
            val end = minOf(aTimes.last(), wTimes.last(), gTimes.last())
            var cursor = start
            fun section(from: Double, until: Double) {
                if (until - from < 20.0) { reasons.add("SECTION_TOO_SHORT"); return }
                val a = accelerometer.filter { seconds(it.elapsedNanos) in from..until }
                val w = gyroscope.filter { seconds(it.elapsedNanos) in from..until }
                val gps = gnss.filter { seconds(it.elapsedNanos) in from..until }
                if (a.size < 2 || w.size < 2 || gps.size < 12) return
                processSection(a, w, gps)
            }
            for ((lo, hi) in cuts.sortedBy { it.first }) {
                if (hi <= cursor || lo >= end) continue
                if (lo > cursor) section(cursor, minOf(lo, end))
                cursor = maxOf(cursor, hi)
            }
            if (cursor < end) section(cursor, end)
        }

        private fun processSection(a: List<LeanImuRecord>, w: List<LeanImuRecord>, gps: List<LeanGnssRecord>) {
            val aTimes = a.map { seconds(it.elapsedNanos) }
            val wTimes = w.map { seconds(it.elapsedNanos) }
            val gpsTimes = gps.map { seconds(it.elapsedNanos) }
            val start = maxOf(aTimes.first(), wTimes.first(), gpsTimes.first())
            val end = minOf(aTimes.last(), wTimes.last(), gpsTimes.last())
            val count = floor((end - start) * SAMPLE_HZ).toInt() + 1
            if (count !in 1000..4000) { reasons.add("SECTION_TOO_SHORT"); return }
            fun bracket(times: List<Double>, time: Double): Int {
                val found = times.binarySearch(time)
                return if (found >= 0) found.coerceAtMost(times.lastIndex - 1) else (-found - 2).coerceIn(0, times.lastIndex - 1)
            }
            fun interpolate(times: List<Double>, time: Double, value: (Int) -> Double): Double {
                val index = bracket(times, time)
                val ratio = ((time - times[index]) / (times[index + 1] - times[index])).coerceIn(0.0, 1.0)
                return value(index) + ratio * (value(index + 1) - value(index))
            }
            fun filtered(rows: List<LeanImuRecord>, times: List<Double>, hz: Double, bias: List<Double>): Array<LeanVector> {
                fun axis(axis: Int): DoubleArray {
                    val raw = DoubleArray(count) { i -> interpolate(times, start + i / SAMPLE_HZ) { j ->
                        (when (axis) { 0 -> rows[j].x; 1 -> rows[j].y; else -> rows[j].z }) - bias[axis]
                    } }
                    return LeanSignalFilters.lowPassZeroPhase(raw, SAMPLE_HZ, hz, check)
                }
                val x = axis(0); val y = axis(1); val z = axis(2)
                return Array(count) { LeanVector(x[it], y[it], z[it]) }
            }
            val accel = filtered(a, aTimes, 1.0, listOf(0.0, 0.0, 0.0))
            val gyro = filtered(w, wTimes, 5.0, session.calibration.gyroBias)
            // The speed filter uses its ACTUAL source rate, not a50Hz upsampled fiction.
            val gpsStep = LeanSignalFilters.median(gpsTimes.zipWithNext { x, y -> y - x })
            if (gpsStep <= 0 || 1 / gpsStep <= .3 * 2.5) { reasons.add("GNSS_RATE_UNSUITABLE"); return }
            val speedCount = floor((gpsTimes.last() - gpsTimes.first()) / gpsStep).toInt() + 1
            if (speedCount !in 12..1000) { reasons.add("GNSS_RATE_UNSUITABLE"); return }
            val speedTimes = List(speedCount) { gpsTimes.first() + it * gpsStep }
            val actualSpeed = DoubleArray(speedCount) { i -> interpolate(gpsTimes, speedTimes[i]) { gps[it].speedMps!! } }
            val smoothSpeed = LeanSignalFilters.lowPassZeroPhase(actualSpeed, 1 / gpsStep, .3, check)
            val speed = DoubleArray(count) { i -> interpolate(speedTimes, start + i / SAMPLE_HZ) { smoothSpeed[it] } }
            if (frame == null) {
                val upright = session.calibration.uprightGravity
                frame = LeanFrameCalibration.derive(accel, gyro, speed, 1 / SAMPLE_HZ,
                    LeanVector(upright[0], upright[1], upright[2]), check)
            }
            val body = frame ?: run { reasons.add("FRAME_UNAVAILABLE"); return }
            reasons.add("FILTER_WINDOW_EDGES_EXCLUDED")
            val kalman = BankKalman()
            var curveStart: Long? = null
            var curveEnd = 0L
            var curvePeak = 0.0
            var curveTime = 0L
            var curveSign = 0
            fun closeCurve() {
                val first = curveStart
                if (first != null && curveEnd - first >= 2_000_000_000L) {
                    if (curves.size < MAX_CURVES) {
                        val nearest = gps.minByOrNull { abs(it.elapsedNanos.toDouble() - curveTime.toDouble()) }!!
                        curves.add(LeanCurveResult(first, curveEnd,
                            if (curveSign < 0) round(curvePeak) else null, if (curveSign > 0) round(curvePeak) else null,
                            nearest.latitude, nearest.longitude, utc(first), utc(curveEnd)))
                    } else reasons.add("CURVE_LIMIT")
                }
                curveStart = null; curvePeak = 0.0
            }
            for (i in 0 until count) {
                if (i % 128 == 0) check()
                val time = origin + ((start + i / SAMPLE_HZ) * 1e9).roundToLong()
                val acceleration = body.transform(accel[i])
                val angular = body.transform(gyro[i])
                val value = kalman.update(acceleration, angular, speed[i], 1 / SAMPLE_HZ)
                if (value == null || speed[i] < 3.0) {
                    if (value == null) reasons.add("MODEL_CHANNEL_UNAVAILABLE")
                    kalman.reset(); closeCurve(); continue
                }
                // Edge exclusions and monotonic emit frontier prevent duplicates across overlap.
                if (i < 5 * SAMPLE_HZ || i > count - 1 - 5 * SAMPLE_HZ || time <= lastEmitted) continue
                validNanos += 20_000_000L; lastEmitted = time
                val degrees = Math.toDegrees(value)
                if (degrees <= -.5) left = maxOf(left ?: 0.0, -degrees)
                if (degrees >= .5) right = maxOf(right ?: 0.0, degrees)
                if (abs(degrees) < 5) { closeCurve(); continue }
                val direction = if (degrees < 0) -1 else 1
                if (curveStart != null && (direction != curveSign || time - curveEnd > 40_000_000L)) closeCurve()
                if (curveStart == null) { curveStart = time; curveSign = direction }
                curveEnd = time
                if (abs(degrees) > curvePeak) { curvePeak = abs(degrees); curveTime = time }
            }
            closeCurve()
        }

        private fun utc(time: Long): Long? {
            val delta = (time - origin) / 1_000_000L
            return if (delta < 0 || session.startedAtUtc > Long.MAX_VALUE - delta) null else session.startedAtUtc + delta
        }

        fun finish(): LeanEstimate {
            check()
            val observed = session.endedAtElapsedNanos?.takeIf { origin >= 0 && it >= origin }?.let { minOf(it - origin, DAY_NANOS) / 1_000_000L } ?: 0
            return LeanEstimate(ALGORITHM_VERSION, session.calibration.epoch,
                left?.let { round(it).takeIf { rounded -> rounded > 0 } },
                right?.let { round(it).takeIf { rounded -> rounded > 0 } },
                minOf(validNanos / 1_000_000, observed), observed, reasons.toSet(), curves.toList(),
                frame?.rotation().orEmpty())
        }
    }

    /** Linear two-state angle/roll-rate Kalman; sec2.4 eq15–19 stationary channel switch.
     * Q/R and2-pole/windowing are conservative versioned engineering choices, not accuracy claims.
     */
    private class BankKalman {
        private var angle = 0.0
        private var rate = 0.0
        private var p00 = .1
        private var p01 = 0.0
        private var p11 = .1
        fun reset() { angle = 0.0; rate = 0.0; p00 = .1; p01 = 0.0; p11 = .1 }
        fun update(acc: LeanVector, gyro: LeanVector, speed: Double, dt: Double): Double? {
            if (!speed.isFinite() || !acc.norm().isFinite() || acc.norm() !in 5.0..40.0) return null
            angle += dt * rate
            p00 += 2 * dt * p01 + dt * dt * p11 + 1e-5
            p01 += dt * p11
            p11 += .005 * dt
            // First measurement: true body roll rate, not a numerical GPS derivative.
            val rateVariance = .0004
            val rateInnovation = gyro.x - rate
            val sRate = p11 + rateVariance
            val k0 = p01 / sRate
            val k1 = p11 / sRate
            angle += k0 * rateInnovation; rate += k1 * rateInnovation
            val old01 = p01
            p00 -= k0 * old01; p01 -= k0 * p11; p11 -= k1 * p11
            val measurement = if (abs(angle) < Math.toRadians(10.0)) {
                val ratio = speed * gyro.z / LeanFrameCalibration.GRAVITY
                if (abs(ratio) > 1) return null
                asin(ratio)
            } else {
                val ratio = LeanFrameCalibration.GRAVITY / -acc.z
                if (acc.z >= 0 || ratio !in 0.0..1.0 || abs(acc.y) < .001) return null
                sign(acc.y) * acos(ratio) + atan(acc.y / acc.z)
            }
            if (!measurement.isFinite() || abs(measurement) > Math.toRadians(80.0)) return null
            val angleVariance = Math.toRadians(5.0).pow(2)
            val sAngle = p00 + angleVariance
            val ka = p00 / sAngle
            val kr = p01 / sAngle
            val innovation = measurement - angle
            angle += ka * innovation; rate += kr * innovation
            val old00 = p00; val before01 = p01
            p00 -= ka * old00; p01 -= ka * before01; p11 -= kr * before01
            return angle.takeIf { it.isFinite() && abs(it) <= Math.toRadians(80.0) }
        }
    }

    companion object {
        const val ALGORITHM_VERSION = "stanglmayr2020-frd-bw2-window60-kalman-v1"
        private const val SAMPLE_HZ = 50.0
        private const val WINDOW_NANOS = 60_000_000_000L
        private const val OVERLAP_NANOS = 10_000_000_000L
        private const val DAY_NANOS = 86_400_000_000_000L
        private const val MAX_WINDOW_RECORDS = 12_000
        private const val MAX_TOTAL_RECORDS = 8_000_000
        private const val MAX_CURVES = LeanCodec.MAX_CURVES
    }
}