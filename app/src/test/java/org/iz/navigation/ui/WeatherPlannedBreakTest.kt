package org.iz.navigation.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.LocationForecast
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RoutePlanner
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.SavedWeatherPlan
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherProvider
import org.iz.navigation.weather.defaultWeatherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPlannedBreakTest {
    @Test fun editingOneOccurrenceInvalidatesPendingForecastAndExtendsNextWindow() = runBlocking {
        val start = 3_600_000L
        val via = RouteStop("Via", WeatherCoordinate(41.01, 29.0))
        val stops = listOf(RouteStop("Start", WeatherCoordinate(41.0, 29.0)), via, via,
            RouteStop("End", WeatherCoordinate(41.03, 29.0)))
        val times = listOf(0.0, 900.0, 1_800.0, 3_600.0)
        val requestedUntil = mutableListOf<Long>()
        val requested = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute =
                PlannedRoute("route", stops, stops.zip(times) { stop, time -> RouteVertex(stop.coordinate, time) },
                    4_000.0, 3_600.0, departureAt, times, transport, preferences = preferences, hasHighway = false)
        }
        val provider = object : WeatherProvider {
            override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> {
                requestedUntil += until
                if (requestedUntil.size == 1) {
                    requested.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await() }
                }
                return emptyList()
            }
        }
        val coordinator = WeatherPlannerCoordinator(this, WeatherPlannerServices(
            routePlanner = { planner }, weatherProvider = { provider }, readSettings = ::defaultWeatherSettings,
            saveSettings = { _, _ -> }, savePlan = {}, activate = { _, _ -> "unused" }, clock = { start },
        ), SavedWeatherPlan(stops, start))

        val oldJob = requireNotNull(coordinator.calculate())
        try {
            withTimeout(5_000L) { requested.await() }
            coordinator.setPlannedBreakMinutes(2, 20)
        } finally {
            releaseOld.complete(Unit)
        }
        oldJob.join()

        assertEquals(listOf(0, 0, 1_200, 0), coordinator.state.value.stops.map { it.plannedBreakSeconds })
        assertEquals(stops.map { it.coordinate }, coordinator.state.value.stops.map { it.coordinate })
        assertNull(coordinator.state.value.route)
        assertNull(coordinator.state.value.selectedAssessment)
        assertTrue(coordinator.state.value.comparisons.isEmpty())
        assertFalse(coordinator.state.value.busy)

        requireNotNull(coordinator.calculate()).join()
        assertEquals(listOf(21_600_000L, 22_800_000L), requestedUntil)
        assertEquals(8_400_000L, coordinator.state.value.selectedAssessment?.samples?.last()?.arrivalAt)

        coordinator.setPlannedBreakMinutes(2, 0)
        assertEquals(stops, coordinator.state.value.stops)
    }
}
