package org.iz.navigation

import java.util.concurrent.CancellationException
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.RecordedCurveDetail
import org.iz.navigation.data.RecordedCurveDirection
import org.iz.navigation.data.RecordedCurves
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class RecordedCurveDetailsTest {
    @Test fun connectedRightTurnKeepsActualEndpointsTimeAndSampledSpeeds() {
        // North -> north-east -> east: two clockwise turns, about 113.89 m in 6 s.
        val details = RecordedCurves.prepare(journey(), rightTurn(), FINGERPRINT_A)
        assertEquals("recorded-trip", details.journeyId)
        assertEquals(FINGERPRINT_A, details.sourceFingerprint)
        assertEquals(1, details.algorithmVersion)
        assertEquals(4, details.usablePointCount)
        assertEquals(0, details.excludedIntervalCount)
        assertEquals(1, details.curves.size)
        val curve = details.curves.single()
        assertTrue("A selectable curve needs a stable nonblank ID", curve.id.isNotBlank())
        assertEquals("recorded-trip", curve.journeyId)
        assertEquals(FINGERPRINT_A, curve.sourceFingerprint)
        assertEquals(1, curve.algorithmVersion)
        assertEquals(RecordedCurveDirection.RIGHT, curve.direction)
        assertEquals("fix-a", curve.start.pointId)
        assertEquals(1_000L, curve.start.at)
        assertEquals(0.0, curve.start.latitude, 0.0)
        assertEquals(0.0, curve.start.longitude, 0.0)
        assertEquals("fix-d", curve.end.pointId)
        assertEquals(7_000L, curve.end.at)
        assertEquals(0.0006, curve.end.latitude, 0.0)
        assertEquals(0.0006, curve.end.longitude, 0.0)
        assertEquals(4, curve.pointCount)
        assertEquals(113.89, curve.recordedDistanceMeters, 0.05)
        // Sampled m/s values 1, null, 3, 2 become 3.6..10.8 km/h; not geometry-derived speed.
        assertEquals(3.6, curve.sampledMinSpeedKmh!!, 0.0001)
        assertEquals(10.8, curve.sampledMaxSpeedKmh!!, 0.0001)
    }

    @Test fun reflectedRecordedTurnIsLeftWithoutInventingDifferentEndpoints() {
        val left = listOf(
            point("left-a", 1_000, 0.0, 0.0),
            point("left-b", 3_000, 0.0003, 0.0),
            point("left-c", 5_000, 0.0006, -0.0003),
            point("left-d", 7_000, 0.0006, -0.0006),
        )
        val curve = singleCurve(left)
        assertEquals(RecordedCurveDirection.LEFT, curve.direction)
        assertEquals("left-a", curve.start.pointId)
        assertEquals("left-d", curve.end.pointId)
        assertEquals(1_000L, curve.start.at)
        assertEquals(7_000L, curve.end.at)
        assertEquals(-0.0006, curve.end.longitude, 0.0)
        assertEquals(4, curve.pointCount)
    }

    @Test fun missingSpeedSamplesStayUnknownEvenWhenCoordinatesProveMovement() {
        val curve = singleCurve(rightTurn().map { it.copy(speed = null) })
        assertEquals(RecordedCurveDirection.RIGHT, curve.direction)
        assertEquals(113.89, curve.recordedDistanceMeters, 0.05)
        assertNull(curve.sampledMinSpeedKmh)
        assertNull(curve.sampledMaxSpeedKmh)
    }

    @Test fun measuredZeroSpeedIsRetainedAlongsideMissingSamples() {
        val points = rightTurn().mapIndexed { index, p -> p.copy(speed = when (index) {
            0 -> 0f
            2 -> 2f
            else -> null
        }) }
        val curve = singleCurve(points)
        assertEquals(0.0, curve.sampledMinSpeedKmh!!, 0.0)
        assertEquals(7.2, curve.sampledMaxSpeedKmh!!, 0.0001)
    }

    @Test fun reorderingInputKeepsSelectionIdAndActualEndpointProvenance() {
        val points = rightTurn()
        val original = singleCurve(points)
        val reordered = singleCurve(listOf(points[2], points[0], points[3], points[1]))
        assertEquals("fix-a", reordered.start.pointId)
        assertEquals("fix-d", reordered.end.pointId)
        assertEquals(RecordedCurveDirection.RIGHT, reordered.direction)
        assertEquals(original.id, reordered.id)
        assertEquals(original, reordered)
    }

    @Test fun changedFingerprintInvalidatesTheOldCurveSelectionId() {
        val original = singleCurve(rightTurn())
        val changed = RecordedCurves.prepare(journey(), rightTurn(), FINGERPRINT_B)
        assertEquals(1, changed.curves.size)
        assertEquals(FINGERPRINT_B, changed.curves.single().sourceFingerprint)
        assertNotEquals(original.id, changed.curves.single().id)
    }

    @Test fun identicalGeometryInAnotherJourneyDoesNotReuseTheCurveId() {
        val first = singleCurve(rightTurn())
        val other = RecordedCurves.prepare(journey().copy(id = "another-trip"),
            rightTurn().map { it.copy(journeyId = "another-trip") }, FINGERPRINT_A)
        assertEquals(1, other.curves.size)
        assertEquals("another-trip", other.curves.single().journeyId)
        assertNotEquals(first.id, other.curves.single().id)
    }

    @Test fun straightRecordingDoesNotCreateACurveFromDistanceAlone() {
        val straight = listOf(
            point("a", 1_000, 0.0, 0.0), point("b", 3_000, 0.0003, 0.0),
            point("c", 5_000, 0.0006, 0.0), point("d", 7_000, 0.0009, 0.0),
        )
        assertTrue(RecordedCurves.prepare(journey(), straight, FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun shallowTwentyDegreeBendDoesNotReachTheCurveThreshold() {
        // Hand-checked headings approximately0,10,20 degrees: coherent motion, total turn <30 degrees.
        val shallow = listOf(
            point("a", 1_000, 0.0, 0.0), point("b", 3_000, 0.0003, 0.0),
            point("c", 5_000, 0.0005954423, 0.0000520945),
            point("d", 7_000, 0.0008773501, 0.0001547005),
        )
        assertTrue(RecordedCurves.prepare(journey(), shallow, FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun stationaryFixesCannotBecomeATurnEvenWithReportedSpeed() {
        val stationary = rightTurn().map { it.copy(latitude = 0.0, longitude = 0.0) }
        assertTrue(RecordedCurves.prepare(journey(), stationary, FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun alternatingHeadingNoiseIsNotAConsistentTurn() {
        // North -> north-east -> north reverses turn direction; summing absolute deltas is wrong.
        val noisy = listOf(
            point("a", 1_000, 0.0, 0.0), point("b", 3_000, 0.0003, 0.0),
            point("c", 5_000, 0.0006, 0.0003), point("d", 7_000, 0.0009, 0.0003),
        )
        assertTrue(RecordedCurves.prepare(journey(), noisy, FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun fewerThanFourRecordedPointsCannotProduceCurveDetail() {
        assertTrue(RecordedCurves.prepare(journey(), rightTurn().take(3), FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun subThreeSecondTurnIsRejectedButThreeSecondRecordingIsUsable() {
        val short = rightTurn().mapIndexed { index, p -> p.copy(recordedAt = 1_000L + index * 900L) }
        assertTrue(RecordedCurves.prepare(journey(), short, FINGERPRINT_A).curves.isEmpty())
        val boundary = rightTurn().mapIndexed { index, p -> p.copy(recordedAt = 1_000L + index * 1_000L) }
        val curve = singleCurve(boundary)
        assertEquals(1_000L, curve.start.at)
        assertEquals(4_000L, curve.end.at)
    }

    @Test fun eachLegMustReachTenMetersEvenWhenTotalExceedsTwentyMeters() {
        // Legs about 6.67, 9.44, 6.67 m; total22.78 m does not establish reliable per-leg headings.
        val shortLegs = listOf(
            point("a", 1_000, 0.0, 0.0, accuracy = 1f),
            point("b", 3_000, 0.00006, 0.0, accuracy = 1f),
            point("c", 5_000, 0.00012, 0.00006, accuracy = 1f),
            point("d", 7_000, 0.00012, 0.00012, accuracy = 1f),
        )
        assertTrue(RecordedCurves.prepare(journey(), shortLegs, FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun inaccurateEndpointsCannotSupplyReliableHeadingsForShorterLegs() {
        // Accuracy20 m requires >=40 m per leg; the two33.36 m legs fail despite accuracy <=25 m.
        assertTrue(RecordedCurves.prepare(journey(), rightTurn().map { it.copy(accuracy = 20f) },
            FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun accuracyTwentyFiveMetersIsAcceptedWhenEveryLegIsLongEnough() {
        val longLegs = listOf(
            point("a", 1_000, 0.0, 0.0, accuracy = 25f),
            point("b", 3_000, 0.0006, 0.0, accuracy = 25f),
            point("c", 5_000, 0.0012, 0.0006, accuracy = 25f),
            point("d", 7_000, 0.0012, 0.0012, accuracy = 25f),
        )
        val curve = singleCurve(longLegs)
        assertEquals(4, curve.pointCount)
        assertEquals(RecordedCurveDirection.RIGHT, curve.direction)
    }

    @Test fun invalidFixesAreBarriersRatherThanDroppedBridges() {
        val points = fivePointRightTurn()
        val invalidMiddle = listOf(
            points[2].copy(latitude = Double.NaN), points[2].copy(latitude = 91.0),
            points[2].copy(longitude = 181.0), points[2].copy(accuracy = Float.NaN),
            points[2].copy(accuracy = -1f), points[2].copy(accuracy = 25.1f),
            points[2].copy(speed = -1f), points[2].copy(speed = Float.NaN),
        )
        invalidMiddle.forEach { bad ->
            val input = points.toMutableList().also { it[2] = bad }
            assertTrue("Invalid middle fix must split the two two-point runs: $bad",
                RecordedCurves.prepare(journey(), input, FINGERPRINT_A).curves.isEmpty())
        }
    }

    @Test fun explicitBreakPreventsBridgingTwoShortRunsIntoOneTurn() {
        val broken = rightTurn().toMutableList().also { it[2] = it[2].copy(breakBefore = true) }
        assertTrue(RecordedCurves.prepare(journey(), broken, FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun fifteenSecondIntervalsAreUsableButALargerGapSplitsTheTurn() {
        val allowed = rightTurn().mapIndexed { index, p -> p.copy(recordedAt = 1_000L + index * 15_000L) }
        val completed = journey().copy(endedAt = 50_000)
        val prepared = RecordedCurves.prepare(completed, allowed, FINGERPRINT_A)
        assertEquals(1, prepared.curves.size)
        assertEquals(46_000L, prepared.curves.single().end.at)
        val gap = allowed.toMutableList().also {
            it[2] = it[2].copy(recordedAt = 31_001)
            it[3] = it[3].copy(recordedAt = 46_001)
        }
        assertTrue(RecordedCurves.prepare(completed, gap, FINGERPRINT_A).curves.isEmpty())
    }

    @Test fun foreignAndOutOfJourneyTimeFixesAreNotCurveEvidence() {
        val rejected = listOf(
            rightTurn().map { it.copy(journeyId = "foreign") },
            rightTurn().map { it.copy(recordedAt = it.recordedAt - 20_000) },
            rightTurn().map { it.copy(recordedAt = it.recordedAt + 20_000) },
        )
        rejected.forEach { points ->
            assertTrue(RecordedCurves.prepare(journey(), points, FINGERPRINT_A).curves.isEmpty())
        }
    }

    @Test fun activeAndUnconfirmedJourneysDoNotPublishPreparedCurveDetails() {
        listOf(journey().copy(endedAt = null), journey().copy(status = JourneyStatus.TEMPORARY)).forEach {
            assertTrue(RecordedCurves.prepare(it, rightTurn(), FINGERPRINT_A).curves.isEmpty())
        }
    }

    @Test fun cancellationIsPropagatedWithoutWrappingOrPublishingPartialResults() {
        val cancelled = CancellationException("analysis superseded")
        try {
            RecordedCurves.prepare(journey(), rightTurn(), FINGERPRINT_A) { throw cancelled }
            fail("A cancelled preparation must not return details")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun cancellationIsCheckedAgainWhileProcessingALongerRecording() {
        val cancelled = CancellationException("recording replaced")
        val points = (0..2_048).map { index ->
            point("long-$index", 1_000L + index * 2_000L, 0.0, index * 0.0003)
        }
        var checks = 0
        try {
            RecordedCurves.prepare(journey().copy(endedAt = 5_000_000), points, FINGERPRINT_A) {
                if (++checks == 2) throw cancelled
            }
            fail("Preparation must observe cancellation beyond its admission check")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    private fun singleCurve(points: List<TrackPoint>): RecordedCurveDetail {
        val result = RecordedCurves.prepare(journey(), points, FINGERPRINT_A)
        assertEquals("Hand-checked connected turn must produce exactly one detail", 1, result.curves.size)
        return result.curves.single()
    }

    private fun journey() = Journey(id = "recorded-trip", transport = Transport.MOTORCYCLE,
        status = JourneyStatus.CONFIRMED, startedAt = 0, endedAt = 10_000)

    private fun rightTurn() = listOf(
        point("fix-a", 1_000, 0.0, 0.0, speed = 1f),
        point("fix-b", 3_000, 0.0003, 0.0),
        point("fix-c", 5_000, 0.0006, 0.0003, speed = 3f),
        point("fix-d", 7_000, 0.0006, 0.0006, speed = 2f),
    )

    private fun fivePointRightTurn() = rightTurn() + point("fix-e", 9_000, 0.0003, 0.0009)

    private fun point(id: String, time: Long, latitude: Double, longitude: Double,
        accuracy: Float = 5f, speed: Float? = null) = TrackPoint(id = id, journeyId = "recorded-trip",
        latitude = latitude, longitude = longitude, recordedAt = time, accuracy = accuracy, speed = speed)

    private companion object {
        val FINGERPRINT_A = "a".repeat(64)
        val FINGERPRINT_B = "b".repeat(64)
    }
}
