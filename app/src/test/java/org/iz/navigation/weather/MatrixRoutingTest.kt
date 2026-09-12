package org.iz.navigation.weather

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.data.Transport
import java.util.concurrent.TimeUnit

class MatrixRoutingTest {
    @Test fun motorcycleProposalUsesOneComparisonInstantAndFullMotorcycleRoutes() = runBlocking {
        val stops = (0..3).map { RouteStop("$it", WeatherCoordinate(0.0, it * .001)) }
        val calls = mutableListOf<Pair<Long, Transport>>()
        var matrixAt = 0L
        val planner = ConfiguredStopOrderPlanner(
            credentials = { TrafficCredentials("fictional-key", false, true, "revision", freeAccountVerified = true, matrixEnabled = true) },
            clock = { 1_000L },
            matrix = { _, authorize, _, at ->
                authorize(); matrixAt = at
                mapOf((0 to 1) to 10.0, (1 to 2) to 10.0, (2 to 3) to 10.0,
                    (0 to 2) to 1.0, (2 to 1) to 1.0, (1 to 3) to 1.0)
            },
            route = { _, authorize, points, at, mode ->
                authorize(); calls += at to mode
                val duration = if (points[1] == stops[1]) 100.0 else 70.0
                PlannedRoute("${calls.size}", points, listOf(RouteVertex(points.first().coordinate, 0.0),
                    RouteVertex(points.last().coordinate, duration)), 100.0, duration, 1_000L,
                    transport = mode, provider = RouteProvider.TOMTOM, effectiveDepartureAt = at)
            },
        )
        val result = requireNotNull(planner.propose(stops, 1_000L, Transport.MOTORCYCLE))
        assertEquals(121_000L, matrixAt)
        assertEquals(listOf(matrixAt to Transport.MOTORCYCLE, matrixAt to Transport.MOTORCYCLE), calls)
        assertEquals(listOf(stops[0], stops[2], stops[1], stops[3]), result.orderedStops)
        assertEquals(30.0, result.savedSeconds, 0.0)
        assertTrue(result.approximateMotorcycle)
    }

    @Test fun unverifiedMatrixEntitlementSendsNothing() = runBlocking {
        var calls = 0
        val planner = ConfiguredStopOrderPlanner(
            credentials = { TrafficCredentials("fictional-key", true, true, "revision") },
            matrix = { _, _, _, _ -> calls++; emptyMap() },
        )
        val stops = (0..3).map { RouteStop("$it", WeatherCoordinate(0.0, it * .001)) }
        try { planner.propose(stops, 200_000L, Transport.CAR); fail("Expected missing entitlement") }
        catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }

    @Test fun rateLimitUsesSharedCooldownWithoutRetryOrKeyLeak() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "30"))
            val client = TomTomMatrixClient("fictional-key", server.url("/routing/matrix/2").toString(),
                OkHttpClient(), { 1_000L }, TrafficRequestGate())
            val stops = (0..3).map { RouteStop("$it", WeatherCoordinate(0.0, it * .001)) }
            repeat(2) {
                try { client.costs(stops, 200_000L); fail("Expected rate limit") }
                catch (error: RouteServiceException) {
                    // Coroutine stack recovery may wrap the already-sanitized exception.
                    generateSequence<Throwable>(error) { it.cause }.forEach { cause ->
                        assertFalse(cause.toString().contains("fictional-key"))
                        assertFalse(cause.toString().contains("/routing/matrix"))
                    }
                }
            }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun directedCostsReorderOnlyIntermediates() {
        val costs = mapOf((0 to 1) to 10.0, (1 to 2) to 10.0, (2 to 3) to 10.0,
            (0 to 2) to 1.0, (2 to 1) to 1.0, (1 to 3) to 1.0)
        assertEquals(listOf(0, 2, 1, 3), fastestStopOrder(4, costs))
    }

    @Test fun missingCellsAreNotZeroAndTiesKeepOriginalOrder() {
        assertEquals(listOf(0, 1, 2, 3), fastestStopOrder(4, emptyMap()))
        val equal = (0..3).flatMap { a -> (0..3).map { b -> (a to b) to 1.0 } }.toMap()
        assertEquals(listOf(0, 1, 2, 3), fastestStopOrder(4, equal))
    }

    @Test fun matrixUsesSmallDirectedBodyAndKeepsFailedCellsUnavailable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[
                {"originIndex":0,"destinationIndex":0,"routeSummary":{"travelTimeInSeconds":20}},
                {"originIndex":1,"destinationIndex":2,"detailedError":{"code":"NO_ROUTE_FOUND"}}]}"""))
            val stops = (0..3).map { RouteStop("$it", WeatherCoordinate(0.0, it * .001)) }
            val costs = TomTomMatrixClient("fictional-key", server.url("/routing/matrix/2").toString(),
                OkHttpClient(), { 1_000L }, TrafficRequestGate()).costs(stops, 200_000L)
            assertEquals(20.0 as Double?, costs[0 to 1])
            assertFalse(costs.containsKey(1 to 3))
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            val json = JSONObject(request.body.readUtf8())
            assertEquals(3, json.getJSONArray("origins").length())
            assertEquals(3, json.getJSONArray("destinations").length())
            assertEquals("car", json.getJSONObject("options").getString("travelMode"))
            assertEquals("live", json.getJSONObject("options").getString("traffic"))
        }
    }
}
