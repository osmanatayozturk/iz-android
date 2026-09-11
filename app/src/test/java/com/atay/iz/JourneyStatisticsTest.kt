package com.atay.iz

import com.atay.iz.data.Journey
import com.atay.iz.data.JourneyStatistics
import com.atay.iz.data.TrackPoint
import com.atay.iz.data.Transport
import org.junit.Assert.*
import org.junit.Test

class JourneyStatisticsTest {
    @Test fun separatesElapsedObservedMovingAndStoppedTime() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 40_000),
            listOf(point(0, 0.0), point(10_000, 0.001), point(20_000, 0.002), point(30_000, 0.002)),
            now = 90_000,
        )

        assertEquals(222.39, stats.distanceMeters, 0.02)
        assertEquals(40_000L, stats.elapsedMillis)
        assertEquals(30_000L, stats.observedMillis)
        assertEquals(20_000L, stats.movingMillis)
        assertEquals(10_000L, stats.stoppedMillis)
        assertEquals(10_000L, stats.unobservedMillis)
        assertEquals(20.015, stats.averageSpeedKmh!!, 0.01)
        assertEquals(40.03, stats.movingAverageSpeedKmh!!, 0.01)
        assertEquals(40.03, stats.maxSpeedKmh!!, 0.01)
    }

    @Test fun maxSpeedUsesFastestRecordedIntervalInsteadOfWholeTripAverage() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 20_000),
            listOf(point(0, 0.0), point(10_000, 0.001), point(20_000, 0.003)),
        )

        assertEquals(60.045, stats.averageSpeedKmh!!, 0.01)
        assertEquals(80.06, stats.maxSpeedKmh!!, 0.01)
    }

    @Test fun activeJourneyUsesNowAndDoesNotTreatUnobservedTailAsStopped() {
        val stats = JourneyStatistics.calculate(
            journey(), listOf(point(0, 0.0), point(10_000, 0.001), point(40_000, 0.004)), now = 20_000,
        )

        assertEquals(20_000L, stats.elapsedMillis)
        assertEquals(10_000L, stats.observedMillis)
        assertEquals(10_000L, stats.unobservedMillis)
        assertEquals(0L, stats.stoppedMillis)
        assertEquals(111.195, stats.distanceMeters, 0.01)
        assertEquals(20.015, stats.averageSpeedKmh!!, 0.01)
    }

    @Test fun finishedJourneyIgnoresNowAndPointsOutsideItsOwnTimeAndIdentity() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 10_000),
            listOf(
                point(-10_000, -0.001), point(10_000, 0.001),
                point(5_000, 0.005).copy(journeyId = "other"), point(0, 0.0), point(20_000, 0.002),
            ),
            now = 1_000,
        )

        assertEquals(10_000L, stats.elapsedMillis)
        assertEquals(10_000L, stats.observedMillis)
        assertEquals(111.195, stats.distanceMeters, 0.01)
    }

    @Test fun missingSamplesRemainUnknownInsteadOfShowingZeroSpeedOrAStop() {
        listOf(emptyList(), listOf(point(10_000, 0.0))).forEach { points ->
            val stats = JourneyStatistics.calculate(journey(endedAt = 20_000), points)

            assertEquals(0.0, stats.distanceMeters, 0.0)
            assertEquals(0L, stats.observedMillis)
            assertEquals(0L, stats.movingMillis)
            assertEquals(0L, stats.stoppedMillis)
            assertEquals(20_000L, stats.unobservedMillis)
            assertNull(stats.averageSpeedKmh)
            assertNull(stats.movingAverageSpeedKmh)
            assertNull(stats.maxSpeedKmh)
        }
    }

    @Test fun stationaryGpsNoiseDoesNotCreateDistanceOrSpeed() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 20_000),
            listOf(point(0, 0.0), point(10_000, 0.00001), point(20_000, 0.0)),
        )

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(20_000L, stats.observedMillis)
        assertEquals(20_000L, stats.stoppedMillis)
        assertEquals(0L, stats.movingMillis)
        assertEquals(0.0, stats.averageSpeedKmh!!, 0.0)
        assertEquals(0.0, stats.maxSpeedKmh!!, 0.0)
        assertNull(stats.movingAverageSpeedKmh)
    }

    @Test fun reliableCoordinateMovementOverridesZeroReportedSpeed() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 10_000),
            listOf(point(0, 0.0).copy(speed = 0f), point(10_000, 0.001).copy(speed = 0f)),
        )

        assertEquals(111.195, stats.distanceMeters, 0.01)
        assertEquals(10_000L, stats.movingMillis)
        assertEquals(0L, stats.stoppedMillis)
        assertEquals(40.03, stats.movingAverageSpeedKmh!!, 0.01)
    }

    @Test fun zeroReportedSpeedStillRecognizesRealStopsAndGpsJitter() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 30_000),
            listOf(point(0, 0.0), point(10_000, 0.0), point(20_000, 0.00001), point(30_000, 0.0))
                .map { it.copy(speed = 0f) },
        )

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(30_000L, stats.stoppedMillis)
        assertEquals(0L, stats.movingMillis)
        assertEquals(0.0, stats.maxSpeedKmh!!, 0.0)
        assertNull(stats.movingAverageSpeedKmh)
    }

    @Test fun coordinateDriftWithinReportedAccuracyDoesNotOverrideStationarySensor() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 10_000),
            listOf(point(0, 0.0), point(10_000, 0.0002)).map { it.copy(speed = 0f, accuracy = 80f) },
        )

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(10_000L, stats.stoppedMillis)
        assertEquals(0L, stats.movingMillis)
        assertNull(stats.movingAverageSpeedKmh)
    }

    @Test fun gpsGapsDoNotAddDistanceSpeedOrStoppedTime() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 310_000),
            listOf(point(0, 0.0), point(10_000, 0.001), point(300_000, 0.05), point(310_000, 0.05)),
        )

        assertEquals(111.195, stats.distanceMeters, 0.01)
        assertEquals(20_000L, stats.observedMillis)
        assertEquals(290_000L, stats.unobservedMillis)
        assertEquals(10_000L, stats.stoppedMillis)
        assertEquals(40.03, stats.maxSpeedKmh!!, 0.01)
    }

    @Test fun explicitRecordingBreaksDoNotBecomeTravelOrStops() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 30_000),
            listOf(point(0, 0.0), point(10_000, 0.001), point(20_000, 0.005).copy(breakBefore = true), point(30_000, 0.005)),
        )

        assertEquals(111.195, stats.distanceMeters, 0.01)
        assertEquals(20_000L, stats.observedMillis)
        assertEquals(10_000L, stats.unobservedMillis)
        assertEquals(10_000L, stats.stoppedMillis)
        assertEquals(40.03, stats.maxSpeedKmh!!, 0.01)
    }

    @Test fun invalidFixStaysABoundaryInsteadOfJoiningItsValidNeighbours() {
        listOf(
            point(5_000, 0.0005).copy(accuracy = 150f),
            point(5_000, 0.0005).copy(latitude = Double.NaN),
            point(5_000, 0.0005).copy(speed = Float.NaN),
        ).forEach { invalid ->
            val stats = JourneyStatistics.calculate(
                journey(endedAt = 10_000), listOf(point(0, 0.0), invalid, point(10_000, 0.001)),
            )

            assertEquals(0.0, stats.distanceMeters, 0.0)
            assertEquals(0L, stats.observedMillis)
            assertEquals(10_000L, stats.unobservedMillis)
            assertNull(stats.maxSpeedKmh)
        }
    }

    @Test fun impossiblePositionJumpsDoNotSetTheMaximumSpeed() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 30_000),
            listOf(point(0, 0.0), point(10_000, 1.0), point(20_000, 0.0), point(30_000, 0.001)),
        )

        assertEquals(111.195, stats.distanceMeters, 0.01)
        assertEquals(10_000L, stats.observedMillis)
        assertEquals(40.03, stats.maxSpeedKmh!!, 0.01)
    }

    @Test fun impossibleReportedSpeedDoesNotManufactureMovement() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 10_000),
            listOf(point(0, 0.0).copy(speed = 500f), point(10_000, 0.0).copy(speed = 500f)),
        )

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(10_000L, stats.stoppedMillis)
        assertEquals(0L, stats.movingMillis)
        assertEquals(0.0, stats.maxSpeedKmh!!, 0.0)
    }

    @Test fun duplicateTimestampsNeverDivideByZero() {
        val stats = JourneyStatistics.calculate(
            journey(endedAt = 10_000), listOf(point(0, 0.0), point(0, 0.001)),
        )

        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0L, stats.observedMillis)
        assertNull(stats.maxSpeedKmh)
    }

    @Test fun clockBeforeStartProducesNoNegativeDurationOrSpeeds() {
        val stats = JourneyStatistics.calculate(
            journey().copy(startedAt = 20_000), listOf(point(20_000, 0.0)), now = 10_000,
        )

        assertEquals(0L, stats.elapsedMillis)
        assertEquals(0L, stats.unobservedMillis)
        assertNull(stats.averageSpeedKmh)
    }

    @Test fun recordedStatisticsWorkForEveryTransportWithoutModeAssumptions() {
        Transport.entries.forEach { transport ->
            val stats = JourneyStatistics.calculate(
                journey(endedAt = 10_000).copy(transport = transport), listOf(point(0, 0.0), point(10_000, 0.001)),
            )

            assertEquals(transport.name, 111.195, stats.distanceMeters, 0.01)
            assertEquals(transport.name, 40.03, stats.maxSpeedKmh!!, 0.01)
        }
    }

    private fun journey(endedAt: Long? = null) = Journey(id = "trip", startedAt = 0, endedAt = endedAt)

    private fun point(time: Long, longitude: Double) = TrackPoint(
        journeyId = "trip", latitude = 0.0, longitude = longitude, recordedAt = time, accuracy = 5f,
    )
}
