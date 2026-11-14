package org.iz.navigation.weather

import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class MotorcycleRoutingPolicyTest {
    private val stops = listOf(RouteStop("Start", WeatherCoordinate(35.0, 25.0)), RouteStop("End", WeatherCoordinate(35.01, 25.01)))
    private val credentials = TrafficCredentials("synthetic-key", true, true, "enabled")
    private fun routePlanner(provider: RouteProvider, before: () -> Unit = {}) = object : RoutePlanner, RouteModificationPlanner {
        override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
            before()
            return PlannedRoute("synthetic-${provider.name}", stops, listOf(RouteVertex(stops.first().coordinate, 0.0), RouteVertex(stops.last().coordinate, 100.0)),
                1000.0, 100.0, 1L, transport = transport, provider = provider, preferences = preferences, travelSpeedKmh = travelSpeedKmh)
        }
        override suspend fun planModified(request: RouteModificationRequest) =
            plan(request.stops, request.departureAt, request.transport, request.travelSpeedKmh, request.preferences)
    }

    @Test fun motorcycleWithAuthorizedTrafficUsesValhallaForRouteAlternativesAndModification() = runBlocking {
        var tomTom = 0
        var valhalla = 0
        val configured = ConfiguredRoutePlanner({ credentials }, { routePlanner(RouteProvider.VALHALLA) { valhalla++ } },
            { _, _ -> routePlanner(RouteProvider.TOMTOM) { tomTom++ } })
        val preferences = RoutePreferences.defaults(Transport.MOTORCYCLE)
        val route = configured.plan(stops, 1000L, Transport.MOTORCYCLE, null, preferences)
        val alternatives = configured.alternatives(stops, 1000L, Transport.MOTORCYCLE, null, preferences)
        val modified = configured.planModified(RouteModificationRequest(stops, 1000L, Transport.MOTORCYCLE, null, preferences))
        assertEquals(listOf(RouteProvider.VALHALLA, RouteProvider.VALHALLA, RouteProvider.VALHALLA),
            listOf(route.provider, alternatives.routes.single().provider, modified.provider))
        assertEquals(0, tomTom)
        assertEquals(3, valhalla)
        assertNull(route.traffic)
        assertFalse(route.trafficUnavailableReason.orEmpty().contains("TomTom"))
    }

    @Test fun motorcycleValhallaFailureIsNotReplacedByTomTom() {
        var tomTom = 0
        val configured = ConfiguredRoutePlanner({ credentials }, { routePlanner(RouteProvider.VALHALLA) { throw RouteServiceException("Synthetic Valhalla failure") } },
            { _, _ -> routePlanner(RouteProvider.TOMTOM) { tomTom++ } })
        assertThrows(RouteServiceException::class.java) { runBlocking { configured.plan(stops, 1000L, Transport.MOTORCYCLE) } }
        assertEquals(0, tomTom)
    }

    @Test fun authorizedCarAndPassengerKeepTomTomRouting() = runBlocking {
        var tomTom = 0
        val configured = ConfiguredRoutePlanner({ credentials }, { error("Authorized car/passenger must keep provider") },
            { _, _ -> routePlanner(RouteProvider.TOMTOM) { tomTom++ } })
        for (mode in listOf(Transport.CAR, Transport.PASSENGER)) assertEquals(RouteProvider.TOMTOM, configured.plan(stops, 1000L, mode).provider)
        assertEquals(2, tomTom)
    }

    @Test fun directTomTomVerificationRejectsMotorcycleBeforeCredentialsOrNetwork() {
        val verified = VerifiedTomTomRoutePlanner { error("Motorcycle must not read TomTom credentials") }
        val preferences = RoutePreferences.defaults(Transport.MOTORCYCLE)
        assertThrows(RouteServiceException::class.java) { runBlocking { verified.plan(stops, 1000L, Transport.MOTORCYCLE) } }
        assertThrows(RouteServiceException::class.java) { runBlocking { verified.alternatives(stops, 1000L, Transport.MOTORCYCLE, null, preferences) } }
        assertThrows(RouteServiceException::class.java) { runBlocking { verified.planModified(RouteModificationRequest(stops, 1000L, Transport.MOTORCYCLE, null, preferences)) } }
    }

    @Test fun changedMotorcycleEndpointAndCancellationNeverReturnOldResultOrTryTomTom() {
        var settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        val planner = ConfiguredRoutePlanner({ error("Not a route permission for motorcycle") },
            { routePlanner(RouteProvider.VALHALLA) { settings = settings.copy(routeEndpoint = "https://changed.invalid/route") } },
            { _, _ -> error("Must not try TomTom") }, routingSettings = { settings })
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { planner.plan(stops, 1000L, Transport.MOTORCYCLE) } }
        val cancelled = ConfiguredRoutePlanner({ credentials },
            { routePlanner(RouteProvider.VALHALLA) { throw kotlinx.coroutines.CancellationException("Synthetic cancellation") } },
            { _, _ -> error("Must not try TomTom") })
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { cancelled.plan(stops, 1000L, Transport.MOTORCYCLE) } }
    }
}
