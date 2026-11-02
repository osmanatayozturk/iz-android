package org.iz.navigation.weather

import org.iz.navigation.data.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RouteModificationProvidersTest {
    private val stops = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, 0.002)))
    private val area = RouteAvoidArea(1.0, 1.0, 1.01, 1.01)
    private fun request() = RouteModificationRequest(stops, 1L, Transport.CAR, null, RoutePreferences(), listOf(area))
    private fun route() = PlannedRoute("fixture", stops, listOf(RouteVertex(stops[0].coordinate, 0.0),
        RouteVertex(stops[1].coordinate, 20.0)), 222.0, 20.0, 1L, transport = Transport.CAR)
    private inner class Modified(val fail: Boolean = false, val beforeReturn: () -> Unit = {}) : RoutePlanner, RouteModificationPlanner {
        val modifications = mutableListOf<RouteModificationRequest>()
        var ordinary = 0
        override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
            travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
            ordinary++
            if (fail) throw RouteServiceException("fixture failure")
            return route()
        }
        override suspend fun planModified(request: RouteModificationRequest): PlannedRoute {
            modifications += request
            beforeReturn()
            if (fail) throw RouteServiceException("fixture failure")
            return route().copy(avoidAreas = request.avoidAreas)
        }
    }
    @Test fun trafficFailurePreservesExclusionsInModifiedFallbackInsteadOfOrdinaryPlan() = runBlocking {
        val traffic = Modified(fail = true); val fallback = Modified()
        val result = ConfiguredRoutePlanner({ TrafficCredentials("fixture-key", true, true, "1") },
            { fallback }, { _, _ -> traffic }).planModified(request())
        assertEquals(1, fallback.modifications.size)
        assertEquals(listOf(area), fallback.modifications.single().avoidAreas)
        assertEquals(0, fallback.ordinary)
        assertEquals(listOf(area), result.avoidAreas)
    }
    @Test fun fallbackWithoutModificationCapabilityRejectsWithoutDroppingConstraint() {
        var ordinaryCalls = 0
        val unsupported = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute { ordinaryCalls++; return route() }
        }
        val planner = ConfiguredRoutePlanner({ TrafficCredentials(null, false, false, "1") },
            { unsupported }, { _, _ -> error("No traffic request allowed") })
        assertThrows(RouteServiceException::class.java) { runBlocking { planner.planModified(request()) } }
        assertEquals(0, ordinaryCalls)
    }
    @Test fun changedCredentialsAfterModifiedResponseCancelWithoutFallback() {
        var credentials = TrafficCredentials("fixture-key", true, true, "1")
        val traffic = Modified { credentials = TrafficCredentials(null, false, false, "2") }
        val fallback = Modified()
        val planner = ConfiguredRoutePlanner({ credentials }, { fallback }, { _, _ -> traffic })
        assertThrows(CancellationException::class.java) { runBlocking { planner.planModified(request()) } }
        assertEquals(0, fallback.ordinary + fallback.modifications.size)
    }
    @Test fun ignoredExclusionInBothActualResponsesCannotBePublished() {
        val traffic = Modified(); val fallback = Modified()
        val planner = ConfiguredRoutePlanner({ TrafficCredentials("fixture-key", true, true, "1") },
            { fallback }, { _, _ -> traffic })
        val crossing = request().copy(avoidAreas = listOf(RouteAvoidArea(-0.0001, 0.0009, 0.0001, 0.0011)))
        assertThrows(RouteServiceException::class.java) { runBlocking { planner.planModified(crossing) } }
        assertEquals(1, traffic.modifications.size)
        assertEquals(1, fallback.modifications.size)
        assertEquals(crossing.avoidAreas, fallback.modifications.single().avoidAreas)
    }

    @Test fun stopEditWithNoExclusionsCanUseOrdinaryFallbackWithoutPretendingToAvoid() = runBlocking {
        var calls = 0
        val ordinary = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute { calls++; return route() }
        }
        val planner = ConfiguredRoutePlanner({ TrafficCredentials(null, false, false, "1") },
            { ordinary }, { _, _ -> error("No traffic request allowed") })
        val result = planner.planModified(request().copy(avoidAreas = emptyList()))
        assertEquals(1, calls)
        assertTrue(result.avoidAreas.isEmpty())
    }
}
