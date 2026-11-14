package org.iz.navigation.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

/** Real weather coordinator and configured provider boundary; only external routes/forecast IO is synthetic. */
class MotorcycleDepartureTest {
    private val stops = listOf(RouteStop("Start", WeatherCoordinate(35.0, 25.0)), RouteStop("End", WeatherCoordinate(35.01, 25.01)))
    private fun planner(provider: RouteProvider, before: () -> Unit = {}) = object : RoutePlanner {
        override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
            before()
            return PlannedRoute("${provider.name}-$departureAt", stops,
                listOf(RouteVertex(stops.first().coordinate, 0.0), RouteVertex(stops.last().coordinate, 600.0)),
                10000.0, 600.0, 1L, transport = transport, provider = provider, preferences = preferences,
                travelSpeedKmh = travelSpeedKmh, effectiveDepartureAt = departureAt)
        }
    }
    private fun services(route: RoutePlanner, verify: RoutePlanner, free: RoutePlanner,
        read: () -> RideWeatherSettings = { defaultWeatherSettings(Transport.MOTORCYCLE) },
        forecast: suspend () -> Unit = {}) = WeatherPlannerServices(
        routePlanner = { route }, verificationPlanner = { verify }, trafficFreePlanner = { free },
        weatherProvider = { object : WeatherProvider {
            override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> { forecast(); return emptyList() }
        } }, readSettings = { read() }, saveSettings = { _, _ -> }, savePlan = {}, activate = { _, _ -> "synthetic-session" },
        clock = { 1000L }, compare = { _, at, _, _ -> (0..6).map { index -> val it = at + index * 1_800_000L
            WeatherAssessment(it, emptyList(), 0.0, false, null, null, null, null, null, null)
        } })

    @Test fun selectingMotorcycleDepartureResamplesWeatherUsingValhallaWithoutVerifiedTomTom() = runBlocking {
        var verified = 0
        var free = 0
        var forecasts = 0
        val valhalla = planner(RouteProvider.VALHALLA) { free++ }
        val tomTom = planner(RouteProvider.TOMTOM) { verified++ }
        val coordinator = WeatherPlannerCoordinator(this, services(valhalla, tomTom, valhalla, forecast = { forecasts++ }), SavedWeatherPlan(stops, 50_000L))
        coordinator.calculate()!!.join()
        coordinator.selectDeparture(1_850_000L)
        yield()
        assertEquals(RouteProvider.VALHALLA, coordinator.state.value.route?.provider)
        assertEquals(1_850_000L, coordinator.state.value.selectedDepartureAt)
        assertEquals(0, verified)
        assertEquals(2, free)
        assertEquals(2, forecasts)
    }

    @Test fun failedMotorcycleDepartureRetainsPriorRouteAndDoesNotOfferAnotherTrafficFreeProvider() = runBlocking {
        var verified = 0
        val base = planner(RouteProvider.VALHALLA)
        val fail = planner(RouteProvider.VALHALLA) { throw RouteServiceException("Synthetic offline") }
        val coordinator = WeatherPlannerCoordinator(this,
            services(base, planner(RouteProvider.TOMTOM) { verified++; throw RouteServiceException("Synthetic offline") }, fail), SavedWeatherPlan(stops, 50_000L))
        coordinator.calculate()!!.join()
        val original = coordinator.state.value.route
        coordinator.selectDeparture(1_850_000L)
        yield()
        assertEquals(original, coordinator.state.value.route)
        assertEquals(50_000L, coordinator.state.value.selectedDepartureAt)
        assertFalse(coordinator.state.value.busy)
        assertNotNull(coordinator.state.value.error)
        assertNull(coordinator.state.value.trafficFreeDeparture)
        assertEquals(0, verified)
    }

    @Test fun endpointChangeDuringSelectedForecastRejectsLateDepartureBundle() = runBlocking {
        var settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var forecasts = 0
        val base = planner(RouteProvider.VALHALLA)
        val coordinator = WeatherPlannerCoordinator(this,
            services(base, planner(RouteProvider.TOMTOM), base, read = { settings }, forecast = {
                if (++forecasts == 2) { started.complete(Unit); release.await() }
            }), SavedWeatherPlan(stops, 50_000L))
        coordinator.calculate()!!.join()
        val original = coordinator.state.value.route
        coordinator.selectDeparture(1_850_000L)
        withTimeout(2_000) { started.await() }
        settings = settings.copy(routeEndpoint = "https://changed-endpoint.invalid/route")
        release.complete(Unit)
        yield()
        assertEquals(original, coordinator.state.value.route)
        assertEquals(50_000L, coordinator.state.value.selectedDepartureAt)
        assertFalse(coordinator.state.value.busy)
    }

    @Test fun failedMotorcycleRecalculationKeepsUsableRouteUntilManualRetry() = runBlocking {
        var calls = 0
        var fail = false
        val valhalla = planner(RouteProvider.VALHALLA) { calls++; if (fail) throw RouteServiceException("Synthetic offline") }
        val coordinator = WeatherPlannerCoordinator(this, services(valhalla, valhalla, valhalla), SavedWeatherPlan(stops, 50_000L))
        coordinator.calculate()!!.join()
        val original = coordinator.state.value.route
        fail = true
        coordinator.calculate()!!.join()
        assertEquals(original, coordinator.state.value.route)
        assertEquals(50_000L, coordinator.state.value.selectedDepartureAt)
        assertFalse(coordinator.state.value.busy)
        assertNotNull(coordinator.state.value.error)
        assertEquals(2, calls)
        fail = false
        coordinator.calculate()!!.join()
        assertEquals(RouteProvider.VALHALLA, coordinator.state.value.route?.provider)
        assertEquals(3, calls)
    }

    @Test fun endpointChangeDuringBaseWeatherCannotPublishOldForecastOrLeaveBusy() = runBlocking {
        var settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val valhalla = planner(RouteProvider.VALHALLA)
        val coordinator = WeatherPlannerCoordinator(this, services(valhalla, valhalla, valhalla, read = { settings }, forecast = {
            started.complete(Unit); release.await()
        }), SavedWeatherPlan(stops, 50_000L))
        val job = coordinator.calculate()!!
        withTimeout(2000) { started.await() }
        settings = settings.copy(routeEndpoint = "https://changed.invalid/route")
        release.complete(Unit); job.join()
        assertTrue(coordinator.state.value.comparisons.isEmpty())
        assertNull(coordinator.state.value.selectedWeather)
        assertFalse(coordinator.state.value.busy)
    }

    @Test fun endpointChangeCannotReuseCachedMotorcycleDeparture() = runBlocking {
        var settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        var calls = 0
        val endpoints = mutableListOf<String>()
        val valhalla = planner(RouteProvider.VALHALLA) { calls++ }
        val services = services(valhalla, valhalla, valhalla, read = { settings }).copy(routePlanner = { selected ->
            endpoints += selected.routeEndpoint; valhalla
        })
        val coordinator = WeatherPlannerCoordinator(this, services, SavedWeatherPlan(stops, 50_000L))
        coordinator.calculate()!!.join()
        settings = settings.copy(routeEndpoint = "https://changed.invalid/route")
        coordinator.selectDeparture(50_000L)
        yield()
        assertEquals(1, calls)
        assertNull(coordinator.state.value.selectedWeather)
        assertNotNull(coordinator.state.value.error)
        coordinator.calculate()!!.join()
        assertEquals(2, calls)
        assertEquals("https://changed.invalid/route", endpoints.last())
        assertEquals(RouteProvider.VALHALLA, coordinator.state.value.route?.provider)
    }
}
