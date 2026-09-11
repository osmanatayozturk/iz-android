package com.atay.iz.car

import com.atay.iz.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class CarTrailTest {
    private fun point(id: String, time: Long, longitude: Double, gap: Boolean = false) =
        TrackPoint(journeyId = id, latitude = 0.0, longitude = longitude, recordedAt = time, accuracy = 5f, breakBefore = gap)

    @Test fun noActiveJourneyNeverDisplaysDiaryHistory() {
        assertTrue(activeTrailSegments(null, listOf(point("past", 1, 0.0))).isEmpty())
    }
    @Test fun activeIdentityFiltersForeignPointsAndSortsRecorderTimes() {
        val segments = activeTrailSegments("active", listOf(point("past", 0, 15.0), point("active", 2, 0.001), point("active", 1, 0.0)))
        assertEquals(listOf(1L, 2L), segments.single().map { it.recordedAt })
        assertEquals(111.2, trailDistanceMeters(segments), 0.3)
    }
    @Test fun recorderGapDoesNotBecomeAnArtificialLineOrDistance() {
        val segments = activeTrailSegments("a", listOf(point("a", 1, 0.0), point("a", 2, 0.001),
            point("a", 3, 30.0, true), point("a", 4, 30.001)))
        assertEquals(2, segments.size)
        assertEquals(222.4, trailDistanceMeters(segments), 0.5)
    }
    @Test fun invalidCoordinatesCannotPoisonSurfaceOrStatistics() {
        val segments = activeTrailSegments("a", listOf(point("a", 1, Double.NaN), point("a", 2, 181.0)))
        assertTrue(segments.isEmpty())
    }
}
