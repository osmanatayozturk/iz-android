package org.iz.navigation

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyComparison
import org.iz.navigation.data.JourneyStats
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.PreparedJourneyComparison
import org.iz.navigation.data.Transport
import org.iz.navigation.data.JourneyStatistics
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.ui.comparisonDistance
import org.junit.Assert.*
import org.junit.Test

class JourneyComparisonTest {
    @Test fun twoDistinctCompletedSummariesPreserveSelectedMetricsWithoutRouteIdentityClaim() {
        val first = prepared("first")
        val second = PreparedJourneyComparison(
            journey("second").copy(endedAt = 20_000), FINGERPRINT_B,
            fullStats().copy(distanceMeters = 200.0, elapsedMillis = 20_000,
                observedMillis = 20_000, stoppedMillis = 10_000, movingAverageSpeedKmh = 72.0,
                maxSpeedKmh = 90.0),
        )
        val result = JourneyComparison.compare(first, second)
        assertNotNull("Two eligible prepared summaries must be consumable", result)
        result!!
        assertEquals("first", result.first.journey.id)
        assertEquals("second", result.second.journey.id)
        assertEquals(FINGERPRINT_A, result.first.fingerprint)
        assertEquals(FINGERPRINT_B, result.second.fingerprint)
        assertEquals(100.0, result.first.stats.distanceMeters, 0.0)
        assertEquals(200.0, result.second.stats.distanceMeters, 0.0)
        assertEquals(10_000L, result.first.stats.elapsedMillis)
        assertEquals(20_000L, result.second.stats.elapsedMillis)
        assertEquals(36.0, result.first.stats.averageSpeedKmh!!, 0.0)
        assertEquals(72.0, result.second.stats.movingAverageSpeedKmh!!, 0.0)
        assertFalse(result.differentTransport)
        assertFalse(result.incompleteCoverage)
        assertFalse("Summary comparison cannot establish the same recorded/planned route", result.routeIdentityVerified)
    }

    @Test fun comparisonPreservesTheUsersFirstAndSecondSelectionOrder() {
        val result = JourneyComparison.compare(prepared("second"), prepared("first"))
        assertNotNull(result)
        assertEquals("second", result!!.first.journey.id)
        assertEquals("first", result.second.journey.id)
        assertFalse(result.routeIdentityVerified)
    }

    @Test fun selectingTheSameJourneyTwiceDoesNotProduceAComparison() {
        val first = prepared("same")
        val alteredSummary = first.copy(fingerprint = FINGERPRINT_B)
        assertNull(JourneyComparison.compare(first, alteredSummary))
    }

    @Test fun bothSlotsRequireConfirmedCompletedAndValidJourneyMetadata() {
        val valid = prepared("valid")
        val base = prepared("invalid")
        val invalidJourneys = listOf(
            base.journey.copy(endedAt = null),
            base.journey.copy(status = JourneyStatus.TEMPORARY),
            base.journey.copy(startedAt = 11_000, endedAt = 10_000),
            base.journey.copy(id = ""),
        )
        invalidJourneys.forEach { invalid ->
            assertRejectedInEitherSlot(valid, base.copy(journey = invalid), "journey=$invalid")
        }
    }

    @Test fun malformedFingerprintsAreNotRenderablePreparedInputs() {
        val valid = prepared("valid")
        listOf("", "a".repeat(63), "b".repeat(65), "g".repeat(64), " " + "a".repeat(63),
            "a".repeat(63) + "\n").forEach { invalid ->
            assertRejectedInEitherSlot(valid, prepared("invalid").copy(fingerprint = invalid),
                "fingerprint length=${invalid.length}")
        }
    }

    @Test fun nonFiniteOrNegativeDistancesAndSpeedsRejectThePreparedSummary() {
        val base = fullStats()
        val badStats = listOf(
            base.copy(distanceMeters = -1.0), base.copy(distanceMeters = Double.NaN),
            base.copy(distanceMeters = Double.POSITIVE_INFINITY),
            base.copy(averageSpeedKmh = -1.0), base.copy(averageSpeedKmh = Double.NaN),
            base.copy(averageSpeedKmh = Double.POSITIVE_INFINITY),
            base.copy(movingAverageSpeedKmh = -1.0), base.copy(movingAverageSpeedKmh = Double.NaN),
            base.copy(movingAverageSpeedKmh = Double.POSITIVE_INFINITY),
            base.copy(maxSpeedKmh = -1.0), base.copy(maxSpeedKmh = Double.NaN),
            base.copy(maxSpeedKmh = Double.POSITIVE_INFINITY),
        )
        badStats.forEachIndexed { index, invalid ->
            assertRejectedInEitherSlot(prepared("valid"), prepared("invalid").copy(stats = invalid), "numeric case$index")
        }
    }

    @Test fun negativeDurationsAndInconsistentCoverageAreRejectedInEitherSlot() {
        val base = fullStats()
        val badStats = listOf(
            base.copy(elapsedMillis = -1), base.copy(observedMillis = -1),
            base.copy(movingMillis = -1), base.copy(stoppedMillis = -1),
            base.copy(unobservedMillis = -1),
            base.copy(observedMillis = 11_000),
            base.copy(movingMillis = 9_000), // moving + stopped must equal observed.
            base.copy(unobservedMillis = 1), // observed + unobserved must equal elapsed.
            base.copy(elapsedMillis = 20_000, unobservedMillis = 10_000), // wrong journey span.
            base.copy(movingMillis = Long.MAX_VALUE, stoppedMillis = Long.MAX_VALUE),
        )
        badStats.forEachIndexed { index, invalid ->
            assertRejectedInEitherSlot(prepared("valid"), prepared("invalid").copy(stats = invalid), "duration case$index")
        }
    }

    @Test fun unobservedTimeIsWarnedAndUnknownSpeedsAreNotConvertedToMeasuredZero() {
        val unknown = JourneyStats(distanceMeters = 0.0, elapsedMillis = 10_000,
            observedMillis = 0, movingMillis = 0, stoppedMillis = 0, unobservedMillis = 10_000,
            averageSpeedKmh = null, movingAverageSpeedKmh = null, maxSpeedKmh = null)
        val result = JourneyComparison.compare(prepared("no-fixes").copy(stats = unknown), prepared("other"))
        assertNotNull(result)
        result!!
        assertTrue(result.incompleteCoverage)
        assertFalse(result.differentTransport)
        assertEquals(10_000L, result.first.stats.unobservedMillis)
        assertEquals(0L, result.first.stats.stoppedMillis)
        assertNull(result.first.stats.averageSpeedKmh)
        assertNull(result.first.stats.movingAverageSpeedKmh)
        assertNull(result.first.stats.maxSpeedKmh)
        assertFalse(result.routeIdentityVerified)
    }

    @Test fun observedStationaryZeroRemainsMeasuredZeroAndNotMissingCoverage() {
        val stationary = JourneyStats(distanceMeters = 0.0, elapsedMillis = 10_000,
            observedMillis = 10_000, movingMillis = 0, stoppedMillis = 10_000, unobservedMillis = 0,
            averageSpeedKmh = 0.0, movingAverageSpeedKmh = null, maxSpeedKmh = 0.0)
        val result = JourneyComparison.compare(prepared("stationary").copy(stats = stationary), prepared("other"))
        assertNotNull(result)
        result!!
        assertFalse(result.incompleteCoverage)
        assertEquals(0.0, result.first.stats.averageSpeedKmh!!, 0.0)
        assertEquals(0.0, result.first.stats.maxSpeedKmh!!, 0.0)
        assertNull(result.first.stats.movingAverageSpeedKmh)
        assertEquals(10_000L, result.first.stats.stoppedMillis)
    }

    @Test fun missingObservationCannotAdvertiseFabricatedMeasuredZeroSpeed() {
        val inconsistent = JourneyStats(distanceMeters = 0.0, elapsedMillis = 10_000,
            observedMillis = 0, movingMillis = 0, stoppedMillis = 0, unobservedMillis = 10_000,
            averageSpeedKmh = 0.0, movingAverageSpeedKmh = null, maxSpeedKmh = 0.0)
        assertRejectedInEitherSlot(prepared("valid"), prepared("invalid").copy(stats = inconsistent), "unknown-is-not-zero")
    }

    @Test fun partialCoverageWarningIsSymmetricAndDoesNotTurnMissingTimeIntoStoppedTime() {
        val partial = fullStats().copy(distanceMeters = 50.0, observedMillis = 5_000,
            movingMillis = 5_000, unobservedMillis = 5_000, averageSpeedKmh = 18.0)
        val first = prepared("partial").copy(stats = partial)
        val second = prepared("complete")
        val forward = JourneyComparison.compare(first, second)
        val reverse = JourneyComparison.compare(second, first)
        assertNotNull(forward)
        assertNotNull(reverse)
        assertTrue(forward!!.incompleteCoverage)
        assertTrue(reverse!!.incompleteCoverage)
        assertEquals(0L, forward.first.stats.stoppedMillis)
        assertEquals(5_000L, forward.first.stats.unobservedMillis)
        assertEquals(18.0, forward.first.stats.averageSpeedKmh!!, 0.0)
    }

    @Test fun differentTransportWarnsWithoutRejectingOrClaimingRouteEquivalence() {
        val walking = prepared("walking").let { it.copy(journey = it.journey.copy(transport = Transport.WALK)) }
        val motorcycle = prepared("motorcycle")
        val forward = JourneyComparison.compare(walking, motorcycle)
        val reverse = JourneyComparison.compare(motorcycle, walking)
        assertNotNull(forward)
        assertNotNull(reverse)
        assertTrue(forward!!.differentTransport)
        assertTrue(reverse!!.differentTransport)
        assertEquals(Transport.WALK, forward.first.journey.transport)
        assertEquals(Transport.MOTORCYCLE, forward.second.journey.transport)
        assertFalse(forward.routeIdentityVerified)
        assertFalse(reverse.routeIdentityVerified)
    }

    @Test fun actualProducerWithoutUsableIntervalsKeepsDistanceUnavailableInEitherComparisonSlot() {
        val unknownJourney = journey("producer-unknown").copy(endedAt = 60_000L)
        fun point(time: Long, latitude: Double = 0.0, accuracy: Float = 5f) = TrackPoint(
            journeyId = unknownJourney.id, latitude = latitude, longitude = 0.0,
            recordedAt = time, accuracy = accuracy,
        )
        val inputs: List<Pair<String, List<TrackPoint>>> = listOf(
            "empty" to emptyList(),
            "one valid fix without an interval" to listOf(point(0L)),
            "all invalid coordinates" to listOf(point(0L, Double.NaN), point(60_000L, 91.0)),
            "all unusable accuracy" to listOf(point(0L, accuracy = 101f), point(60_000L, accuracy = 101f)),
        )
        val referenceJourney = journey("producer-reference").copy(endedAt = 60_000L)
        val reference = PreparedJourneyComparison(referenceJourney, FINGERPRINT_B,
            JourneyStatistics.calculate(referenceJourney, listOf(
                TrackPoint(journeyId = referenceJourney.id, latitude = 0.0, longitude = 0.0, recordedAt = 0L, accuracy = 5f),
                TrackPoint(journeyId = referenceJourney.id, latitude = 0.0, longitude = 0.0, recordedAt = 60_000L, accuracy = 5f),
            )))
        inputs.forEach { (label, points) ->
            val stats = JourneyStatistics.calculate(unknownJourney, points)
            assertEquals(label, 0.0, stats.distanceMeters, 0.0)
            assertEquals(label, 60_000L, stats.elapsedMillis)
            assertEquals(label, 0L, stats.observedMillis)
            assertEquals(label, 0L, stats.movingMillis)
            assertEquals(label, 0L, stats.stoppedMillis)
            assertEquals(label, 60_000L, stats.unobservedMillis)
            assertNull(stats.averageSpeedKmh)
            assertNull(stats.movingAverageSpeedKmh)
            assertNull(stats.maxSpeedKmh)
            val unknown = PreparedJourneyComparison(unknownJourney, FINGERPRINT_A, stats)

            val forward = JourneyComparison.compare(unknown, reference)
            val reverse = JourneyComparison.compare(reference, unknown)

            assertNotNull("$label remains a valid comparison with known elapsed/coverage", forward)
            assertNotNull("$label remains valid in the second slot", reverse)
            val first = requireNotNull(forward)
            val second = requireNotNull(reverse)
            assertTrue(first.incompleteCoverage)
            assertTrue(second.incompleteCoverage)
            assertEquals(label, "—", comparisonDistance(first.first.stats))
            assertEquals(label, "—", comparisonDistance(second.second.stats))
            assertEquals("0,00 km", comparisonDistance(first.second.stats))
            assertEquals("0,00 km", comparisonDistance(second.first.stats))
            assertEquals(60_000L, first.first.stats.unobservedMillis)
            assertEquals(0L, second.second.stats.stoppedMillis)
        }
    }

    @Test fun actualObservedStationaryIntervalsKeepMeasuredZeroWithFullOrPartialCoverage() {
        val referenceJourney = journey("stationary-reference").copy(endedAt = 60_000L)
        val reference = PreparedJourneyComparison(referenceJourney, FINGERPRINT_B,
            JourneyStatistics.calculate(referenceJourney, listOf(
                TrackPoint(journeyId = referenceJourney.id, latitude = 0.0, longitude = 0.0, recordedAt = 0L, accuracy = 5f),
                TrackPoint(journeyId = referenceJourney.id, latitude = 0.0, longitude = 0.0, recordedAt = 60_000L, accuracy = 5f),
            )))
        listOf(60_000L, 10_000L).forEach { observed ->
            val trip = journey("stationary-$observed").copy(endedAt = 60_000L)
            val stats = JourneyStatistics.calculate(trip, listOf(
                TrackPoint(journeyId = trip.id, latitude = 0.0, longitude = 0.0, recordedAt = 0L, accuracy = 5f),
                TrackPoint(journeyId = trip.id, latitude = 0.0, longitude = 0.0, recordedAt = observed, accuracy = 5f),
            ))
            assertEquals(0.0, stats.distanceMeters, 0.0)
            assertEquals(observed, stats.observedMillis)
            assertEquals(observed, stats.stoppedMillis)
            assertEquals(0L, stats.movingMillis)
            assertEquals(60_000L - observed, stats.unobservedMillis)
            assertEquals(0.0, requireNotNull(stats.averageSpeedKmh), 0.0)
            assertEquals(0.0, requireNotNull(stats.maxSpeedKmh), 0.0)
            assertNull(stats.movingAverageSpeedKmh)
            val stationary = PreparedJourneyComparison(trip, FINGERPRINT_A, stats)

            val forward = JourneyComparison.compare(stationary, reference)
            val reverse = JourneyComparison.compare(reference, stationary)

            assertNotNull(forward)
            assertNotNull(reverse)
            val first = requireNotNull(forward)
            val second = requireNotNull(reverse)
            assertEquals(observed < 60_000L, first.incompleteCoverage)
            assertEquals(observed < 60_000L, second.incompleteCoverage)
            assertEquals("0,00 km", comparisonDistance(first.first.stats))
            assertEquals("0,00 km", comparisonDistance(second.second.stats))
            assertEquals(observed, first.first.stats.stoppedMillis)
            assertEquals(60_000L - observed, second.second.stats.unobservedMillis)
        }
    }

    private fun assertRejectedInEitherSlot(valid: PreparedJourneyComparison,
        invalid: PreparedJourneyComparison, label: String) {
        assertNull("$label in second slot", JourneyComparison.compare(valid, invalid))
        assertNull("$label in first slot", JourneyComparison.compare(invalid, valid))
    }

    private fun prepared(id: String) = PreparedJourneyComparison(journey(id), FINGERPRINT_A, fullStats())
    private fun journey(id: String) = Journey(id = id, transport = Transport.MOTORCYCLE,
        status = JourneyStatus.CONFIRMED, startedAt = 0, endedAt = 10_000)

    // Hand-written consistent summary:100 m /10 s =36 km/h; sampled max72 km/h is plausible.
    private fun fullStats() = JourneyStats(distanceMeters = 100.0, elapsedMillis = 10_000,
        observedMillis = 10_000, movingMillis = 10_000, stoppedMillis = 0, unobservedMillis = 0,
        averageSpeedKmh = 36.0, movingAverageSpeedKmh = 36.0, maxSpeedKmh = 72.0)

    private companion object {
        val FINGERPRINT_A = "a".repeat(64)
        val FINGERPRINT_B = "b".repeat(64)
    }
}
