package com.atay.iz.tracking

import com.atay.iz.data.Journey
import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.TrackPoint
import com.atay.iz.data.Transport
import org.junit.Assert.*
import org.junit.Test

class AutomaticCandidateWindowTest {
    private val startedAt = 1_000_000L
    private val deadline = startedAt + 900_000L
    private val candidate = Journey(id = "candidate", transport = Transport.WALK,
        status = JourneyStatus.TEMPORARY, startedAt = startedAt, expiresAt = startedAt + 86_400_000L)

    @Test fun deadlineUsesPersistedJourneyStartAndOnlyAppliesToOpenAutomaticCandidates() {
        assertEquals(deadline, TrackingPolicy.automaticCandidateDeadline(candidate))
        assertNull(TrackingPolicy.automaticCandidateDeadline(candidate.copy(status = JourneyStatus.CONFIRMED)))
        assertNull(TrackingPolicy.automaticCandidateDeadline(candidate.copy(endedAt = startedAt + 120_000)))
    }

    @Test fun emptyCandidateResetsAtFifteenMinutesWithoutWaitingForAnotherGpsFix() {
        assertEquals(AutomaticCandidateDecision.KEEP, decision(emptyList(), deadline - 1))
        assertEquals(AutomaticCandidateDecision.RESET, decision(emptyList(), deadline))
        assertEquals(AutomaticCandidateDecision.RESET, decision(emptyList(), deadline + 60_000))
    }

    @Test fun thresholdJustBeforeDeadlineConfirmsAndKeepsOriginalStartEvenAfterReattachment() {
        val points = nearThreshold() + point(510.0, deadline - 1)
        assertEquals(AutomaticCandidateDecision.CONFIRM, decision(points, deadline - 1))
        assertEquals(AutomaticCandidateDecision.CONFIRM, decision(points, deadline + 1))
        assertEquals(startedAt, candidate.startedAt)
    }

    @Test fun thresholdAtExactDeadlineOrLaterCannotQualifyTheOldWindow() {
        assertEquals(AutomaticCandidateDecision.RESET, decision(nearThreshold() + point(510.0, deadline), deadline))
        assertEquals(AutomaticCandidateDecision.RESET, decision(nearThreshold() + point(510.0, deadline + 1), deadline + 1))
    }

    @Test fun pointsBeforeStartOrFromAnotherCandidateCannotBeCarriedIntoTheNextWindow() {
        val other = nearThreshold().map { it.copy(journeyId = "old") } + point(510.0, deadline - 1).copy(journeyId = "old")
        assertEquals(AutomaticCandidateDecision.RESET, decision(other, deadline))
        val previousWindow = nearThreshold().map { it.copy(recordedAt = it.recordedAt - 900_000) }
        assertEquals(AutomaticCandidateDecision.RESET, decision(previousWindow, deadline))
    }

    @Test fun stationaryNoiseAndMissingGpsNeverConfirmACandidate() {
        val jitter = (0..170).map { point((it % 2).toDouble(), startedAt + it * 5_000L) }
        assertEquals(AutomaticCandidateDecision.RESET, decision(jitter, deadline))
        val gap = listOf(point(0.0, startedAt), point(1000.0, deadline - 1))
        assertEquals(AutomaticCandidateDecision.RESET, decision(gap, deadline))
    }

    @Test fun confirmedAndFinishedTemporaryJourneysKeepTheirExistingLifetime() {
        assertEquals(AutomaticCandidateDecision.KEEP,
            TrackingPolicy.automaticCandidateDecision(candidate.copy(status = JourneyStatus.CONFIRMED), emptyList(), deadline))
        assertEquals(AutomaticCandidateDecision.KEEP,
            TrackingPolicy.automaticCandidateDecision(candidate.copy(endedAt = startedAt + 120_000), emptyList(), deadline))
    }

    private fun decision(points: List<TrackPoint>, now: Long) = TrackingPolicy.automaticCandidateDecision(candidate, points, now)
    private fun nearThreshold() = (0..14).map { point(it * 35.0, startedAt + it * 60_000L) }
    private fun point(meters: Double, at: Long) = TrackPoint(journeyId = candidate.id,
        latitude = Math.toDegrees(meters / 6_371_000.0), longitude = 0.0, recordedAt = at, accuracy = 5f)
}
