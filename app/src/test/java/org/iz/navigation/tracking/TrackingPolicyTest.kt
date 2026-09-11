package org.iz.navigation.tracking

import org.iz.navigation.data.Transport
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class TrackingPolicyTest {
    private fun fix(time: Long = 1_000_000, latitude: Double = 41.0,
        longitude: Double = 29.0, accuracy: Float = 5f, speed: Float? = null) =
        PositionSample(latitude, longitude, time, accuracy, speed)

    @Test fun invalidDuplicateDoesNotBreakFollowingEvidence() {
        val filter = PositionFilter()
        filter.accept(fix(), 1_000_000)
        assertNull(filter.accept(fix(accuracy = 500f), 1_000_000))
        assertNull(filter.accept(fix(time = 999_000, latitude = Double.NaN), 1_000_000))
        assertFalse(filter.accept(fix(time = 1_005_000), 1_005_000)!!.breakBefore)
    }

    @Test fun fortyMinuteTraceIgnoresInvalidDuplicates() {
        val filter = PositionFilter()
        val points = mutableListOf<TrackPoint>()
        for (index in 0..480) {
            val sample = fix(time = 1_000_000L + index * 5000L)
            val accepted = filter.accept(sample, sample.time)!!
            points += TrackPoint(journeyId = "trace", latitude = sample.latitude, longitude = sample.longitude,
                recordedAt = sample.time, accuracy = sample.accuracy, breakBefore = accepted.breakBefore)
            if (index % 4 == 0) filter.accept(sample.copy(accuracy = 500f), sample.time)
        }
        val stats = org.iz.navigation.data.JourneyStatistics.calculate(
            Journey(id = "trace", transport = Transport.CAR, startedAt = 1_000_000, endedAt = 3_400_000), points)
        assertEquals(2_400_000L, stats.observedMillis)
        assertEquals(0L, stats.unobservedMillis)
    }

    @Test fun temporaryRecordExpiresAtExactDeadline() {
        assertFalse(TrackingPolicy.expired(null, Long.MAX_VALUE))
        assertFalse(TrackingPolicy.expired(500, 499))
        assertTrue(TrackingPolicy.expired(500, 500))
        assertTrue(TrackingPolicy.expired(500, 501))
    }

    @Test fun runningUsesTheSameExclusiveFifteenMinuteWindowAndDistanceThreshold() {
        val run = Journey(id = "run", transport = Transport.RUN, status = JourneyStatus.TEMPORARY, startedAt = 100_000)
        val deadline = run.startedAt + 15 * 60_000L
        assertEquals(deadline, TrackingPolicy.automaticCandidateDeadline(run))
        fun route(meters: Double, finishAt: Long) = listOf(
            TrackPoint(journeyId = run.id, latitude = 0.0, longitude = 0.0, recordedAt = finishAt - 50_000, accuracy = 5f),
            TrackPoint(journeyId = run.id, latitude = Math.toDegrees(meters / 6_371_000.0), longitude = 0.0, recordedAt = finishAt, accuracy = 5f),
        )
        assertEquals(AutomaticCandidateDecision.KEEP, TrackingPolicy.automaticCandidateDecision(run, route(499.0, deadline - 1), deadline - 1))
        assertEquals(AutomaticCandidateDecision.CONFIRM, TrackingPolicy.automaticCandidateDecision(run, route(501.0, deadline - 1), deadline - 1))
        assertEquals(AutomaticCandidateDecision.RESET, TrackingPolicy.automaticCandidateDecision(run, route(501.0, deadline), deadline))
    }

    @Test fun routeBreaksAfterPoorAccuracyAndSignalGap() {
        val filter = PositionFilter()
        assertTrue(filter.accept(fix(), 1_000_000)!!.breakBefore)
        assertFalse(filter.accept(fix(time = 1_005_000), 1_005_000)!!.breakBefore)
        assertNull(filter.accept(fix(time = 1_010_000, accuracy = 100f), 1_010_000))
        assertTrue(filter.accept(fix(time = 1_015_000), 1_015_000)!!.breakBefore)
        assertTrue(filter.accept(fix(time = 1_080_000), 1_080_000)!!.breakBefore)
    }

    @Test fun staleInvalidAndOutOfOrderFixesAreNotRecorded() {
        val filter = PositionFilter()
        assertNull(filter.accept(fix(latitude = Double.NaN), 1_000_000))
        assertNull(filter.accept(fix(longitude = 181.0), 1_000_000))
        assertNull(filter.accept(fix(), 1_100_000))
        assertNotNull(filter.accept(fix(), 1_000_000))
        assertNull(filter.accept(fix(), 1_000_000))
        assertNull(filter.accept(fix(time = 999_999), 1_000_000))
    }

    @Test fun impossibleJumpIsRejectedAndCreatesBreak() {
        val filter = PositionFilter()
        filter.accept(fix(), 1_000_000)
        assertNull(filter.accept(fix(time = 1_005_000, latitude = 42.0), 1_005_000))
        assertTrue(filter.accept(fix(time = 1_010_000), 1_010_000)!!.breakBefore)
    }

    @Test fun vehicleSuggestionPreservesUserMotorcycleChoice() {
        assertTrue(TrackingPolicy.sameDetectedMode(Transport.MOTORCYCLE, Transport.CAR))
        assertTrue(TrackingPolicy.sameDetectedMode(Transport.WALK, Transport.WALK))
        assertFalse(TrackingPolicy.sameDetectedMode(Transport.CAR, Transport.BICYCLE))
    }

    @Test fun dwellPromptsOnceThenResetsAfterMoving() {
        val detector = DwellDetector()
        for (offset in 0L..600_000L step 15_000L) detector.observe(fix(time = 1_000_000 + offset))
        assertTrue(detector.shouldPrompt(1_600_000, 10))
        assertFalse(detector.shouldPrompt(1_600_001, 10))
        detector.observe(fix(time = 1_615_000, speed = 4f))
        for (offset in 0L..600_000L step 15_000L) detector.observe(fix(time = 1_630_000 + offset))
        assertTrue(detector.shouldPrompt(2_230_000, 10))
    }

    @Test fun missingGpsDoesNotImplyAStop() {
        val detector = DwellDetector()
        detector.observe(fix())
        assertFalse(detector.shouldPrompt(1_600_000, 10))
        detector.observe(fix(time = 1_600_000))
        assertFalse(detector.shouldPrompt(1_600_000, 10))
    }

    @Test fun noisyOutOfOrderObservationDoesNotEraseDwell() {
        val detector = DwellDetector()
        for (offset in 0L..600_000L step 15_000L) detector.observe(fix(time = 1_000_000 + offset))
        detector.observe(fix(time = 1_500_000, speed = 5f))
        assertTrue(detector.shouldPrompt(1_600_000, 10))
    }
}
