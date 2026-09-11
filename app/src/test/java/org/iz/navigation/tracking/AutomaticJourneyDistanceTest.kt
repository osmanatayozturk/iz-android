package org.iz.navigation.tracking

import org.iz.navigation.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class AutomaticJourneyDistanceTest {
    private fun point(meters: Double, time: Long, gap: Boolean = false, accuracy: Float = 5f) =
        TrackPoint(journeyId = "trip", latitude = Math.toDegrees(meters / 6_371_000.0), longitude = 0.0,
            recordedAt = time, accuracy = accuracy, breakBefore = gap)

    @Test fun qualifiesOnlyAfterFiveHundredMetersAndRetainsTheStart() {
        val distance = AutomaticJourneyDistance()
        for (i in 0..4) distance.observe(point(i * 100.0, i * 5_000L))
        distance.observe(point(499.0, 25_000))
        assertFalse(distance.qualified)
        distance.observe(point(510.0, 30_000))
        assertTrue(distance.qualified)
        assertEquals(510.0, distance.meters, 0.001)
    }

    @Test fun pauseAndReturnJourneyCountCumulativeTravelWithoutResetting() {
        val distance = AutomaticJourneyDistance()
        distance.observe(point(0.0, 0))
        distance.observe(point(260.0, 10_000))
        for (i in 1..20) distance.observe(point(260.0, 10_000 + i * 10_000L))
        assertFalse(distance.qualified)
        distance.observe(point(0.0, 220_000))
        assertTrue(distance.qualified)
        assertEquals(520.0, distance.meters, 0.001)
    }

    @Test fun gpsGapAndStationaryNoiseCannotSatisfyTheThreshold() {
        val distance = AutomaticJourneyDistance()
        distance.observe(point(0.0, 0))
        for (i in 1..1000) distance.observe(point((i % 2).toDouble(), i * 5_000L))
        distance.observe(point(1000.0, 5_005_000, gap = true))
        assertFalse(distance.qualified)
        assertEquals(0.0, distance.meters, 0.001)
        distance.observe(point(1100.0, 5_010_000))
        assertEquals(100.0, distance.meters, 0.001)
    }

    @Test fun persistedPointsRestoreProgressAndDuplicateFixesDoNotAddDistance() {
        val points = listOf(point(0.0, 0), point(250.0, 10_000))
        val distance = AutomaticJourneyDistance(points)
        distance.observe(points.last())
        distance.observe(point(510.0, 20_000))
        assertTrue(distance.qualified)
        assertEquals(510.0, distance.meters, 0.001)
    }

    @Test fun normalWalkingAccumulatesAcrossShortFixesWithinTheReportedAccuracy() {
        val distance = AutomaticJourneyDistance()
        for (i in 0..71) distance.observe(point(i * 7.0, i * 5_000L, accuracy = 20f))
        assertFalse(distance.qualified)
        distance.observe(point(504.0, 360_000, accuracy = 20f))
        assertTrue(distance.qualified)
        assertEquals(504.0, distance.meters, 0.001)
    }

    @Test fun stationaryOscillationWithinTheAccuracyRadiusNeverCountsAsTravel() {
        val points = (0..179).map { point(if (it % 2 == 0) -20.0 else 20.0, it * 5_000L, accuracy = 20f) }
        val distance = AutomaticJourneyDistance(points)
        assertFalse(distance.qualified)
        assertEquals(0.0, distance.meters, 0.001)
    }

    @Test fun anchorNeverBridgesAnUnobservedGapOrAnInvalidFix() {
        val points = listOf(point(0.0, 0, accuracy = 20f), point(21.0, 15_000, accuracy = 20f),
            point(1_000.0, 80_001, accuracy = 20f), point(1_021.0, 95_001, accuracy = 20f),
            point(2_000.0, 100_001, accuracy = 100f), point(2_007.0, 105_001, accuracy = 20f),
            point(2_049.0, 135_001, accuracy = 20f))
        assertEquals(42.0, AutomaticJourneyDistance(points).meters, 0.001)
    }
}
