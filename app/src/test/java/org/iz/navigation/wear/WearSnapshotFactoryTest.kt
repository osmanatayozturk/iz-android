package org.iz.navigation.wear

import org.iz.navigation.data.*
import org.junit.Assert.*
import org.junit.Test

class WearSnapshotFactoryTest {
    private val start = 1_000_000L
    private val trip = Journey(id = "trip", startedAt = start, transport = Transport.WALK, stepCount = 43L)
    private fun point(meters: Double, time: Long) = TrackPoint(journeyId = trip.id,
        latitude = Math.toDegrees(meters / 6_371_000.0), longitude = 0.0, accuracy = 5f,
        speed = 0f, recordedAt = time)

    @Test fun coordinatesDriveCurrentSpeedEvenWhenRawSpeedIsZero() {
        val state = WearSnapshotFactory.create(trip, listOf(point(0.0, start), point(100.0, start + 10_000)), start + 11_000, trip.id)
        assertEquals(36.0, state.currentSpeedKmh!!, 0.01)
        assertEquals(43L, state.stepCount)
        assertTrue(state.recording)
    }
    @Test fun staleGpsAndBrokenIntervalsDoNotInventCurrentSpeed() {
        val points = listOf(point(0.0, start), point(100.0, start + 10_000))
        assertNull(WearSnapshotFactory.create(trip, points, start + 40_000, trip.id).currentSpeedKmh)
        assertNull(WearSnapshotFactory.create(trip, points.map { it.copy(breakBefore = true) }, start + 11_000, trip.id).currentSpeedKmh)
    }
    @Test fun finishedJourneyCannotAppearActiveAndWrongServiceCannotAppearRecording() {
        assertNull(WearSnapshotFactory.create(trip.copy(endedAt = start + 1), emptyList(), start + 10, trip.id).journeyId)
        assertFalse(WearSnapshotFactory.create(trip, emptyList(), start + 10, "other").recording)
    }
    @Test fun temporaryWindowAndUnknownModeRemainExplicit() {
        val state = WearSnapshotFactory.create(trip.copy(status = JourneyStatus.TEMPORARY, transport = Transport.UNKNOWN), emptyList(), start + 30, trip.id)
        assertEquals(start + 900_000, state.deadlineAt)
        assertTrue(state.temporary)
        assertNull(state.mode)
        assertNull(state.stepCount)
        assertNull(state.averageSpeedKmh)
    }

    @Test fun runningPaceIncludesPausedTimeAndPhoneStepsStaySeparateFromWatchSteps() {
        val run = trip.copy(transport = Transport.RUN)
        val points = listOf(point(0.0, start), point(100.0, start + 10_000))
        val health = JourneyHealthSummary(run.id, latestHeartRateBpm = 120.0,
            latestHeartRateAt = start + 5_000, heartRateSampleCount = 1, watchSteps = 27L,
            measurementStartAt = start + 5_000, measurementEndAt = start + 5_000,
            metricCheckedAt = mapOf(HealthMetric.HEART_RATE_BPM to start + 59_000))
        val state = WearSnapshotFactory.create(run, points, start + 60_000, run.id, health)
        assertEquals(org.iz.navigation.wearprotocol.WearMode.RUN, state.mode)
        assertEquals(600.0, state.averagePaceSecondsPerKm!!, 0.01)
        assertEquals(43L, state.stepCount)
        val actualHealth = requireNotNull(state.health)
        assertEquals(27L, actualHealth.watchSteps)
        assertEquals(start + 5_000, actualHealth.latestHeartRateAt)
        assertEquals(start + 59_000, actualHealth.lastCheckedAt)
        assertTrue(actualHealth.partial)
        assertNull(actualHealth.totalCaloriesKcal)
        assertNull(WearSnapshotFactory.create(run, emptyList(), start + 60_000, run.id).averagePaceSecondsPerKm)
        assertNull(WearSnapshotFactory.create(trip, points, start + 60_000, trip.id).averagePaceSecondsPerKm)
    }

    @Test fun weatherAppearsForEveryActivelyRecordedJourneyMode() {
        val weather = org.iz.navigation.wearprotocol.WearRouteWeather(
            org.iz.navigation.wearprotocol.WearWeatherStatus.READY,
            calculatedAt = start,
            validUntil = start + 3_600_000,
            remainingMeters = 12_000.0,
            arrivalAt = start + 900_000,
            headline = "Eşiklerin altında",
        )
        val motorcycle = trip.copy(transport = Transport.MOTORCYCLE)
        assertEquals(weather, WearSnapshotFactory.create(motorcycle, emptyList(), start + 1, motorcycle.id,
            weather = weather).weather)
        assertNull(WearSnapshotFactory.create(motorcycle, emptyList(), start + 1, "other", weather = weather).weather)
        Transport.entries.filter { it != Transport.UNKNOWN }.forEach { mode ->
            assertEquals(mode.name, weather, WearSnapshotFactory.create(trip.copy(transport = mode),
                emptyList(), start + 1, trip.id, weather = weather).weather)
        }
        assertNull(WearSnapshotFactory.create(motorcycle.copy(endedAt = start + 1), emptyList(), start + 2,
            motorcycle.id, weather = weather).weather)
    }
    @Test fun unrelatedAndFinishedJourneyHealthNeverLeaksIntoCurrentSnapshot() {
        val health = JourneyHealthSummary("another", watchSteps = 100L)
        assertNull(WearSnapshotFactory.create(trip, emptyList(), start + 1, trip.id, health).health)
        assertNull(WearSnapshotFactory.create(trip.copy(endedAt = start + 1), emptyList(), start + 2,
            trip.id, health.copy(journeyId = trip.id)).health)
        val checkedEmpty = health.copy(journeyId = trip.id, watchSteps = null,
            metricCheckedAt = mapOf(HealthMetric.STEPS to start + 1))
        val summary = WearSnapshotFactory.create(trip, emptyList(), start + 2, trip.id, checkedEmpty).health!!
        assertNull(summary.watchSteps)
        assertNull(summary.latestHeartRateAt)
        assertEquals(start + 1, summary.lastCheckedAt)
    }
}
