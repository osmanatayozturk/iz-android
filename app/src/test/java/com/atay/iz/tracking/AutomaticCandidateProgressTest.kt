package com.atay.iz.tracking

import com.atay.iz.data.Journey
import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.TrackPoint
import com.atay.iz.data.Transport
import org.junit.Assert.*
import org.junit.Test

class AutomaticCandidateProgressTest {
    private val start = 1_000_000L
    private val candidate = Journey(id = "current", transport = Transport.WALK,
        status = JourneyStatus.TEMPORARY, startedAt = start)
    private fun point(meters: Double, at: Long = start, id: String = candidate.id) = TrackPoint(
        journeyId = id, latitude = Math.toDegrees(meters / 6_371_000), longitude = 0.0,
        recordedAt = at, accuracy = 20f)

    @Test fun liveProgressAndRestoredQualificationUseExactlyTheSameDistance() {
        val points = (0..72).map { point(it * 7.0, start + it * 5_000L) }
        val progress = TrackingPolicy.automaticCandidateProgress(candidate, points)!!
        assertEquals(AutomaticJourneyDistance(points).meters, progress, 0.000001)
        assertEquals(504.0, progress, 0.001)
        assertEquals(AutomaticCandidateDecision.CONFIRM,
            TrackingPolicy.automaticCandidateDecision(candidate, points, start + 360_000))
    }

    @Test fun progressExcludesOtherJourneysPreviousWindowsAndExactDeadlineFixes() {
        val deadline = start + TrackingPolicy.AUTOMATIC_WINDOW_MS
        val points = listOf(point(0.0, start - 1), point(1_000.0, start + 1, "old"),
            point(0.0, deadline - 50_000), point(499.0, deadline - 1), point(600.0, deadline))
        assertEquals(499.0, TrackingPolicy.automaticCandidateProgress(candidate, points)!!, 0.001)
        assertEquals(AutomaticCandidateDecision.RESET,
            TrackingPolicy.automaticCandidateDecision(candidate, points, deadline))
        val replacement = candidate.copy(id = "replacement", startedAt = deadline)
        assertEquals(0.0, TrackingPolicy.automaticCandidateProgress(replacement, points)!!, 0.0)
    }

    @Test fun permanentAndEndedJourneysExposeNoQualificationProgress() {
        assertNull(TrackingPolicy.automaticCandidateProgress(candidate.copy(status = JourneyStatus.CONFIRMED), emptyList()))
        assertNull(TrackingPolicy.automaticCandidateProgress(candidate.copy(endedAt = start + 1), emptyList()))
    }

    @Test fun stationaryNoiseDoesNotConfirmWhenRecomputedFromStoredPoints() {
        val points = (0..179).map { point(if (it % 2 == 0) -20.0 else 20.0, start + it * 5_000L) }
        assertEquals(AutomaticCandidateDecision.RESET,
            TrackingPolicy.automaticCandidateDecision(candidate, points, start + TrackingPolicy.AUTOMATIC_WINDOW_MS))
    }
}
