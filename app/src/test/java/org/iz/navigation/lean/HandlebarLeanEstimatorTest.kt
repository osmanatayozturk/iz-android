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
        turnStarts: Double = 0.0, turnEnds: Double = 12.0, stationaryUntil: Double = 0.0): List<LeanRecord> = buildList {
        var north = 0.0
        var east = 0.0
        var course = 350.0
        for (i in 0..600) {
            val t = i / 50.0
            val sampleSpeed = if (t < stationaryUntil) 0.0 else speed
            val turn = if (t in turnStarts..<turnEnds) yaw else 0.0
            val bank = atan(sampleSpeed * turn / g)
            val ns = start + i * 20_000_000L
            // Right-positive course rotates clockwise around world up. Lean changes its projection in device coordinates.
            add(LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), ns,
                0.0, 0.0, hypot(g, sampleSpeed * turn), 3))
            add(LeanImuRecord(LeanSensorKind.GYROSCOPE, i.toLong(), ns,
                axis[0] * steeringRate, -turn * sin(bank), .02 - turn * cos(bank) + axis[2] * steeringRate, 3))
            if (i % 50 == 0) add(LeanGnssRecord(ns, utc + i * 20L, Math.toDegrees(north / 6_371_000),
                Math.toDegrees(east / 6_371_000), 1.0, sampleSpeed, (course % 360 + 360) % 360,
                .2, 1.0, 1_000_000.0, false, "gps"))
            val mid = Math.toRadians(course) + turn / 100.0
            north += sampleSpeed / 50 * cos(mid)
            east += sampleSpeed / 50 * sin(mid)
            course += Math.toDegrees(turn / 50.0)
        }
    }

    private fun estimate(records: Iterable<LeanRecord>, input: LeanInputSession = session()) =
        HandlebarLeanEstimator().estimate(input, records.asSequence()) {}

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

    @Test fun conflictingFixesAtOneMeasurementTimeCannotSupportEitherAdjacentInterval() {
        val at = start + 6 * second
        val original = ride()
        val duplicate = original.filterIsInstance<LeanGnssRecord>().single { it.elapsedNanos == at }
            .copy(bearingDegrees = 180.0)
        val index = original.indexOfFirst { it is LeanGnssRecord && it.elapsedNanos == at }
        val sequence = original.take(index) + duplicate + original.drop(index)
        val result = estimate(sequence)
        assertTrue("GNSS_DUPLICATE_TIME" in result.partialReasons)
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
