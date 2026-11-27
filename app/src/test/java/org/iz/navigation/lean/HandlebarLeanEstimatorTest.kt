package org.iz.navigation.lean

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class HandlebarLeanEstimatorTest {
    private val g = 9.80665
    private val second = 1_000_000_000L
    private val start = 10 * second
    private val utc = 1_700_000_000_000L
    private val axis = listOf(-.4, 0.0, sqrt(.84))
    private val calibration = LeanCalibration("handlebar", utc, listOf(0.0, 0.0, g),
        listOf(0.0, 0.0, .02), bodyConfirmed = false, steeringAxis = axis)
    private fun session(reference: LeanCalibration = calibration) = LeanInputSession(
        LeanSessionToken("session", "journey", 0), reference, "boot", utc, start,
        start + 12 * second, "a".repeat(64), emptySet(), 0)

    /** Local synthetic arc with independent headings and coordinates, not measured chassis truth. */
    private fun ride(speed: Double = 12.0, yaw: Double = .30, steeringRate: Double = 0.0,
        turnStarts: Double = 0.0, turnEnds: Double = 12.0, stationaryUntil: Double = 0.0,
        sensorHz: Int = 50, yawAt: ((Double) -> Double)? = null): List<LeanRecord> = buildList {
        var north = 0.0
        var east = 0.0
        var course = 350.0
        for (i in 0..12 * sensorHz) {
            val t = i.toDouble() / sensorHz
            val sampleSpeed = if (t < stationaryUntil) 0.0 else speed
            val turn = yawAt?.invoke(t) ?: if (t in turnStarts..<turnEnds) yaw else 0.0
            val bank = atan(sampleSpeed * turn / g)
            val ns = start + i * second / sensorHz
            // Right-positive course rotates clockwise around world up. Lean changes its projection in device coordinates.
            add(LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), ns,
                0.0, 0.0, hypot(g, sampleSpeed * turn), 3))
            add(LeanImuRecord(LeanSensorKind.GYROSCOPE, i.toLong(), ns,
                axis[0] * steeringRate, -turn * sin(bank), .02 - turn * cos(bank) + axis[2] * steeringRate, 3))
            if (i % sensorHz == 0) add(LeanGnssRecord(ns, utc + i * 1000L / sensorHz, Math.toDegrees(north / 6_371_000),
                Math.toDegrees(east / 6_371_000), 1.0, sampleSpeed, (course % 360 + 360) % 360,
                .2, 1.0, 1_000_000.0, false, "gps"))
            val mid = Math.toRadians(course) + turn / (2 * sensorHz)
            north += sampleSpeed / sensorHz * cos(mid)
            east += sampleSpeed / sensorHz * sin(mid)
            course += Math.toDegrees(turn / sensorHz)
        }
    }

    private fun estimate(records: Iterable<LeanRecord>, input: LeanInputSession = session()) =
        HandlebarLeanEstimator().estimate(input, records.asSequence()) {}

    private fun withSingleFixTiming(records: List<LeanRecord>, provider: String = "gps") = records.map {
        if (it is LeanGnssRecord) it.copy(elapsedUncertaintyNanos = null, provider = provider,
            singleFixReceivedElapsedNanos = it.elapsedNanos + 50_000_000L) else it
    }

    @Test fun liveTrackerRequiresFourSameDirectionPairsAndPublishesTheLatestMeasurement() {
        for (direction in listOf(-1.0, 1.0)) {
            val records = withSingleFixTiming(ride(yawAt = { if (it < 4.0) direction * .30 else direction * .24 }))
            val fixes = records.filterIsInstance<LeanGnssRecord>()
            val imu = records.filterIsInstance<LeanImuRecord>() // Includes future timestamps by design.
            val tracker = LiveHandlebarTracker()
            fun decide(index: Int) = tracker.evaluate(calibration, fixes[index], imu, fixes[index].elapsedNanos + 60_000_000)
            assertEquals(LiveHandlebarReason.WAITING_FOR_PAIR, decide(0).reason())
            for (index in 1..3) assertEquals(LiveHandlebarReason.TURN_SUPPORT, decide(index).reason())
            val first = decide(4) as LiveHandlebarDecision.Accepted
            assertEquals(LiveHandlebarSource.ESTIMATED, first.source)
            assertEquals(direction * 20.158, first.signedDegrees, .01)
            assertEquals(fixes[4].elapsedNanos, first.measuredElapsedNanos)
            val latest = decide(5) as LiveHandlebarDecision.Accepted
            assertEquals(fixes[5].elapsedNanos, latest.measuredElapsedNanos)
            assertTrue(abs(latest.signedDegrees) < abs(first.signedDegrees))
            assertEquals(LiveHandlebarReason.WAITING_FOR_PAIR, tracker.evaluate(calibration.copy(epoch = "new-epoch"),
                fixes[6], imu, fixes[6].elapsedNanos + 60_000_000).reason())
            tracker.reset()
            assertEquals(LiveHandlebarReason.WAITING_FOR_PAIR, decide(7).reason())
        }
    }

    @Test fun liveTrackerClearsOnBatchMissingReceiptOrStaleFixAndRebuildsSupport() {
        val records = withSingleFixTiming(ride())
        val fixes = records.filterIsInstance<LeanGnssRecord>()
        val imu = records.filterIsInstance<LeanImuRecord>()
        val tracker = LiveHandlebarTracker()
        fun decide(index: Int, batch: Boolean = false) =
            tracker.evaluate(calibration, fixes[index], imu, fixes[index].elapsedNanos + 60_000_000, batch)
        repeat(4) { decide(it) }
        assertTrue(decide(4) is LiveHandlebarDecision.Accepted)
        assertEquals(LiveHandlebarReason.GNSS_BATCH, decide(5, batch = true).reason())
        assertEquals(LiveHandlebarReason.WAITING_FOR_PAIR, decide(6).reason())
        for (index in 7..9) assertEquals(LiveHandlebarReason.TURN_SUPPORT, decide(index).reason())
        assertTrue(decide(10) is LiveHandlebarDecision.Accepted)
        assertEquals(LiveHandlebarReason.GNSS_NO_SINGLE_FIX_RECEIPT, tracker.evaluate(calibration,
            fixes[11].copy(singleFixReceivedElapsedNanos = null), imu, fixes[11].elapsedNanos + 60_000_000).reason())
        assertEquals(LiveHandlebarReason.WAITING_FOR_PAIR, decide(12).reason())
        assertEquals(LiveHandlebarReason.GNSS_STALE, tracker.evaluate(calibration, fixes[12], imu,
            fixes[12].elapsedNanos + 1_500_000_000L).reason())
    }

    @Test fun liveTrackerSeparatesClockSourceAndReceiptEvidenceFromReportedUncertainty() {
        val records = withSingleFixTiming(ride())
        val fixes = records.filterIsInstance<LeanGnssRecord>()
        val imu = records.filterIsInstance<LeanImuRecord>()
        fun evaluate(tracker: LiveHandlebarTracker, fix: LeanGnssRecord) =
            tracker.evaluate(calibration, fix, imu, fix.elapsedNanos + 500_000_000)
        for ((reason, fix) in listOf(
            LiveHandlebarReason.GNSS_SOURCE_CHANGED to fixes[1].copy(provider = "fused"),
            LiveHandlebarReason.GNSS_SOURCE_UNSUPPORTED to fixes[1].copy(provider = "network"),
            LiveHandlebarReason.GNSS_RECEIPT_CLOCK to fixes[1].copy(singleFixReceivedElapsedNanos = fixes[1].elapsedNanos - 1),
            LiveHandlebarReason.GNSS_RECEIPT_CLOCK to fixes[1].copy(singleFixReceivedElapsedNanos = fixes[1].elapsedNanos + 500_000_001),
            LiveHandlebarReason.GNSS_RECEIPT_STALE to fixes[1].copy(singleFixReceivedElapsedNanos = fixes[1].elapsedNanos + 251_000_000),
            LiveHandlebarReason.GNSS_TIME_UNCERTAIN to fixes[1].copy(elapsedUncertaintyNanos = 101_000_000.0),
            LiveHandlebarReason.GNSS_TIME_UNCERTAIN to fixes[1].copy(invalidElapsedUncertainty = true),
            LiveHandlebarReason.GNSS_TIME_UNCERTAIN to fixes[1].copy(elapsedUncertaintyNanos = 1_000_000.0),
            LiveHandlebarReason.GNSS_CLOCK to fixes[1].copy(utcMillis = fixes[1].utcMillis + 81L),
            LiveHandlebarReason.GNSS_FIX_CONTINUITY to fixes[0],
            LiveHandlebarReason.GNSS_FIX_CONTINUITY to fixes[2],
        )) {
            val tracker = LiveHandlebarTracker()
            evaluate(tracker, fixes[0])
            assertEquals(reason.name, reason, evaluate(tracker, fix).reason())
        }
        val drifting = LiveHandlebarTracker()
        for (i in 0..2) evaluate(drifting, fixes[i].copy(
            singleFixReceivedElapsedNanos = fixes[i].elapsedNanos + 50_000_000 + i * 30_000_000))
        assertEquals(LiveHandlebarReason.GNSS_RECEIPT_JITTER,
            evaluate(drifting, fixes[3].copy(singleFixReceivedElapsedNanos = fixes[3].elapsedNanos + 140_000_000)).reason())
        val utcDrift = LiveHandlebarTracker()
        for (i in 0..2) evaluate(utcDrift, fixes[i].copy(utcMillis = fixes[i].utcMillis + i * 60L))
        assertEquals(LiveHandlebarReason.GNSS_CLOCK,
            evaluate(utcDrift, fixes[3].copy(utcMillis = fixes[3].utcMillis + 180L)).reason())
    }

    @Test fun liveTrackerRejectsBrokenOrDisagreeingImuAndDoesNotBorrowFutureRows() {
        val records = withSingleFixTiming(ride())
        val fixes = records.filterIsInstance<LeanGnssRecord>()
        val imu = records.filterIsInstance<LeanImuRecord>()
        val end = fixes[5].elapsedNanos
        val from = fixes[4].elapsedNanos
        for ((reason, rows) in listOf(
            LiveHandlebarReason.SENSOR_STREAM_GAP to imu.filterNot { it.elapsedNanos in from..<end },
            LiveHandlebarReason.SENSOR_QUALITY to imu.map {
                if (it.elapsedNanos in from..<end) it.copy(accuracy = 0) else it
            },
            LiveHandlebarReason.SENSOR_DISAGREEMENT to imu.map {
                if (it.elapsedNanos in from..<end && it.kind == LeanSensorKind.GYROSCOPE) it.copy(z = .04 - it.z) else it
            },
        )) {
            val tracker = LiveHandlebarTracker()
            fun decide(index: Int) =
                tracker.evaluate(calibration, fixes[index], imu, fixes[index].elapsedNanos + 60_000_000)
            repeat(4) { decide(it) }
            assertTrue(decide(4) is LiveHandlebarDecision.Accepted)
            assertEquals(reason, tracker.evaluate(calibration, fixes[5], rows, end + 60_000_000).reason())
            for (index in 6..8) assertEquals(LiveHandlebarReason.TURN_SUPPORT, decide(index).reason())
            assertTrue(decide(9) is LiveHandlebarDecision.Accepted)
        }
    }

    @Test fun liveTrackerRebuildsTheChainOnTurnReversalAndKeepsMeasuredStraightZero() {
        val records = withSingleFixTiming(ride(yawAt = { if (it < 4.0) .30 else -.30 }))
        val fixes = records.filterIsInstance<LeanGnssRecord>()
        val imu = records.filterIsInstance<LeanImuRecord>()
        val tracker = LiveHandlebarTracker()
        fun decide(i: Int) = tracker.evaluate(calibration, fixes[i], imu, fixes[i].elapsedNanos + 60_000_000)
        repeat(4) { decide(it) }
        assertTrue((decide(4) as LiveHandlebarDecision.Accepted).signedDegrees > 0)
        assertEquals(LiveHandlebarReason.TURN_DIRECTION_CHANGED, decide(5).reason())
        for (index in 6..8) assertEquals(LiveHandlebarReason.TURN_SUPPORT, decide(index).reason())
        assertTrue((decide(9) as LiveHandlebarDecision.Accepted).signedDegrees < 0)

        val straight = ride(yaw = 0.0)
        val (first, last) = straight.filterIsInstance<LeanGnssRecord>()
        val rows = straight.filterIsInstance<LeanImuRecord>()
        tracker.reset()
        tracker.evaluate(calibration, first, rows, first.elapsedNanos)
        val measured = tracker.evaluate(calibration, last, rows, last.elapsedNanos) as LiveHandlebarDecision.Accepted
        assertEquals(LiveHandlebarSource.MEASURED, measured.source)
        assertEquals(0.0, measured.signedDegrees, 0.0)

        val withoutUncertainty = withSingleFixTiming(straight).filterIsInstance<LeanGnssRecord>()
        tracker.reset()
        tracker.evaluate(calibration, withoutUncertainty[0], rows, first.elapsedNanos + 60_000_000)
        assertEquals(LiveHandlebarReason.GNSS_MOTION,
            tracker.evaluate(calibration, withoutUncertainty[1], rows, last.elapsedNanos + 60_000_000).reason())
    }

    private fun LiveHandlebarDecision.reason() = (this as LiveHandlebarDecision.Rejected).reason

    @Test fun reportedTurnNeedsIndependentSignedGpsAndImuWithSingleFixTiming() {
        for (provider in listOf("fused", "gps")) for (direction in listOf(-1.0, 1.0)) {
            val fixture = ride(yaw = .30 * direction)
            val result = estimate(withSingleFixTiming(fixture, provider))
            val curve = result.curves.single()
            assertTrue(curve.timingEstimated)
            assertTrue("HANDLEBAR_TIMING_ESTIMATED" in result.partialReasons)
            assertTrue(result.validMillis >= 4_000L)
            val expectedPeak = round(Math.toDegrees(atan(12.0 * .30 / g)))
            assertEquals(expectedPeak, requireNotNull(if (direction < 0) curve.leftDegrees else curve.rightDegrees), 0.0)
            assertNull(if (direction < 0) curve.rightDegrees else curve.leftDegrees)

            val strict = estimate(fixture)
            assertFalse(strict.curves.single().timingEstimated)
            assertFalse("HANDLEBAR_TIMING_ESTIMATED" in strict.partialReasons)
            assertNotEquals(strict.algorithmVersion, "handlebar-gnss-imu-v1")
        }
    }

    @Test fun timingFallbackRejectsMissingOrTaintedArrivalAndDifferentSources() {
        val fixture = withSingleFixTiming(ride())
        val cases: Map<String, (LeanGnssRecord) -> LeanGnssRecord> = mapOf(
            "no callback witness" to { it.copy(singleFixReceivedElapsedNanos = null) },
            "same callback for a batch" to { it.copy(singleFixReceivedElapsedNanos = start + 2 * second) },
            "stale callback" to { it.copy(singleFixReceivedElapsedNanos = it.elapsedNanos + 251_000_000L) },
            "future measurement" to { it.copy(singleFixReceivedElapsedNanos = it.elapsedNanos - 1) },
            "callback jitter" to { it.copy(singleFixReceivedElapsedNanos = it.elapsedNanos +
                if (it.elapsedNanos / second % 2 == 0L) 170_000_000L else 50_000_000L) },
            "source changes" to { it.copy(provider = if (it.elapsedNanos / second % 2 == 0L) "gps" else "fused") },
            "unsupported source" to { it.copy(provider = "network") },
            "non-null poor uncertainty" to { it.copy(elapsedUncertaintyNanos = 101_000_000.0) },
            "reported invalid uncertainty" to { it.copy(invalidElapsedUncertainty = true) },
            "mixed present and missing uncertainty" to { fix -> fix.copy(elapsedUncertaintyNanos =
                1_000_000.0.takeIf { fix.elapsedNanos / second % 2 == 0L }) },
            "UTC anchor" to { it.copy(utcMillis = it.utcMillis + 151L) },
            "UTC changes during pair" to { it.copy(utcMillis = it.utcMillis +
                if (it.elapsedNanos / second % 2 == 0L) 100L else 0L) },
        )
        for ((name, change) in cases) {
            val result = estimate(fixture.map { if (it is LeanGnssRecord) change(it) else it })
            assertTrue(name, result.curves.isEmpty())
            assertEquals(name, 0L, result.validMillis)
            assertFalse(name, "HANDLEBAR_TIMING_ESTIMATED" in result.partialReasons)
        }
    }

    @Test fun timingFallbackRequiresMeasuredFieldQualityAndSignedMotion() {
        val fixture = withSingleFixTiming(ride())
        val poor = listOf(
            withSingleFixTiming(ride(yaw = 0.0)),
            withSingleFixTiming(ride(speed = 6.0)),
            withSingleFixTiming(ride(speed = 0.0)),
            fixture.map { if (it is LeanGnssRecord) it.copy(bearingDegrees = null) else it },
            fixture.map { if (it is LeanGnssRecord) it.copy(bearingAccuracyDegrees = 4.1) else it },
            fixture.map { if (it is LeanGnssRecord) it.copy(speedAccuracyMps = .66) else it },
            fixture.map { if (it is LeanGnssRecord) it.copy(accuracyMeters = 5.1) else it },
            fixture.map { if (it is LeanGnssRecord) it.copy(latitude = 0.0, longitude = 0.0) else it },
            fixture.map { if (it is LeanGnssRecord) it.copy(mock = true) else it },
            fixture.map { if (it is LeanImuRecord && it.kind == LeanSensorKind.GYROSCOPE)
                it.copy(y = -it.y, z = .04 - it.z) else it },
            fixture.map { if (it is LeanImuRecord && it.kind == LeanSensorKind.ACCELEROMETER) {
                val tilt = Math.toRadians(20.0)
                it.copy(y = it.z * sin(tilt), z = it.z * cos(tilt))
            } else it },
        )
        for ((index, rows) in poor.withIndex()) {
            val result = estimate(rows)
            assertTrue("case=$index", result.curves.isEmpty())
            assertEquals("case=$index", 0L, result.validMillis)
            assertFalse("case=$index", "HANDLEBAR_TIMING_ESTIMATED" in result.partialReasons)
        }
    }

    @Test fun chronologicalImuPresenceDoesNotReplaceSignedTurnMagnitude() {
        val weakerGyro = ride().map {
            if (it is LeanImuRecord && it.kind == LeanSensorKind.GYROSCOPE) it.copy(z = .02 + (it.z - .02) * .75)
            else it
        }
        assertEquals(1, estimate(weakerGyro).curves.size)
        val result = estimate(withSingleFixTiming(weakerGyro))
        assertTrue(result.curves.isEmpty())
        assertEquals(0L, result.validMillis)
        assertTrue("SENSOR_DISAGREEMENT" in result.partialReasons)
        assertFalse("HANDLEBAR_TIMING_ESTIMATED" in result.partialReasons)
    }

    @Test fun timingFallbackDoesNotJoinAcrossCutOrCountAnUnpublishedRun() {
        val fixture = withSingleFixTiming(ride())
        val midpoint = start + 6 * second
        val split = estimate(fixture.filterNot { it is LeanImuRecord && abs(it.elapsedNanos - midpoint) <= 60_000_000L })
        assertEquals(2, split.curves.size)
        assertTrue(split.curves.all { it.timingEstimated &&
            (it.endElapsedNanos <= midpoint || it.startElapsedNanos >= midpoint) })
        val short = estimate(fixture.filterNot { it is LeanGnssRecord &&
            (it.elapsedNanos - start) / second !in setOf(0L, 1L, 2L, 3L, 6L, 7L, 8L, 9L) })
        assertTrue(short.curves.isEmpty())
        assertEquals(0L, short.validMillis)
        assertTrue("CURVE_TOO_SHORT" in short.partialReasons)
        assertFalse("HANDLEBAR_TIMING_ESTIMATED" in short.partialReasons)

        val lateFixture = withSingleFixTiming(ride(turnEnds = 8.0))
        val late = estimate(lateFixture + lateFixture.first { it is LeanGnssRecord })
        assertTrue("LATE_RECORD" in late.partialReasons)
        assertFalse("HANDLEBAR_TIMING_ESTIMATED" in late.partialReasons)
        assertEquals(0L, late.validMillis)
    }

    @Test fun timingFallbackRejectsSpacingThatOnlyFitsTheRegularEstimatorWindow() {
        val fixture = withSingleFixTiming(ride())
        for (scale in listOf(7L, 13L)) {
            val stretched = fixture.map {
                val offset = (it.elapsedNanos - start) * scale / 10
                when (it) {
                    is LeanImuRecord -> it.copy(elapsedNanos = start + offset)
                    is LeanGnssRecord -> it.copy(elapsedNanos = start + offset, utcMillis = utc + offset / 1_000_000L,
                        singleFixReceivedElapsedNanos = start + offset + 50_000_000L)
                }
            }
            val result = estimate(stretched, session().copy(endedAtElapsedNanos = start + 16 * second))
            assertTrue("scale=$scale", result.curves.isEmpty())
            assertEquals(0L, result.validMillis)
            assertTrue(requireNotNull(result.gnssDiagnostics).issues.any { it.code == "GNSS_TIME_UNCERTAINTY_MISSING" })
        }
    }

    @Test fun measuredAndEstimatedSupportsNeverFormOneCurve() {
        val midpoint = start + 6 * second
        val fixture = withSingleFixTiming(ride()).map {
            if (it is LeanGnssRecord && it.elapsedNanos < midpoint) {
                it.copy(elapsedUncertaintyNanos = 1_000_000.0, singleFixReceivedElapsedNanos = null)
            } else it
        }
        val (strict, estimated) = estimate(fixture).curves.also { assertEquals(2, it.size) }
        assertFalse(strict.timingEstimated)
        assertTrue(estimated.timingEstimated)
        assertTrue(strict.endElapsedNanos < estimated.startElapsedNanos)
    }

    @Test fun singleFixTimingDoesNotEnableLiveOrBodyEstimation() {
        val timed = withSingleFixTiming(ride())
        val (first, last) = timed.filterIsInstance<LeanGnssRecord>().take(2)
        val window = timed.filterIsInstance<LeanImuRecord>().filter { it.elapsedNanos in first.elapsedNanos..<last.elapsedNanos }
        for (mount in mounts) assertNull(HandlebarLeanEstimator.liveDegrees(mount, first, last, window))
        val body = LeanEstimatorV1().estimate(session(mounts.last()), timed.asSequence()) {}
        assertEquals(0L, body.validMillis)
        assertTrue("GNSS_QUALITY" in body.partialReasons)
        assertFalse("HANDLEBAR_TIMING_ESTIMATED" in body.partialReasons)
    }

    private val mounts = listOf(calibration, calibration.copy(epoch = "body", bodyConfirmed = true, steeringAxis = emptyList()))

    private data class LiveWindow(val first: LeanGnssRecord, val last: LeanGnssRecord, val imu: List<LeanImuRecord>) {
        fun degrees(reference: LeanCalibration) = HandlebarLeanEstimator.liveDegrees(reference, first, last, imu)
    }

    private fun liveWindow(speed: Double = 12.0, yaw: Double = .30, steeringRate: Double = 0.0): LiveWindow {
        val records = ride(speed, yaw, steeringRate)
        val (first, last) = records.filterIsInstance<LeanGnssRecord>().take(2)
        return LiveWindow(first, last, records.filterIsInstance<LeanImuRecord>()
            .filter { it.elapsedNanos in first.elapsedNanos..<last.elapsedNanos })
    }

    @Test fun liveWindowReturnsSignedAnglesAndStraightZeroOnBothMounts() {
        // Synthetic coordinated-turn expectations in degrees at the supplied speed and yaw rate.
        val cases = listOf(Triple(12.0, -.30, -20.158), Triple(12.0, .30, 20.158),
            Triple(4.0, -.45, -10.401), Triple(4.0, .45, 10.401), Triple(12.0, 0.0, 0.0))
        for ((speed, yaw, expected) in cases) {
            val window = liveWindow(speed, yaw)
            for (mount in mounts) {
                val actual = window.degrees(mount)
                assertNotNull("mount=${mount.epoch} speed=$speed yaw=$yaw", actual)
                assertEquals(expected, requireNotNull(actual), .01)
            }
        }
    }

    @Test fun highRateRollingWindowKeepsEverySupportedTurnAndBoundsMemory() {
        for (hz in listOf(100, 200, 1000)) {
            val buffer = LiveLeanSampleWindow()
            var previous: LeanGnssRecord? = null
            var intervals = 0
            for (row in ride(sensorHz = hz)) when (row) {
                is LeanImuRecord -> buffer.add(row)
                is LeanGnssRecord -> {
                    val samples = buffer.before(row.elapsedNanos)
                    assertTrue(samples.size <= LiveLeanSampleWindow.MAX_RECORDS)
                    previous?.let { first ->
                        for (mount in mounts) {
                            val angle = HandlebarLeanEstimator.liveDegrees(mount, first, row, samples)
                            if (hz <= 200) assertEquals("interval=$intervals Hz=$hz", 20.158, requireNotNull(angle), .01)
                            else assertNull("Incomplete interval at $hz Hz", angle)
                        }
                        intervals++
                    }
                    previous = row
                }
            }
            assertEquals(12, intervals)
        }
    }

    @Test fun delayedFixUsesOnlyImuUpToItsTimestamp() {
        val rows = ride(sensorHz = 200)
        val fixes = rows.filterIsInstance<LeanGnssRecord>()
        val buffer = LiveLeanSampleWindow()
        rows.filterIsInstance<LeanImuRecord>().takeWhile { it.elapsedNanos <= start + 4_250_000_000L }.forEach(buffer::add)
        val first = fixes[2]
        val last = fixes[3]
        val atFix = buffer.before(last.elapsedNanos)
        assertTrue(atFix.all { it.elapsedNanos < last.elapsedNanos })
        for (kind in LeanSensorKind.entries) {
            assertEquals(200, atFix.count { it.kind == kind && it.elapsedNanos >= first.elapsedNanos })
        }
        for (mount in mounts) {
            assertEquals(20.158, requireNotNull(HandlebarLeanEstimator.liveDegrees(mount, first, last, atFix)), .01)
            assertNull(HandlebarLeanEstimator.liveDegrees(mount, fixes[0], fixes[1], buffer.before(fixes[1].elapsedNanos)))
        }
    }

    @Test fun liveWindowRejectsStaleImuAndGnssOrImuOutliersOnBothMounts() {
        val window = liveWindow()
        val mid = window.first.elapsedNanos + second / 2
        val stale = window.copy(imu = window.imu.map { it.copy(elapsedNanos = it.elapsedNanos - second) })
        val lateFix = window.copy(last = window.last.copy(elapsedNanos = window.first.elapsedNanos + 2 * second,
            utcMillis = window.first.utcMillis + 2000))
        val badPosition = window.copy(last = window.last.copy(latitude = window.last.latitude + .01))
        val gyroSpike = window.copy(imu = window.imu.map {
            if (it.elapsedNanos == mid && it.kind == LeanSensorKind.GYROSCOPE) it.copy(x = it.x + 1.0) else it
        })
        val forceSpike = window.copy(imu = window.imu.map {
            if (it.elapsedNanos == mid && it.kind == LeanSensorKind.ACCELEROMETER) it.copy(z = it.z + 8.0) else it
        })
        for (mount in mounts) {
            for ((reason, sample) in mapOf("stale IMU" to stale, "late fix" to lateFix, "position" to badPosition,
                "gyro spike" to gyroSpike, "force spike" to forceSpike)) {
                assertNull("${mount.epoch} $reason", sample.degrees(mount))
            }
        }
    }

    @Test fun liveWindowRejectsParkedAndMovingSteeringOnBothMounts() {
        val parked = liveWindow(speed = 0.0, yaw = 0.0, steeringRate = 1.2)
        val moving = liveWindow(steeringRate = .60)
        for (mount in mounts) {
            assertNull("${mount.epoch} parked", parked.degrees(mount))
            assertNull("${mount.epoch} moving", moving.degrees(mount))
        }
    }

    @Test fun liveWindowRequiresMeasuredQualityAtEachFixAndReliableImuOnBothMounts() {
        val window = liveWindow()
        fun withoutQuality(fix: LeanGnssRecord) = listOf(fix.copy(accuracyMeters = null),
            fix.copy(speedAccuracyMps = null), fix.copy(bearingAccuracyDegrees = null),
            fix.copy(elapsedUncertaintyNanos = null))
        for (mount in mounts) {
            for (first in withoutQuality(window.first)) assertNull(window.copy(first = first).degrees(mount))
            for (last in withoutQuality(window.last)) assertNull(window.copy(last = last).degrees(mount))
            for (kind in LeanSensorKind.entries) {
                val unreliable = window.imu.map { if (it.kind == kind) it.copy(accuracy = 0) else it }
                assertNull("${mount.epoch} $kind", window.copy(imu = unreliable).degrees(mount))
            }
        }
    }

    @Test fun parkedSteeringDoesNotMakeCurvesOrInventPeaks() {
        val result = estimate(ride(speed = 0.0, yaw = .5, steeringRate = 1.2))
        assertTrue(result.curves.isEmpty())
        assertNull(result.leftDegrees)
        assertNull(result.rightDegrees)
        assertEquals(0L, result.validMillis)
        assertTrue("PARKED_STEERING_OR_STATIONARY" in result.partialReasons)
    }

    @Test fun aStopBeforeAMovingCurveDoesNotAddAParkedSessionReason() {
        val result = estimate(ride(turnStarts = 4.0, stationaryUntil = 4.0))
        assertEquals(1, result.curves.size)
        assertTrue(result.validMillis > 0)
        assertFalse("PARKED_STEERING_OR_STATIONARY" in result.partialReasons)
    }

    @Test fun missingQualityOrPositionDisplacementDoesNotProveStationarity() {
        val parked = ride(speed = 0.0, yaw = 0.0)
        val missing = estimate(parked.map {
            if (it is LeanGnssRecord) it.copy(speedAccuracyMps = null) else it
        })
        assertTrue("GNSS_QUALITY" in missing.partialReasons)
        assertFalse("PARKED_STEERING_OR_STATIONARY" in missing.partialReasons)

        val conflict = estimate(parked.map {
            if (it is LeanGnssRecord && it.elapsedNanos >= start + 5 * second) it.copy(latitude = .0001) else it
        })
        assertTrue("GNSS_MOTION_DISAGREEMENT" in conflict.partialReasons)
        assertFalse("PARKED_STEERING_OR_STATIONARY" in conflict.partialReasons)
        assertTrue(conflict.curves.isEmpty())
    }

    @Test fun lowSpeedAndFlowingArcsSupportBothDirectionsAcrossHeadingWrap() {
        for ((speed, rate, peak) in listOf(Triple(4.0, .45, 10.0), Triple(12.0, .30, 20.0))) {
            for (direction in listOf(-1, 1)) {
                val result = estimate(ride(speed, direction * rate, turnStarts = 2.0, turnEnds = 10.0))
                assertEquals("speed=$speed direction=$direction reasons=${result.partialReasons}", 1, result.curves.size)
                val curve = result.curves.single()
                assertEquals(start + 2 * second, curve.startElapsedNanos)
                assertEquals(start + 10 * second, curve.endElapsedNanos)
                assertEquals(utc + 2000, curve.startUtcMillis)
                assertEquals(utc + 10_000, curve.endUtcMillis)
                val degrees = if (direction < 0) curve.leftDegrees else curve.rightDegrees
                assertEquals(peak, requireNotNull(degrees), 1.0)
                assertEquals(degrees, if (direction < 0) result.leftDegrees else result.rightDegrees)
                assertNull(if (direction < 0) result.rightDegrees else result.leftDegrees)
                assertTrue(curve.latitude != null && curve.longitude != null)
                assertTrue(result.validMillis in 8000..result.observedMillis)
                assertEquals(HandlebarLeanEstimator.VERSION, result.algorithmVersion)
            }
        }
    }

    @Test fun independentSignalsMustAgreeEvenWhenGnssAloneLooksLikeACurve() {
        val mixed = ride().map {
            if (it is LeanImuRecord && it.kind == LeanSensorKind.GYROSCOPE) it.copy(y = 0.0, z = .02) else it
        }
        val result = estimate(mixed)
        assertTrue(result.curves.isEmpty())
        assertTrue(result.partialReasons.contains("SENSOR_DISAGREEMENT"))
        assertEquals(0L, result.validMillis)
    }

    @Test fun movingButSteeringAndPositionDisagreementAreVetoes() {
        val steering = estimate(ride(steeringRate = .60))
        assertTrue(steering.curves.isEmpty())
        assertTrue(steering.partialReasons.contains("STEERING_OR_MOTION_VETO"))
        val parkedPosition = estimate(ride().map {
            if (it is LeanGnssRecord) it.copy(latitude = 0.0, longitude = 0.0) else it
        })
        assertTrue(parkedPosition.curves.isEmpty())
        assertTrue(parkedPosition.partialReasons.contains("GNSS_MOTION_DISAGREEMENT"))
    }

    @Test fun gnssAndImuHolesSplitCurvesAndDoNotCountTowardCoverage() {
        val result = estimate(ride().filter {
            !(it is LeanGnssRecord && it.elapsedNanos == start + 5 * second) &&
                !(it is LeanImuRecord && it.elapsedNanos in start + 8 * second..start + 8 * second + 200_000_000L)
        })
        assertTrue(result.curves.isNotEmpty())
        assertTrue(result.curves.none {
            it.startElapsedNanos < start + 5 * second && it.endElapsedNanos > start + 5 * second ||
                it.startElapsedNanos <= start + 8 * second && it.endElapsedNanos > start + 8 * second
        })
        assertTrue(result.validMillis < result.observedMillis)
        assertTrue("DATA_GAP" in result.partialReasons)
    }

    @Test fun isolatedHighYawIntervalOutsideAnAcceptedCurveDoesNotSetTripPeak() {
        val result = estimate(ride(yaw = .7, turnStarts = 4.0, turnEnds = 5.0))
        assertTrue(result.curves.isEmpty())
        assertNull(result.rightDegrees)
        assertTrue("CURVE_TOO_SHORT" in result.partialReasons)
    }

    @Test fun rejectsMissingQualityClockShiftAndUnalignedImuInsteadOfGuessing() {
        val fixture = ride()
        val missing = estimate(fixture.map { if (it is LeanGnssRecord) it.copy(bearingAccuracyDegrees = null) else it })
        assertTrue("GNSS_QUALITY" in missing.partialReasons)
        val shifted = estimate(fixture.map { if (it is LeanGnssRecord) it.copy(utcMillis = it.utcMillis + 60000) else it })
        assertTrue("CLOCK_DISCONTINUITY" in shifted.partialReasons)
        val unaligned = estimate(fixture.filter {
            it !is LeanImuRecord || it.elapsedNanos % second in 160_000_000L..860_000_000L
        })
        assertTrue("IMU_ALIGNMENT" in unaligned.partialReasons)
        for (result in listOf(missing, shifted, unaligned)) {
            assertNull(result.rightDegrees)
            assertTrue(result.curves.isEmpty())
            assertEquals(0L, result.validMillis)
        }
    }

    @Test fun boundedArrivalReorderingDoesNotChangeTheResult() {
        val fixture = ride()
        val scrambled = fixture.chunked(70).flatMap { it.reversed() }
        assertEquals(estimate(fixture), estimate(scrambled))
    }

    @Test fun diagnosableQualityFieldsCountOncePerRejectedPair() {
        val fixes = ride().filterIsInstance<LeanGnssRecord>().take(2).map {
            it.copy(bearingAccuracyDegrees = null, bearingDegrees = 360.0)
        }
        val result = estimate(fixes)
        assertTrue("GNSS_QUALITY" in result.partialReasons)
        assertEquals(setOf(
            LeanGnssIssue("GNSS_BEARING_ACCURACY_MISSING", "pair", 1, second),
            LeanGnssIssue("GNSS_BEARING_OUT_OF_RANGE", "pair", 1, second),
        ), requireNotNull(result.gnssDiagnostics).issues.toSet())
        assertEquals(0L, result.validMillis)
    }

    @Test fun gapFrequencyAndClockRetainPrecedenceOverEndpointQuality() {
        val (first, last) = ride().filterIsInstance<LeanGnssRecord>().take(2)
        val cases = listOf(
            Triple("GNSS_DATA_GAP", 2 * second, last.utcMillis),
            Triple("GNSS_RATE_UNSUITABLE", second / 4, last.utcMillis),
            Triple("CLOCK_DISCONTINUITY", second, last.utcMillis + 1_500),
        )
        for ((code, interval, atUtc) in cases) {
            val result = estimate(listOf(first, last.copy(elapsedNanos = first.elapsedNanos + interval,
                utcMillis = atUtc, speedAccuracyMps = null)))
            assertEquals(listOf(LeanGnssIssue(code, "pair", 1, interval)),
                requireNotNull(result.gnssDiagnostics).issues)
        }
    }

    @Test fun warningsUseMeasuredMovementAndMountSpecificChecks() {
        val (first, last) = ride().filterIsInstance<LeanGnssRecord>().take(2)
        val noBearing = last.copy(bearingAccuracyDegrees = null)
        assertEquals(leanGnssIssueLabel("GNSS_BEARING_ACCURACY_MISSING"),
            leanMovingGnssHint(first, noBearing, LeanMount.HANDLEBAR))
        assertNull(leanMovingGnssHint(first, noBearing, LeanMount.BODY))
        val missingSpeedAccuracy = noBearing.copy(speedAccuracyMps = null)
        assertEquals(leanGnssIssueLabel("GNSS_SPEED_ACCURACY_MISSING"),
            leanMovingGnssHint(first, missingSpeedAccuracy, LeanMount.BODY))
        assertEquals(leanGnssIssueLabel("GNSS_DUPLICATE_TIME"),
            leanMovingGnssHint(first, last.copy(elapsedNanos = first.elapsedNanos), LeanMount.HANDLEBAR))
        assertNull(leanMovingGnssHint(first.copy(speedMps = 0.0), missingSpeedAccuracy.copy(speedMps = 0.0), LeanMount.HANDLEBAR))
        assertNull(leanMovingGnssHint(first, missingSpeedAccuracy, LeanMount.UNKNOWN))
    }

    @Test fun conflictingFixesAtOneMeasurementTimeCannotSupportEitherAdjacentInterval() {
        val at = start + 6 * second
        val original = ride()
        val duplicate = original.filterIsInstance<LeanGnssRecord>().single { it.elapsedNanos == at }
            .copy(bearingDegrees = 180.0)
        val index = original.indexOfFirst { it is LeanGnssRecord && it.elapsedNanos == at }
        val sequence = original.take(index) + duplicate + original.drop(index)
        val result = estimate(sequence)
        assertTrue("GNSS_DUPLICATE_TIME" in result.partialReasons)
        assertEquals(listOf(LeanGnssIssue("GNSS_DUPLICATE_TIME", "fix", 2, 0)),
            requireNotNull(result.gnssDiagnostics).issues)
        assertEquals(10_000L, result.validMillis)
        assertTrue(result.curves.none { it.startElapsedNanos < at && it.endElapsedNanos > at })
        assertEquals(result, estimate(sequence.chunked(70).flatMap { it.reversed() }))
    }

    @Test fun gapAcrossGnssBoundaryCannotJoinTwoIndividuallyAlignedImuSlices() {
        val boundary = start + 5 * second
        val input = ride().filterNot {
            it is LeanImuRecord && it.elapsedNanos in boundary - 40_000_000L..boundary + 40_000_000L
        }
        val result = estimate(input)
        assertTrue("DATA_GAP" in result.partialReasons)
        assertTrue(result.curves.none { it.startElapsedNanos < boundary && it.endElapsedNanos > boundary })
        assertTrue(result.validMillis < result.observedMillis)
    }

    @Test fun invalidReferenceStopsBeforeReadingAndCancellationPropagates() {
        val input = session(calibration.copy(steeringAxis = listOf(0.0, 0.0, 0.0)))
        val result = HandlebarLeanEstimator().estimate(input, sequence { error("input consumed") }) {}
        assertTrue("INVALID_SESSION_OR_CALIBRATION" in result.partialReasons)
        assertThrows(IllegalStateException::class.java) {
            HandlebarLeanEstimator().estimate(session(), ride().asSequence()) { error("paused") }
        }
    }

    @Test fun aMalformedEndlessStreamHitsTheBufferBound() {
        var read = 0
        val repeated = generateSequence {
            read++
            LeanImuRecord(LeanSensorKind.GYROSCOPE, 0, start, 0.0, 0.0, .02, 3)
        }
        val result = HandlebarLeanEstimator().estimate(session(), repeated) {}
        assertTrue(read <= 4097)
        assertTrue("WINDOW_RECORD_LIMIT" in result.partialReasons)
        assertTrue(result.curves.isEmpty())
    }
}
