package org.iz.navigation.wear

import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.PlannedBreakState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteSchedule
import org.iz.navigation.weather.RouteScheduleContext
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.wearprotocol.WearNavigationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WearPlannedBreakTest {
    private val firstStopAt = 1_000_000L
    private val stops = listOf(
        RouteStop("Start", WeatherCoordinate(0.0, 0.0)),
        RouteStop("First break", WeatherCoordinate(0.01, 0.01), plannedBreakSeconds = 120),
        RouteStop("Second break", WeatherCoordinate(0.02, 0.02), plannedBreakSeconds = 60),
        RouteStop("Destination", WeatherCoordinate(0.03, 0.03)),
    )
    private val stopTimes = listOf(0.0, 600.0, 900.0, 1200.0)
    private val route = PlannedRoute(
        id = "route",
        stops = stops,
        vertices = stops.zip(stopTimes) { stop, time -> RouteVertex(stop.coordinate, time) },
        distanceMeters = 4_800.0,
        durationSeconds = 1_200.0,
        createdAt = firstStopAt - 600_000,
        stopElapsedSeconds = stopTimes,
    )

    private fun stateAtFirstStop(
        referenceAt: Long,
        remainingBreaks: Map<Int, Double>? = null,
    ) = NavigationState(
        sessionId = "session",
        guidance = true,
        route = route,
        fix = NavigationFix(stops[1].coordinate, referenceAt, 5f),
        gpsStale = false,
        progress = NavigationProgress(600.0, 600.0, 2_400.0, 0.0, 0, 100.0),
        progressUpdatedAt = referenceAt,
        tripTiming = RouteSchedule.remaining(route, RouteScheduleContext(referenceAt, 600.0, remainingBreaks)),
    )

    private fun summary(state: NavigationState, now: Long): WearNavigationSummary =
        requireNotNull(WearNavigationFactory.create(state, now))

    @Test fun plannedBreaksAffectEtaWithoutMovingItOnRepublish() {
        val state = stateAtFirstStop(firstStopAt)
        val initial = summary(state, firstStopAt)
        val republished = summary(state, firstStopAt + 5_000)

        assertEquals(firstStopAt + 780_000, initial.arrivalAt)
        assertEquals(initial.arrivalAt, republished.arrivalAt)
        assertEquals(firstStopAt, republished.fixAt)
    }

    @Test fun leavingEarlyDropsTheUnusedBreakButKeepsTheNextBreak() {
        val departure = firstStopAt + 45_000
        val state = stateAtFirstStop(departure, remainingBreaks = mapOf(2 to 60.0))

        assertEquals(firstStopAt + 705_000, summary(state, departure).arrivalAt)
    }

    @Test fun departingAfterAnOverrunUsesTheLaterSharedEta() {
        val departure = firstStopAt + 180_000
        val state = stateAtFirstStop(departure, remainingBreaks = mapOf(2 to 60.0))

        assertEquals(firstStopAt + 840_000, summary(state, departure).arrivalAt)
    }

    @Test fun bothAutomaticAndManualPauseHideTimingAndProgress() {
        val now = firstStopAt + 45_000
        val active = stateAtFirstStop(now, remainingBreaks = mapOf(1 to 75.0, 2 to 60.0))
        val automaticBreak = PlannedBreakState(1, "First break", 120, firstStopAt, 45.0, 75.0)

        for (breakState in listOf(automaticBreak, null)) {
            val value = summary(active.copy(paused = true, plannedBreak = breakState), now)
            assertEquals("Yönlendirme duraklatıldı", value.instruction)
            assertEquals(now, value.fixAt)
            assertFalse(value.gpsStale)
            assertNull(value.arrivalAt)
            assertNull(value.nextManeuverMeters)
            assertNull(value.remainingMeters)
        }
    }

    @Test fun sharedEtaEarlierThanTheFixIsOmitted() {
        val state = stateAtFirstStop(firstStopAt)
        val timing = requireNotNull(state.tripTiming).copy(arrivalAt = firstStopAt - 1)

        assertNull(summary(state.copy(tripTiming = timing), firstStopAt).arrivalAt)
    }
}
