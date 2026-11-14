package org.iz.navigation.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

/** Real phone/shared destination and weather owners; only external IO is synthetic. */
class MotorcycleCoordinatorIntegrationTest {
    private val stops = listOf(0.0, .008, .002, .010).mapIndexed { index, lon -> RouteStop("Stop $index", WeatherCoordinate(35.0, 25.0 + lon)) }
    private fun proposal(values: List<RouteStop>, settings: RideWeatherSettings): StopOrderProposal {
        val ordered = listOf(values[0], values[2], values[1], values[3])
        return StopOrderProposal(values, ordered,
            itineraryFixture(values, 100.0, at = 200_000L, transport = Transport.MOTORCYCLE, preferences = settings.preferences),
            itineraryFixture(ordered, 60.0, at = 200_000L, transport = Transport.MOTORCYCLE, preferences = settings.preferences),
            200_000L, false, 1000L, "stable", heuristic = true, routeSettings = settings)
    }
    private fun phone(scope: CoroutineScope, settings: () -> RideWeatherSettings,
        suggest: suspend (List<RouteStop>, Long, Transport, RoutePreferences) -> StopOrderProposal?): PhoneDirectionsCoordinator =
        PhoneDirectionsCoordinator(scope, { stops.first().coordinate }, { _, _ -> error("Ordering must not preview a route") }, {},
            credentialRevision = { "stable" }, clock = { 1000L }, readSettings = { settings() }, suggestOrderWithPreferences = suggest)

    private fun weather(scope: CoroutineScope, settings: () -> RideWeatherSettings,
        suggest: suspend (List<RouteStop>, Long, Transport, RoutePreferences) -> StopOrderProposal?): WeatherPlannerCoordinator {
        val unused = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute = error("Ordering must not calculate weather")
        }
        return WeatherPlannerCoordinator(scope, WeatherPlannerServices({ unused }, { object : WeatherProvider {
            override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast> = error("No forecast for ordering")
        } }, { settings() }, { _, _ -> }, {}, { _, _ -> "synthetic" }, clock = { 1000L },
            credentialRevision = { "stable" }, suggestOrderWithPreferences = suggest), SavedWeatherPlan(stops, 50_000L))
    }

    @Test fun phoneMotorcycleOrderUsesDefaultAvoidancesAndNeedsExplicitApply() = runBlocking {
        val settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        val owner = phone(this, { settings }) { values, _, mode, preferences ->
            assertEquals(Transport.MOTORCYCLE, mode); assertTrue(preferences.avoidUnpaved)
            proposal(values, settings)
        }
        owner.enter(1L, stops, Transport.MOTORCYCLE, locateOnOpen = false)
        val job = owner.suggestStopOrder(); assertNotNull(job); job!!.join()
        assertNotNull(owner.state.value.orderProposal)
        assertEquals(stops, owner.state.value.stops)
        owner.acceptStopOrder()
        assertEquals(listOf(stops[0], stops[2], stops[1], stops[3]), owner.state.value.stops)
    }

    @Test fun weatherMotorcycleOrderUsesDefaultAvoidancesAndNeedsExplicitApply() = runBlocking {
        val settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        val owner = weather(this, { settings }) { values, _, _, _ -> proposal(values, settings) }
        val job = owner.suggestStopOrder(); assertNotNull(job); job!!.join()
        assertNotNull(owner.state.value.orderProposal)
        assertEquals(stops, owner.state.value.stops)
        owner.acceptStopOrder()
        assertEquals(listOf(stops[0], stops[2], stops[1], stops[3]), owner.state.value.stops)
    }

    @Test fun phoneEndpointChangedWhileProposalWaitsCannotPublishOrApplyStaleOrder() = runBlocking {
        var settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = phone(this, { settings }) { values, _, _, _ ->
            val result = proposal(values, settings); started.complete(Unit); release.await(); result
        }
        owner.enter(1L, stops, Transport.MOTORCYCLE, locateOnOpen = false)
        val job = owner.suggestStopOrder(); assertNotNull(job)
        withTimeout(2000) { started.await() }
        settings = settings.copy(routeEndpoint = "https://changed.invalid/route")
        release.complete(Unit); job!!.join()
        assertNull(owner.state.value.orderProposal)
        owner.acceptStopOrder(); assertEquals(stops, owner.state.value.stops)
    }

    @Test fun weatherEndpointChangedWhileProposalWaitsCannotPublishOrApplyStaleOrder() = runBlocking {
        var settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = weather(this, { settings }) { values, _, _, _ ->
            val result = proposal(values, settings); started.complete(Unit); release.await(); result
        }
        val job = owner.suggestStopOrder(); assertNotNull(job)
        withTimeout(2000) { started.await() }
        settings = settings.copy(routeEndpoint = "https://changed.invalid/route")
        release.complete(Unit); job!!.join()
        assertNull(owner.state.value.orderProposal)
        owner.acceptStopOrder(); assertEquals(stops, owner.state.value.stops)
    }

    @Test fun quickSharedDestinationAndPhonePreviewUseConfiguredValhallaWithoutTomTom() = runBlocking {
        var tomTom = 0
        val configured = ConfiguredRoutePlanner({ TrafficCredentials("synthetic-key", true, true, "stable") },
            { object : RoutePlanner {
                override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences) =
                    itineraryFixture(stops, at = departureAt, transport = transport, preferences = preferences)
            } }, { _, _ -> tomTom++; error("No motorcycle TomTom request") })
        val owner = PhoneDirectionsCoordinator(this, { stops.first().coordinate }, { values, mode -> configured.plan(values, 1000L, mode) }, {},
            planWithPreferences = { values, mode, speed, preferences -> configured.plan(values, 1000L, mode, speed, preferences) })
        val quick = owner.planQuick(stops.first().coordinate, stops.last(), Transport.MOTORCYCLE, emptyMap())
        assertEquals(RouteProvider.VALHALLA, quick.provider)
        owner.enter(1L, listOf(stops.first(), stops.last()), Transport.MOTORCYCLE, locateOnOpen = false)
        owner.preview()!!.join()
        assertEquals(RouteProvider.VALHALLA, owner.state.value.preview?.provider)
        assertEquals(0, tomTom)
    }
}
