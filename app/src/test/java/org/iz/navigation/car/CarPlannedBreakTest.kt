package org.iz.navigation.car

import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.PlannedBreakState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteSchedule
import org.iz.navigation.weather.RouteScheduleContext
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlannedBreakTest {
    private val referenceAt = 1_800_000_000_000L
    private val start = WeatherCoordinate(41.0, 29.0)
    private val turn = WeatherCoordinate(41.01, 29.01)
    private val via = WeatherCoordinate(41.05, 29.02)
    private val end = WeatherCoordinate(41.1, 29.05)
    private val route = PlannedRoute(
        "auto-planned-break",
        listOf(RouteStop("Start", start), RouteStop("Break", via, plannedBreakSeconds = 1_200), RouteStop("End", end)),
        listOf(RouteVertex(start, 0.0), RouteVertex(turn, 360.0), RouteVertex(via, 1_800.0), RouteVertex(end, 3_600.0)),
        15_000.0, 3_600.0, referenceAt,
        stopElapsedSeconds = listOf(0.0, 1_800.0, 3_600.0),
        maneuvers = listOf(RouteManeuver(10, "Turn right", "", emptyList(), 1, 2, 360.0, 1_800.0)),
    )
    private val progress = NavigationProgress(300.0, 3_300.0, 14_000.0, 0.0, 0, 120.0)
    private val legacy = NavigationState(route = route, progress = progress, guidance = true, gpsStale = false)

    private fun timed(remainingBreaks: Map<Int, Double>): NavigationState {
        val context = RouteScheduleContext(referenceAt, progress.elapsedSeconds, remainingBreaks)
        return legacy.copy(tripTiming = RouteSchedule.remaining(route, context), scheduleContext = context)
    }

    @Test fun destinationUsesSharedTotalAndAbsoluteArrivalWhileNextTurnUsesDrivingTime() {
        val trip = carTrip(timed(mapOf(1 to 1_199.25)))
        assertFalse(trip.isLoading)
        val destination = trip.destinationTravelEstimates.single()
        assertEquals(4_500L, destination.remainingTimeSeconds)
        assertEquals(referenceAt + 4_499_250L, checkNotNull(destination.arrivalTimeAtDestination).timeSinceEpochMillis)
        assertEquals(carDistance(14_000.0), destination.remainingDistance)
        val maneuver = trip.stepTravelEstimates.single()
        assertEquals(60L, maneuver.remainingTimeSeconds)
        assertEquals(carDistance(120.0), maneuver.remainingDistance)
    }

    @Test fun consumedBreakIsNotAddedBackFromTheSavedStop() {
        val trip = carTrip(timed(emptyMap()))
        val destination = trip.destinationTravelEstimates.single()
        assertEquals(3_300L, destination.remainingTimeSeconds)
        assertEquals(referenceAt + 3_300_000L, checkNotNull(destination.arrivalTimeAtDestination).timeSinceEpochMillis)
    }

    @Test fun legacyZeroBreakStateKeepsTheDrivingOnlyEstimate() {
        val oldRoute = route.copy(stops = route.stops.map { it.copy(plannedBreakSeconds = 0) })
        val trip = carTrip(legacy.copy(route = oldRoute))
        assertEquals(3_300L, trip.destinationTravelEstimates.single().remainingTimeSeconds)
        assertEquals(60L, trip.stepTravelEstimates.single().remainingTimeSeconds)
    }

    @Test fun automaticAndManualPauseAndStaleGpsSuppressExistingTripEstimates() {
        val state = timed(mapOf(1 to 1_200.0))
        val automatic = state.copy(paused = true,
            plannedBreak = PlannedBreakState(1, "Break", 1_200, referenceAt - 60_000L, 60.0, 1_140.0))
        for (unavailable in listOf(automatic, state.copy(paused = true), state.copy(gpsStale = true))) {
            val trip = carTrip(unavailable)
            assertTrue(trip.isLoading)
            assertTrue(trip.destinationTravelEstimates.isEmpty())
            assertTrue(trip.stepTravelEstimates.isEmpty())
        }
    }
}
