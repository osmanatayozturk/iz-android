package org.iz.navigation.weather

import org.junit.Assert.*
import org.junit.Test

class RouteTravelTimeTest {
    @Test fun plannedArrivalIncludesMidnightAndFractionalTravelTime() {
        val lateEvening = 23L * 3_600_000 + 50 * 60_000
        assertEquals(24L * 3_600_000 + 20 * 60_000 + 250,
            RouteTravelTime.plannedArrival(lateEvening, 1_800.25))
    }

    @Test fun acceptedProgressUsesRemainingRouteCostWithoutLearningAReportedSpeed() {
        val first = RouteTravelTime.advance(null, 1_800.0, 1_000_000, 1_000_000)
        val halfway = RouteTravelTime.advance(first, 900.0, 1_005_000, 1_005_000)
        assertEquals(900.0, halfway.remainingSeconds, 0.0)
        assertEquals(1_905_000L, halfway.arrivalAt)
        assertFalse(halfway.gpsStale)
    }

    @Test fun stationaryFreshPositionShiftsArrivalByThePauseDuration() {
        val first = RouteTravelTime.advance(null, 900.0, 1_000_000, 1_000_000)
        val paused = RouteTravelTime.advance(first, 900.0, 1_030_000, 1_030_000)
        assertEquals(30_000L, paused.arrivalAt!! - first.arrivalAt!!)
        assertEquals(first.remainingSeconds, paused.remainingSeconds, 0.0)
    }

    @Test fun staleGpsFreezesTheLastPublishedTimeUntilANewFixArrives() {
        val fresh = RouteTravelTime.advance(null, 900.0, 1_000_000, 1_000_000)
        val stale = RouteTravelTime.advance(fresh, 800.0, 1_000_000, 1_090_000)
        assertTrue(stale.gpsStale)
        assertEquals(fresh.arrivalAt, stale.arrivalAt)
        assertEquals(fresh.updatedAt, stale.updatedAt)
        assertEquals(fresh.remainingSeconds, stale.remainingSeconds, 0.0)
        val recovered = RouteTravelTime.advance(stale, 800.0, 1_100_000, 1_100_000)
        assertFalse(recovered.gpsStale)
        assertEquals(1_900_000L, recovered.arrivalAt)
    }

    @Test fun absentGpsShowsPlannedDurationWithoutClaimingALiveArrival() {
        val timing = RouteTravelTime.advance(null, 900.0, null, 1_000_000)
        assertTrue(timing.gpsStale)
        assertNull(timing.arrivalAt)
        assertNull(timing.updatedAt)
        assertEquals(900.0, timing.remainingSeconds, 0.0)
    }

    @Test fun hugeRouteDurationCannotWrapArrivalIntoThePast() {
        assertEquals(Long.MAX_VALUE, RouteTravelTime.plannedArrival(1_000_000, Double.MAX_VALUE))
    }
}
