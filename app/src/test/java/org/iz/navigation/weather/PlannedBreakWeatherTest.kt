package org.iz.navigation.weather

import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.PlannedBreakState
import org.iz.navigation.wearprotocol.WearWeatherStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlannedBreakWeatherTest {
    private val stops = listOf(
        RouteStop("Start", WeatherCoordinate(41.0, 29.0)),
        RouteStop("First via", WeatherCoordinate(41.01, 29.0), plannedBreakSeconds = 600),
        RouteStop("Second via", WeatherCoordinate(41.02, 29.0), plannedBreakSeconds = 1_200),
        RouteStop("End", WeatherCoordinate(41.04, 29.0)),
    )
    private val driveTimes = listOf(0.0, 900.0, 1_800.0, 3_600.0)
    private val route = PlannedRoute(
        "break-weather", stops, stops.zip(driveTimes) { stop, time -> RouteVertex(stop.coordinate, time) },
        5_000.0, 3_600.0, 0L, driveTimes,
    )

    @Test fun plannedBreaksShiftDownstreamEncountersAndCanExhaustForecastCoverage() {
        val departure = 3_600_000L
        val reading = WeatherReading(17.0, 10.0, 0.0, 5.0, 8.0, 90.0)
        val hours = listOf(WeatherHour(departure, reading), WeatherHour(7_200_000L, reading))
        val forecasts = WeatherEngine.sampleVertices(route).map { LocationForecast(it.coordinate, hours, departure) }

        val assessment = WeatherEngine.assess(route, departure, forecasts, WeatherThresholds())

        assertEquals(listOf(0.0, 900.0, 1_800.0, 2_700.0, 3_600.0),
            assessment.samples.map { it.elapsedSeconds })
        assertEquals(4_500_000L, assessment.samples[1].arrivalAt)
        assertEquals(6_000_000L, assessment.samples[2].arrivalAt)
        assertEquals(9_000_000L, assessment.samples.last().arrivalAt)
        assertFalse(assessment.samples.last().complete)
        assertFalse(assessment.complete)
    }

    @Test fun automaticPauseKeepsPhoneEncountersWhileManualPauseHidesThemAndWearWaits() {
        val reference = 7_200_000L
        val context = RouteScheduleContext(reference, 900.0, mapOf(1 to 300.0, 2 to 1_200.0))
        val automatic = NavigationState(route = route, paused = true, scheduleContext = context,
            plannedBreak = PlannedBreakState(1, "First via", 600, reference - 300_000L, 300.0, 300.0))

        val phoneContext = requireNotNull(rideWeatherScheduleContext(reference, 900.0, automatic))
        val assessment = WeatherEngine.assess(route, context = phoneContext, forecasts = emptyList(),
            thresholds = WeatherThresholds())
        val timing = RouteSchedule.remaining(route, phoneContext)

        assertEquals(8_400_000L, assessment.samples.first { it.elapsedSeconds == 1_800.0 }.arrivalAt)
        assertEquals(11_400_000L, timing.arrivalAt)
        assertEquals(timing.arrivalAt, assessment.samples.last().arrivalAt)
        assertNull(rideWeatherScheduleContext(reference, 900.0, automatic.copy(plannedBreak = null)))
        val live = RideWeatherLiveState(status = RideWeatherStatus.READY,
            assessment = assessment.copy(complete = true, fetchedAt = reference),
            arrivalAt = timing.arrivalAt, paused = true, tripTiming = timing, plannedBreak = automatic.plannedBreak)
        val wear = requireNotNull(live.pausedWearWeather(reference, navigationPaused = automatic.paused))
        assertEquals(WearWeatherStatus.LOADING, wear.status)
        assertNull(wear.arrivalAt)
    }

    @Test fun legacyLiveContextUsesDrivingOnlyAndSharesFractionalArrivalRounding() {
        val context = requireNotNull(rideWeatherScheduleContext(10L, 3_599.9994, NavigationState(route = route)))
        val assessment = WeatherEngine.assess(route, context, emptyList(), WeatherThresholds())

        assertEquals(11L, assessment.samples.single().arrivalAt)
        assertEquals(11L, RouteSchedule.remaining(route, context).arrivalAt)
    }
}
