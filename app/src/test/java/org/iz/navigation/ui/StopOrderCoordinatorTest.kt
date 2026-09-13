package org.iz.navigation.ui

import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class StopOrderCoordinatorTest {
    private val stops = (0..3).map { RouteStop("Stop $it", WeatherCoordinate(0.0, it * .001)) }
    private val ordered get() = listOf(stops[0], stops[2], stops[1], stops[3])
    private fun route(points: List<RouteStop>, duration: Double) = PlannedRoute("$duration", points,
        listOf(RouteVertex(points.first().coordinate, 0.0), RouteVertex(points.last().coordinate, duration)),
        100.0, duration, 1_000L, transport = Transport.CAR, provider = RouteProvider.TOMTOM,
        effectiveDepartureAt = 121_000L)
    private fun proposal() = StopOrderProposal(stops, ordered, route(stops, 100.0), route(ordered, 70.0),
        121_000L, false, 1_000L, "revision")

    @Test fun phoneOrderNeedsExplicitProposalAndApplyWithoutStartingOrPlanning() = runBlocking {
        var proposals = 0
        var plans = 0
        var starts = 0
        val coordinator = PhoneDirectionsCoordinator(this, locate = { stops.first().coordinate },
            plan = { points, _ -> plans++; route(points, 100.0) }, activate = { starts++ },
            suggestOrder = { _, _, _ -> proposals++; proposal() }, credentialRevision = { "revision" }, clock = { 1_000L })
        coordinator.enter(1L, stops, Transport.CAR, locateOnOpen = false)
        assertEquals(0, proposals)
        coordinator.suggestStopOrder()!!.join()
        assertEquals(stops, coordinator.state.value.stops)
        coordinator.acceptStopOrder()
        assertEquals(ordered, coordinator.state.value.stops)
        assertEquals(0, plans)
        assertEquals(0, starts)
    }

    @Test fun weatherAppliesOnlyOrderAndRetainsTheUsersDeparture() = runBlocking {
        var plans = 0
        val coordinator = WeatherPlannerCoordinator(this, WeatherPlannerServices(
            routePlanner = { object : RoutePlanner {
                override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                    travelSpeedKmh: Double?, preferences: org.iz.navigation.weather.RoutePreferences): PlannedRoute { plans++; return route(stops, 100.0) }
            } },
            weatherProvider = { object : WeatherProvider {
                override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long) = emptyList<LocationForecast>()
            } }, readSettings = ::defaultWeatherSettings, saveSettings = { _, _ -> }, savePlan = {},
            activate = { _, _ -> "unused" }, clock = { 1_000L }, credentialRevision = { "revision" },
            suggestOrder = { _, _, _ -> proposal() },
        ), SavedWeatherPlan(stops, 10_000L, Transport.CAR))
        coordinator.suggestStopOrder()!!.join()
        assertEquals(stops, coordinator.state.value.stops)
        coordinator.acceptStopOrder()
        assertEquals(ordered, coordinator.state.value.stops)
        assertEquals(10_000L, coordinator.state.value.departureAt)
        assertNull(coordinator.state.value.route)
        assertEquals(0, plans)
    }

    @Test fun changedCredentialsPreventApplyingPhoneProposal() = runBlocking {
        var revision = "revision"
        val coordinator = PhoneDirectionsCoordinator(this, locate = { stops.first().coordinate },
            plan = { points, _ -> route(points, 100.0) }, activate = {},
            suggestOrder = { _, _, _ -> proposal() }, credentialRevision = { revision }, clock = { 1_000L })
        coordinator.enter(1L, stops, Transport.CAR, locateOnOpen = false)
        coordinator.suggestStopOrder()!!.join()
        revision = "changed"
        coordinator.acceptStopOrder()
        assertEquals(stops, coordinator.state.value.stops)
        assertNull(coordinator.state.value.orderProposal)
    }
}
