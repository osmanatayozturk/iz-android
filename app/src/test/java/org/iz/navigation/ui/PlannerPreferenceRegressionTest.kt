package org.iz.navigation.ui

import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class PlannerPreferenceRegressionTest {
    @Test fun savedPreferencesAndSpeedReachPreviewWithoutChangingModeDefaults() = runBlocking {
        val preferences = RoutePreferences(avoidHighways = false, avoidFerries = true)
        var received: RoutePreferences? = null
        var speed: Double? = null
        var saves = 0
        val stops = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .01)))
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
                received = preferences; speed = travelSpeedKmh
                return PlannedRoute("synthetic", stops, listOf(RouteVertex(stops[0].coordinate, 0.0),
                    RouteVertex(stops[1].coordinate, 100.0)), 1000.0, 100.0, 0L,
                    transport = transport, preferences = preferences)
            }
        }
        val services = WeatherPlannerServices({ planner }, { object : WeatherProvider {
            override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long) = emptyList<LocationForecast>()
        } }, ::defaultWeatherSettings, { _, _ -> saves++ }, {}, { _, _ -> "journey" }, clock = { 1000L })
        val coordinator = WeatherPlannerCoordinator(this, services,
            SavedWeatherPlan(stops, 50_000L, Transport.WALK, preferences, travelSpeedKmh = 4.0))
        coordinator.calculate()!!.join()
        assertEquals(preferences, received)
        assertEquals(4.0, speed!!, 0.0)
        assertEquals(0, saves)
    }
}
