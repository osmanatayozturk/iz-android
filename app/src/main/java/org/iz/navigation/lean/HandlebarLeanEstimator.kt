package org.iz.navigation.lean

import java.util.PriorityQueue
import kotlin.math.*
import org.iz.navigation.lean.LeanGnssIssueCode.*

/**
 * Experimental post-ride estimate for a handlebar-mounted phone.
 *
 * Approximate bank in a coordinated turn is atan(speed * courseRate / g), with the
 * signed course rate measured clockwise from north. The IMU checks the predicted
 * specific-force magnitude and angular motion. A steering-axis transient, inconsistent
 * fix or missing sample ends support. A riding position that shifts the combined center
 * of mass can still change chassis bank at the same equilibrium angle.
 *
 * Equilibrium model: Maceira et al., Sensors 2021, doi:10.3390/s21196626, eq. (5).
 * The quality thresholds below are versioned engineering choices for field validation.
 */
class HandlebarLeanEstimator : LeanEstimator {
    override fun estimate(
        session: LeanInputSession,
        records: Sequence<LeanRecord>,
        checkAllowed: () -> Unit,
    ): LeanEstimate {
        checkAllowed()
        val report = Report(session)
        val reference = Reference.from(session.calibration)
        val end = session.endedAtElapsedNanos
        if (reference == null || end == null || !validSession(session, end)) {
            report.reasons += "INVALID_SESSION_OR_CALIBRATION"
            return report.finish()
        }

        val gnss = LeanGnssIssueCounter()
        report.gnss = gnss
        val input = OrderedInput(reference, report::utc, report::cut, gnss, allowEstimatedTiming = true) { a, b, motion ->
            if (!motion.timingEstimated) report.validNanos += b.elapsedNanos - a.elapsedNanos
            if (abs(motion.degrees) >= MIN_BANK_DEGREES) report.support(a, b, motion) else report.closeCurve()
        }
        // GNSS callbacks may be delivered after IMU events with newer measurement times.
        // Drain by measurement time, with IMU endpoints delivered before the GNSS fix.
        val pending = PriorityQueue(compareBy<LeanRecord> { it.elapsedNanos }
            .thenBy { if (it is LeanGnssRecord) 1 else 0 })
        fun drain(through: Long) {
            while (pending.peek()?.elapsedNanos?.let { it <= through } == true) {
                val record = pending.remove()
                if (record is LeanGnssRecord && pending.peek()?.elapsedNanos == record.elapsedNanos) {
                    var count = 1L
                    while (pending.peek()?.elapsedNanos == record.elapsedNanos) {
                        pending.remove()
                        count++
                    }
                    input.duplicateFix(count)
                } else {
                    input.accept(record)
                }
            }
        }
        var newest = session.startedAtElapsedNanos
        var releasedThrough = newest - 1
        for ((index, record) in records.withIndex()) {
            if (index % 128 == 0) checkAllowed()
            if (index >= MAX_TOTAL_RECORDS) return report.discard("INPUT_RECORD_LIMIT")
            val time = record.elapsedNanos
            if (time !in session.startedAtElapsedNanos..end) {
                report.reasons += "OUTSIDE_SESSION"
                continue
            }
            // A measurement arriving after its interval was evaluated invalidates the
            // interval's completeness. Do not publish peaks computed from that prefix.
            if (time <= releasedThrough) return report.discard("LATE_RECORD")
            newest = maxOf(newest, time)
            releasedThrough = maxOf(releasedThrough, newest - REORDER_NANOS)
            drain(releasedThrough)
            if (pending.size >= MAX_WINDOW_RECORDS) return report.discard("WINDOW_RECORD_LIMIT")
            pending.add(record)
        }
        checkAllowed()
        drain(Long.MAX_VALUE)
        checkAllowed()
        input.finish(report)
        return report.finish()
    }

    private data class Reference(val upright: LeanVector, val bias: LeanVector, val steering: LeanVector?) {
        companion object {
            fun from(value: LeanCalibration): Reference? {
                if (value.bodyConfirmed || value.epoch.isBlank() || value.createdAt <= 0) return null
                val gravity = vector(value.uprightGravity) ?: return null
                val bias = vector(value.gyroBias) ?: return null
                val axis = vector(value.steeringAxis) ?: return null
                if (gravity.norm() !in 8.8..10.8 || bias.norm() > .5 || axis.dot(axis) !in .98..1.02) return null
                return Reference(gravity.unit() ?: return null, bias, axis.unit() ?: return null)
            }

            private fun vector(values: List<Double>): LeanVector? =
                if (values.size == 3 && values.all { it.isFinite() })
                    LeanVector(values[0], values[1], values[2]) else null
        }
    }

    private enum class SpeedClass(
        val positionAccuracy: Double,
        val speedAccuracy: Double,
        val bearingAccuracy: Double,
        val minTurnDegrees: Double,
        val maxLongitudinalAcceleration: Double,
        val minSupportNanos: Long,
    ) {
        LOW_SPEED(6.0, .65, 6.0, 6.0, .9, 2_000_000_000L),
        FLOWING(12.0, 1.0, 8.0, 4.0, 1.5, 3_000_000_000L),
    }

    private sealed interface GnssInterval {
        data class Rejected(val reason: String, val issues: Set<LeanGnssIssueCode>) : GnssInterval
        data class Parked(val disagreement: Boolean) : GnssInterval
        data class Moving(val kind: SpeedClass, val speed: Double, val yaw: Double,
            val bank: Double, val degrees: Double, val timingEstimated: Boolean) : GnssInterval
    }

    private class OrderedInput(
        private val reference: Reference,
        private val expectedUtc: (Long) -> Long?,
        private val cut: (String?) -> Unit,
        private val gnss: LeanGnssIssueCounter? = null,
        private val allowEstimatedTiming: Boolean = false,
        private val onValid: (LeanGnssRecord, LeanGnssRecord, GnssInterval.Moving) -> Unit,
    ) {
        private val acceleration = ArrayDeque<LeanImuRecord>()
        private val rotation = ArrayDeque<LeanImuRecord>()
        private val sensors = listOf(acceleration, rotation)
        private var previous: LeanGnssRecord? = null
        private var havePair = false
        private var haveParkedPair = false
        private var movementEvidence = false

        fun accept(record: LeanRecord) {
            val time = record.elapsedNanos
            for (queue in sensors) {
                while (queue.firstOrNull()?.let { time - it.elapsedNanos > MAX_GNSS_STEP + 80_000_000L } == true) {
                    queue.removeFirst()
                }
            }
            when (record) {
                is LeanImuRecord -> {
                    val queue = if (record.kind == LeanSensorKind.ACCELEROMETER) acceleration else rotation
                    queue.addLast(record)
                }
                is LeanGnssRecord -> {
                    // A quality moving fix can disprove a parked session even when a gap
                    // leaves it without an adjacent fix usable for estimating a curve.
                    if (LeanGnssQuality.movingEvidence(record, LeanMount.HANDLEBAR) &&
                        LeanGnssQuality.fixIssues(record, LeanMount.HANDLEBAR).isEmpty() &&
                        LeanGnssQuality.clockValid(record, expectedUtc(record.elapsedNanos), LeanMount.HANDLEBAR)) {
                        movementEvidence = true
                    }
                    previous?.let { before ->
                        havePair = true
                        interval(before, record)
                    }
                    previous = record
                }
            }
        }

        fun finish(report: Report) {
            report.closeCurve()
            if (!havePair) report.reasons += "GNSS_RATE_UNSUITABLE"
            if (haveParkedPair && !movementEvidence) report.reasons += "PARKED_STEERING_OR_STATIONARY"
        }

        fun duplicateFix(count: Long) {
            previous = null
            gnss?.fix(LeanGnssIssueCode.GNSS_DUPLICATE_TIME, count)
            cut("GNSS_DUPLICATE_TIME")
        }

        private fun interval(a: LeanGnssRecord, b: LeanGnssRecord) {
            val span = b.elapsedNanos - a.elapsedNanos
            val motion = when (val evaluated = gnssInterval(a, b, expectedUtc, allowEstimatedTiming)) {
                is GnssInterval.Rejected -> {
                    evaluated.issues.forEach { gnss?.pair(it, span) }
                    return cut(evaluated.reason)
                }
                is GnssInterval.Parked -> {
                    if (evaluated.disagreement) {
                        movementEvidence = true
                        gnss?.pair(LeanGnssIssueCode.GNSS_MOTION_DISAGREEMENT, span)
                        return cut("GNSS_MOTION_DISAGREEMENT")
                    }
                    haveParkedPair = true
                    return cut(null)
                }
                is GnssInterval.Moving -> evaluated
            }
            val from = a.elapsedNanos
            val until = b.elapsedNanos
            val forceRows = acceleration.filter { it.elapsedNanos in from..<until }
            val gyroRows = rotation.filter { it.elapsedNanos in from..<until }
            val reason = imuProblem(forceRows, acceleration.lastOrNull { it.elapsedNanos < from },
                from, until, 100.0, motion.timingEstimated) ?:
                imuProblem(gyroRows, rotation.lastOrNull { it.elapsedNanos < from }, from, until, 30.0, motion.timingEstimated)
            if (reason != null) return cut(reason)
            val veto = sensorVeto(forceRows, gyroRows, motion.bank, motion.yaw, motion.speed, reference)
            if (veto != null) return cut(veto)

            if (motion.timingEstimated) {
                if (!signedImuTurnAgrees(forceRows, gyroRows, motion, reference)) return cut("SENSOR_DISAGREEMENT")
                movementEvidence = true
            }
            onValid(a, b, motion)
        }
    }

    private class Report(private val session: LeanInputSession) {
        val reasons = linkedSetOf<String>().apply { if (session.droppedSamples > 0) add("DROPPED_INPUT") }
        private val curves = ArrayList<LeanCurveResult>()
        private var current: Curve? = null
        private var estimatedNanos = 0L
        var gnss: LeanGnssIssueCounter? = null
        var validNanos: Long = 0

        fun cut(reason: String?) {
            if (reason != null) reasons += reason
            closeCurve()
        }

        fun support(a: LeanGnssRecord, b: LeanGnssRecord, motion: GnssInterval.Moving) {
            val direction = if (motion.degrees < 0) -1 else 1
            val existing = current
            if (existing == null || existing.direction != direction || existing.end != a.elapsedNanos ||
                existing.timingEstimated != motion.timingEstimated) {
                closeCurve()
                current = Curve(a.elapsedNanos, b.elapsedNanos, direction, abs(motion.degrees), a, motion.kind, motion.timingEstimated)
            } else {
                existing.end = b.elapsedNanos
                existing.intervals++
                if (motion.kind == SpeedClass.LOW_SPEED) existing.kind = motion.kind
                if (abs(motion.degrees) > existing.peak) {
                    existing.peak = abs(motion.degrees)
                    existing.peakFix = a
                }
            }
        }

        fun closeCurve() {
            val curve = current ?: return
            current = null
            val minimumPairs = if (curve.timingEstimated) 4 else 3
            if (curve.intervals < minimumPairs || curve.end - curve.start < curve.kind.minSupportNanos) {
                reasons += "CURVE_TOO_SHORT"
                return
            }
            if (curves.size >= MAX_CURVES) {
                reasons += "CURVE_LIMIT"
                return
            }
            val peak = round(curve.peak)
            curves += LeanCurveResult(curve.start, curve.end, peak.takeIf { curve.direction < 0 },
                peak.takeIf { curve.direction > 0 }, curve.peakFix.latitude, curve.peakFix.longitude,
                utc(curve.start), utc(curve.end), timingEstimated = curve.timingEstimated)
            if (curve.timingEstimated) estimatedNanos += curve.end - curve.start
        }

        fun utc(time: Long): Long? {
            val millis = (time - session.startedAtElapsedNanos) / NANOS_PER_MILLI
            return if (millis < 0 || session.startedAtUtc > Long.MAX_VALUE - millis) null
            else session.startedAtUtc + millis
        }

        fun discard(reason: String): LeanEstimate {
            reasons += reason
            current = null
            validNanos = 0
            estimatedNanos = 0
            curves.clear()
            return finish()
        }

        fun finish(): LeanEstimate {
            val start = session.startedAtElapsedNanos
            val observed = session.endedAtElapsedNanos?.takeIf { start >= 0 && it >= start }
                ?.let { minOf(it - start, DAY_NANOS) / NANOS_PER_MILLI } ?: 0
            val supportedNanos = validNanos + estimatedNanos
            if (supportedNanos == 0L) reasons += "NO_VALID_COVERAGE"
            else if (curves.isEmpty()) reasons += "NO_SUPPORTED_CURVES"
            val timingReason = if (estimatedNanos > 0) setOf(TIMING_ESTIMATED_REASON) else emptySet()
            val allReasons = (timingReason + reasons + session.partialReasons.asSequence()
                .filter { it != TIMING_ESTIMATED_REASON && REASON_PATTERN.matches(it) }.sorted().take(64).toList()).take(64).toSet()
            return LeanEstimate(VERSION, session.calibration.epoch, curves.mapNotNull { it.leftDegrees }.maxOrNull(),
                curves.mapNotNull { it.rightDegrees }.maxOrNull(), supportedNanos / NANOS_PER_MILLI, observed,
                allReasons, curves.toList(), gnssDiagnostics = gnss?.finish())
        }
    }

    private data class Curve(
        val start: Long, var end: Long, val direction: Int, var peak: Double, var peakFix: LeanGnssRecord,
        var kind: SpeedClass, val timingEstimated: Boolean, var intervals: Int = 1,
    )

    companion object {
        /** Two measured fixes and their IMU interval; no report, journey ID or storage. */
        internal fun liveDegrees(calibration: LeanCalibration, first: LeanGnssRecord, last: LeanGnssRecord,
            imu: List<LeanImuRecord>): Double? {
            if (first.elapsedNanos <= 0 || first.utcMillis <= 0 ||
                last.elapsedNanos - first.elapsedNanos !in MIN_GNSS_STEP..MAX_GNSS_STEP ||
                imu.size > LiveLeanSampleWindow.MAX_RECORDS) return null
            val reference = if (calibration.bodyConfirmed) {
                if (!validLeanReference(calibration)) return null
                val gravity = calibration.uprightGravity
                val bias = calibration.gyroBias
                Reference(LeanVector(gravity[0], gravity[1], gravity[2]).unit() ?: return null,
                    LeanVector(bias[0], bias[1], bias[2]), null)
            } else Reference.from(calibration) ?: return null
            var result: Double? = null
            val input = OrderedInput(reference,
                { timestamp -> first.utcMillis + (timestamp - first.elapsedNanos) / NANOS_PER_MILLI },
                { result = null }) { _, _, motion -> result = motion.degrees }
            input.accept(first)
            imu.forEach(input::accept)
            input.accept(last)
            return result
        }

        const val LEGACY_VERSION = "handlebar-gnss-imu-v1"
        const val VERSION = "handlebar-gnss-imu-v2"
        private const val TIMING_ESTIMATED_REASON = "HANDLEBAR_TIMING_ESTIMATED"
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MIN_GNSS_STEP = 350_000_000L
        private const val MAX_GNSS_STEP = 1_600_000_000L
        private const val ESTIMATED_MIN_STEP = 750_000_000L
        private const val ESTIMATED_MAX_STEP = 1_250_000_000L
        private const val MAX_SINGLE_FIX_AGE = 250_000_000L
        private const val MAX_SINGLE_FIX_JITTER = 60_000_000L
        private const val MAX_ESTIMATED_IMU_GAP = 40_000_000L
        private const val REORDER_NANOS = 2_000_000_000L
        private const val DAY_NANOS = 86_400_000_000_000L
        private const val MAX_TOTAL_RECORDS = 8_000_000
        private const val MAX_WINDOW_RECORDS = 4096
        private const val MAX_CURVES = 2000 // LeanCodec.MAX_CURVES serialized result bound.
        private const val MIN_BANK_DEGREES = 4.0
        private const val MIN_MOVING_SPEED = 2.5
        private val REASON_PATTERN = Regex("[A-Za-z0-9_.:-]{1,100}")

        private fun validSession(session: LeanInputSession, end: Long): Boolean {
            val start = session.startedAtElapsedNanos
            return start >= 0 && end >= start && end - start <= DAY_NANOS && session.startedAtUtc > 0 &&
                session.startedAtUtc <= Long.MAX_VALUE - (end - start) / NANOS_PER_MILLI &&
                session.token.id.isNotBlank() && session.token.journeyId.isNotBlank() &&
                session.token.generation >= 0 && session.bootId.isNotBlank() &&
                session.inputHash.matches(Regex("[A-Fa-f0-9]{64}")) && session.droppedSamples >= 0
        }

        internal fun movingGnssIssue(first: LeanGnssRecord, last: LeanGnssRecord,
            expectedUtc: (Long) -> Long?): LeanGnssIssueCode? =
            (gnssInterval(first, last, expectedUtc) as? GnssInterval.Rejected)?.issues?.firstOrNull()

        private fun gnssInterval(a: LeanGnssRecord, b: LeanGnssRecord, expectedUtc: (Long) -> Long?,
            allowEstimatedTiming: Boolean = false): GnssInterval {
            fun reject(code: LeanGnssIssueCode, reason: String = code.name) = GnssInterval.Rejected(reason, setOf(code))
            val mount = LeanMount.HANDLEBAR
            val span = b.elapsedNanos - a.elapsedNanos
            if (span > MAX_GNSS_STEP) return reject(GNSS_DATA_GAP, "DATA_GAP")
            if (span < MIN_GNSS_STEP) return reject(GNSS_RATE_UNSUITABLE)
            if (!LeanGnssQuality.clockValid(a, expectedUtc(a.elapsedNanos), mount) ||
                !LeanGnssQuality.clockValid(b, expectedUtc(b.elapsedNanos), mount) ||
                abs((b.utcMillis - a.utcMillis) - span / NANOS_PER_MILLI) > 250L) {
                return reject(CLOCK_DISCONTINUITY)
            }
            val estimated = allowEstimatedTiming && singleFixTimingAgrees(a, b, span, expectedUtc)
            val reportedIssues = LeanGnssQuality.fixIssues(a, mount) + LeanGnssQuality.fixIssues(b, mount)
            val baseIssues = if (estimated) reportedIssues - GNSS_TIME_UNCERTAINTY_MISSING else reportedIssues
            if (baseIssues.isNotEmpty()) return GnssInterval.Rejected("GNSS_QUALITY", baseIssues)
            val va = requireNotNull(a.speedMps)
            val vb = requireNotNull(b.speedMps)
            if (estimated && minOf(va, vb) < 8.0) return reject(LOW_SPEED_UNSUPPORTED)
            val parkedSpeed = LeanGnssQuality.HANDLEBAR_PARKED_SPEED
            if (va <= parkedSpeed && vb <= parkedSpeed) {
                val positionUncertainty = requireNotNull(a.accuracyMeters) + requireNotNull(b.accuracyMeters)
                val maxParkedDisplacement = parkedSpeed * (span / NANOS_PER_SECOND) + positionUncertainty
                return GnssInterval.Parked(distanceMeters(a, b) > maxOf(3.0, maxParkedDisplacement))
            }
            if (va < MIN_MOVING_SPEED || vb < MIN_MOVING_SPEED) return reject(LOW_SPEED_UNSUPPORTED)
            val kind = if (minOf(va, vb) >= 8.0) SpeedClass.FLOWING else SpeedClass.LOW_SPEED
            fun movingIssues(fix: LeanGnssRecord) = if (estimated) {
                LeanGnssQuality.movingHandlebarIssues(fix, 5.0, .65, 4.0)
            } else LeanGnssQuality.movingHandlebarIssues(fix, kind.positionAccuracy, kind.speedAccuracy, kind.bearingAccuracy)
            val movingIssues = movingIssues(a) + movingIssues(b)
            if (movingIssues.isNotEmpty()) return GnssInterval.Rejected("GNSS_QUALITY", movingIssues)
            val duration = span / NANOS_PER_SECOND
            val accelerationLimit = if (estimated) 1.0 else kind.maxLongitudinalAcceleration
            if (abs(vb - va) / duration > accelerationLimit) return reject(SPEED_TRANSIENT)

            val firstBearing = requireNotNull(a.bearingDegrees)
            val secondBearing = requireNotNull(b.bearingDegrees)
            val headingChange = angleDifference(secondBearing, firstBearing)
            // A sparse pair double-backs or exceeds the time resolution for a useful peak.
            if (abs(headingChange) > 95.0) return reject(GNSS_COURSE_UNCERTAIN)
            val bearingUncertainty = hypot(requireNotNull(a.bearingAccuracyDegrees), requireNotNull(b.bearingAccuracyDegrees))
            if (estimated && abs(headingChange) !in maxOf(8.0, 2.0 * bearingUncertainty)..50.0) {
                return reject(GNSS_COURSE_UNCERTAIN)
            }
            val yaw = Math.toRadians(headingChange) / duration
            val speed = (va + vb) / 2.0
            if (!consistentMotion(a, b, speed * duration, firstBearing + headingChange / 2.0, headingChange, kind, estimated)) {
                return reject(GNSS_MOTION_DISAGREEMENT)
            }
            val bank = atan(speed * yaw / LeanFrameCalibration.GRAVITY)
            val degrees = Math.toDegrees(bank)
            if (abs(degrees) > 60.0) return reject(MODEL_CHANNEL_UNAVAILABLE)
            if (estimated && abs(degrees) !in 8.0..45.0) return reject(MODEL_CHANNEL_UNAVAILABLE)
            val turn = abs(degrees) >= MIN_BANK_DEGREES
            if (turn && abs(headingChange) < maxOf(kind.minTurnDegrees, 1.5 * bearingUncertainty)) {
                return reject(GNSS_COURSE_UNCERTAIN)
            }
            return GnssInterval.Moving(kind, speed, yaw, bank, degrees, estimated)
        }

        /** Callback times are a bounded corroborating witness. GNSS and IMU stay in measurement time. */
        private fun singleFixTimingAgrees(a: LeanGnssRecord, b: LeanGnssRecord, span: Long,
            expectedUtc: (Long) -> Long?): Boolean {
            if (span !in ESTIMATED_MIN_STEP..ESTIMATED_MAX_STEP || a.elapsedNanos <= 0L ||
                a.elapsedUncertaintyNanos != null || b.elapsedUncertaintyNanos != null ||
                a.invalidElapsedUncertainty || b.invalidElapsedUncertainty ||
                a.provider != b.provider || (a.provider != "gps" && a.provider != "fused")) return false
            val firstReceived = a.singleFixReceivedElapsedNanos ?: return false
            val lastReceived = b.singleFixReceivedElapsedNanos ?: return false
            if (firstReceived < a.elapsedNanos || lastReceived < b.elapsedNanos || firstReceived >= lastReceived) return false
            val firstAge = firstReceived - a.elapsedNanos
            val lastAge = lastReceived - b.elapsedNanos
            if (firstAge > MAX_SINGLE_FIX_AGE || lastAge > MAX_SINGLE_FIX_AGE ||
                abs(lastAge - firstAge) > MAX_SINGLE_FIX_JITTER) return false
            val firstUtc = expectedUtc(a.elapsedNanos) ?: return false
            val lastUtc = expectedUtc(b.elapsedNanos) ?: return false
            return abs(a.utcMillis - firstUtc) <= 150L && abs(b.utcMillis - lastUtc) <= 150L &&
                abs((b.utcMillis - a.utcMillis) - span / NANOS_PER_MILLI) <= 80L
        }

        private fun angleDifference(next: Double, previous: Double) = (next - previous + 540.0) % 360.0 - 180.0

        private fun distanceMeters(a: LeanGnssRecord, b: LeanGnssRecord): Double {
            val latA = Math.toRadians(a.latitude)
            val latB = Math.toRadians(b.latitude)
            val deltaLon = Math.toRadians(angleDifference(b.longitude, a.longitude))
            val h = sin((latB - latA) / 2).pow(2) + cos(latA) * cos(latB) * sin(deltaLon / 2).pow(2)
            return 2 * 6_371_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        private fun consistentMotion(a: LeanGnssRecord, b: LeanGnssRecord, expectedArc: Double,
            midCourse: Double, headingChange: Double, kind: SpeedClass, estimatedTiming: Boolean): Boolean {
            val distance = distanceMeters(a, b)
            val half = Math.toRadians(headingChange / 2)
            val expectedChord = expectedArc * if (abs(half) < 1e-6) 1.0 else sin(half) / half
            if (estimatedTiming) {
                val positionalNoise = hypot(requireNotNull(a.accuracyMeters), requireNotNull(b.accuracyMeters))
                if (distance !in maxOf(.70 * expectedChord, 2.0 * positionalNoise)..(1.30 * expectedChord)) return false
            } else if (distance < maxOf(1.0, .5 * expectedChord) || distance > 1.6 * expectedChord + 1.0) return false
            val latA = Math.toRadians(a.latitude)
            val latB = Math.toRadians(b.latitude)
            val deltaLon = Math.toRadians(angleDifference(b.longitude, a.longitude))
            val north = cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(deltaLon)
            val east = sin(deltaLon) * cos(latB)
            val chordCourse = (Math.toDegrees(atan2(east, north)) + 360) % 360
            val maxCourseError = when {
                estimatedTiming -> 15.0
                kind == SpeedClass.LOW_SPEED -> 30.0
                else -> 25.0
            }
            return abs(angleDifference(chordCourse, midCourse)) <= maxCourseError
        }

        private fun imuProblem(rows: List<LeanImuRecord>, preceding: LeanImuRecord?, from: Long,
            until: Long, limit: Double, estimatedTiming: Boolean): String? {
            if (rows.isEmpty()) return "IMU_UNAVAILABLE"
            val edgeLimit = if (estimatedTiming) MAX_ESTIMATED_IMU_GAP else 60_000_000L
            val gapLimit = if (estimatedTiming) MAX_ESTIMATED_IMU_GAP else 80_000_000L
            if (rows.first().elapsedNanos - from > edgeLimit || until - rows.last().elapsedNanos > edgeLimit ||
                (estimatedTiming && 10 * (rows.last().elapsedNanos - rows.first().elapsedNanos) < 9 * (until - from))) {
                return "IMU_ALIGNMENT"
            }
            // The pair may look aligned at each edge separately while a hole spans the shared fix.
            var previous = preceding?.takeIf { from - it.elapsedNanos <= 80_000_000L }
            for (row in rows) {
                if (row.sequence < 0 || row.accuracy !in 1..3 ||
                    !(row.x in -limit..limit && row.y in -limit..limit && row.z in -limit..limit)) return "IMU_QUALITY"
                val old = previous
                if (old != null) {
                    val gap = row.elapsedNanos - old.elapsedNanos
                    if (gap > gapLimit) return "DATA_GAP"
                    if (gap <= 0 || row.sequence <= old.sequence) return "IMU_QUALITY"
                    if (row.sequence - old.sequence != 1L) return "IMU_SEQUENCE_GAP"
                }
                previous = row
            }
            if (rows.size < (until - from) / 40_000_000L) return "IMU_RATE_UNSUITABLE"
            return null
        }

        private fun average(rows: List<LeanVector>) =
            LeanVector(rows.sumOf { it.x }, rows.sumOf { it.y }, rows.sumOf { it.z }) * (1.0 / rows.size)

        private fun signedImuTurnAgrees(forceRows: List<LeanImuRecord>, gyroRows: List<LeanImuRecord>,
            motion: GnssInterval.Moving, reference: Reference): Boolean {
            val up = average(forceRows.map { LeanVector(it.x, it.y, it.z) }).unit() ?: return false
            if (up.dot(reference.upright) < cos(Math.toRadians(15.0))) return false

            fun verticalRate(row: LeanImuRecord) = (LeanVector(row.x, row.y, row.z) - reference.bias).dot(up)
            var previous = gyroRows.first()
            var previousRate = verticalRate(previous)
            var observed = 0.0
            for (index in 1 until gyroRows.size) {
                val next = gyroRows[index]
                val rate = verticalRate(next)
                val seconds = (next.elapsedNanos - previous.elapsedNanos) / NANOS_PER_SECOND
                observed += (previousRate + rate) * 0.5 * seconds
                previousRate = rate
                previous = next
            }
            val measuredSeconds = (gyroRows.last().elapsedNanos - gyroRows.first().elapsedNanos) / NANOS_PER_SECOND
            val expected = -motion.yaw * cos(motion.bank) * measuredSeconds
            return observed * expected > 0.0 && abs(observed - expected) <= maxOf(Math.toRadians(2.0), abs(expected) * .20)
        }

        private fun sensorVeto(forceRows: List<LeanImuRecord>, gyroRows: List<LeanImuRecord>,
            bank: Double, yaw: Double, speed: Double, reference: Reference): String? {
            val forces = forceRows.map { LeanVector(it.x, it.y, it.z) }
            val force = average(forces)
            val up = force.unit() ?: return "SENSOR_DISAGREEMENT"
            val expectedForce = hypot(LeanFrameCalibration.GRAVITY, speed * yaw)
            if (up.dot(reference.upright) < cos(Math.toRadians(30.0)) ||
                abs(force.norm() - expectedForce) > maxOf(1.25, expectedForce * .12) ||
                forces.any { (it - force).norm() > 3.5 }) return "STEERING_OR_MOTION_VETO"

            val angular = gyroRows.map { LeanVector(it.x, it.y, it.z) - reference.bias }
            val mean = average(angular)
            if (mean.norm() < abs(yaw) * .4) return "SENSOR_DISAGREEMENT"
            val expectedVertical = -yaw * cos(bank)
            val expectedHorizontal = abs(yaw * sin(bank))
            if (angular.any { (it - mean).norm() > .35 }) return "STEERING_OR_MOTION_VETO"
            val axis = reference.steering
            if (axis != null) {
                val steeringOnUp = axis.dot(up)
                val steeringOffUp = (axis - up * steeringOnUp).norm()
                val steeringExcess = abs(mean.dot(axis) - expectedVertical * steeringOnUp) -
                    expectedHorizontal * steeringOffUp
                if (steeringExcess > maxOf(.12, abs(yaw) * .28) ||
                    angular.any { abs((it - mean).dot(axis)) > .22 }) {
                    return "STEERING_OR_MOTION_VETO"
                }
            }
            val vertical = mean.dot(up)
            val horizontal = (mean - up * vertical).norm()
            if (abs(vertical - expectedVertical) > maxOf(.045, .30 * abs(expectedVertical)) ||
                abs(horizontal - expectedHorizontal) > maxOf(.08, .45 * expectedHorizontal)) return "SENSOR_DISAGREEMENT"
            return null
        }
    }
}
