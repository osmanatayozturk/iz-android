package org.iz.navigation.weather

import org.iz.navigation.data.Transport
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TomTomRoutePlannerTest {
    @Test fun geometryInstructionOffsetsAndTrafficShareTheSameRoute() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tomTomFixture()))
            val route = planner(server).plan(trafficStops(), 1L, Transport.CAR)
            assertEquals(RouteProvider.TOMTOM, route.provider)
            assertEquals(listOf(0.0, .001, .002, .003, .004), route.vertices.map { it.coordinate.longitude })
            listOf(0.0, 10.0, 20.0, 60.0, 100.0).zip(route.vertices).forEach { (expected, vertex) ->
                assertEquals(expected, vertex.elapsedSeconds, 0.000_001)
            }
            assertEquals(listOf(0.0, 20.0, 100.0), route.maneuvers.map { it.beginElapsedSeconds })
            assertEquals(listOf(0, 2, 4), route.maneuvers.map { it.beginShapeIndex })
            assertEquals(10, route.maneuvers[1].type)
            assertEquals("Sağa dönün", route.maneuvers[1].verbalInstruction)
            assertEquals(30.0, route.traffic!!.delaySeconds, 0.0)
            assertEquals(70.0, route.traffic!!.noTrafficDurationSeconds!!, 0.0)
            assertEquals(500_000L, route.traffic!!.fetchedAt)
            val url = server.takeRequest().requestUrl!!
            assertEquals("true", url.queryParameter("traffic"))
            assertEquals("all", url.queryParameter("computeTravelTimeFor"))
            assertEquals("text", url.queryParameter("instructionsType"))
            assertEquals("polyline", url.queryParameter("routeRepresentation"))
            assertEquals("car", url.queryParameter("travelMode"))
        }
    }

    @Test fun motorVehicleModesKeepIdentityAndMotorcycleIsExplicitlyExperimental() = runBlocking {
        MockWebServer().use { server ->
            listOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE).forEach { mode ->
                server.enqueue(MockResponse().setBody(tomTomFixture()))
                val route = planner(server).plan(trafficStops(), 1L, mode)
                assertEquals(mode, route.transport)
                assertEquals(mode == Transport.MOTORCYCLE, route.traffic!!.experimental)
                assertEquals(if (mode == Transport.MOTORCYCLE) "motorcycle" else "car", server.takeRequest().requestUrl!!.queryParameter("travelMode"))
            }
        }
    }

    @Test fun multipleLegsKeepWaypointTimesAndCompleteProviderGeometry() = runBlocking {
        MockWebServer().use { server ->
            val fixture = JSONObject(tomTomFixture())
            val response = fixture.getJSONArray("routes").getJSONObject(0)
            val points = response.getJSONArray("legs").getJSONObject(0).getJSONArray("points")
            val legs = org.json.JSONArray()
            listOf(0 to 20, 2 to 80).forEach { (start, seconds) ->
                legs.put(JSONObject().put("summary", JSONObject().put("lengthInMeters", 222).put("travelTimeInSeconds", seconds))
                    .put("points", org.json.JSONArray((start..start + 2).map { points.getJSONObject(it) })))
            }
            response.put("legs", legs)
            server.enqueue(MockResponse().setBody(fixture.toString()))
            val stops = listOf(trafficStops().first(), RouteStop("Ara durak", WeatherCoordinate(0.0, .002)), trafficStops().last())
            val route = planner(server).plan(stops, 1L, Transport.CAR)
            assertEquals(listOf(0.0, 20.0, 100.0), route.stopElapsedSeconds)
            assertEquals(listOf(0.0, .001, .002, .003, .004), route.vertices.map { it.coordinate.longitude })
            assertEquals(listOf(0, 2, 4), route.maneuvers.map { it.beginShapeIndex })
        }
    }

    @Test fun instructionInsideShapeSegmentPreservesItsCoordinateAndTiming() = runBlocking {
        MockWebServer().use { server ->
            val fixture = JSONObject(tomTomFixture())
            fixture.getJSONArray("routes").getJSONObject(0).getJSONObject("guidance").getJSONArray("instructions").getJSONObject(1)
                .put("routeOffsetInMeters", 166.5).put("pointIndex", 1)
                .put("point", JSONObject().put("latitude", 0).put("longitude", .0015))
            server.enqueue(MockResponse().setBody(fixture.toString()))
            val route = planner(server).plan(trafficStops(), 1L, Transport.CAR)
            val maneuver = route.maneuvers[1]
            assertEquals(WeatherCoordinate(0.0, .0015), route.vertices[maneuver.beginShapeIndex].coordinate)
            assertEquals(20.0, route.vertices[maneuver.beginShapeIndex].elapsedSeconds, 0.0)
            assertEquals(listOf(0.0, .001, .0015, .002, .003, .004), route.vertices.map { it.coordinate.longitude })
        }
    }

    @Test fun timedStationaryDepartureRetainsZeroStartAndProviderDelay() = runBlocking {
        MockWebServer().use { server ->
            val fixture = JSONObject(tomTomFixture())
            fixture.getJSONArray("routes").getJSONObject(0).getJSONObject("guidance").getJSONArray("instructions").getJSONObject(0)
                .put("travelTimeInSeconds", 5)
            server.enqueue(MockResponse().setBody(fixture.toString()))
            val route = planner(server).plan(trafficStops(), 1L, Transport.CAR)
            assertEquals(listOf(0.0, 5.0), route.vertices.take(2).map { it.elapsedSeconds })
            assertEquals(route.vertices[0].coordinate, route.vertices[1].coordinate)
            assertEquals(1, route.maneuvers.first().beginShapeIndex)
        }
    }

    @Test fun guidanceDistanceRoundingDoesNotDuplicateOrReorderProviderShape() = runBlocking {
        MockWebServer().use { server ->
            val fixture = JSONObject(tomTomFixture())
            fixture.getJSONArray("routes").getJSONObject(0).getJSONObject("guidance").getJSONArray("instructions").getJSONObject(1)
                .put("routeOffsetInMeters", 210)
            server.enqueue(MockResponse().setBody(fixture.toString()))
            val route = planner(server).plan(trafficStops(), 1L, Transport.CAR)
            assertEquals(listOf(0.0, .001, .002, .003, .004), route.vertices.map { it.coordinate.longitude })
            assertEquals(20.0, route.vertices[2].elapsedSeconds, 0.0)
            assertEquals(2, route.maneuvers[1].beginShapeIndex)
        }
    }

    @Test fun providerProgressPreservesConcentratedTrafficBetweenManeuvers() = runBlocking {
        MockWebServer().use { server ->
            val fixture = JSONObject(tomTomFixture())
            fixture.getJSONArray("routes").getJSONObject(0).put("progress", org.json.JSONArray().apply {
                listOf(0 to 0, 1 to 5, 2 to 20, 3 to 90, 4 to 100).forEach { (index, seconds) ->
                    put(JSONObject().put("pointIndex", index).put("travelTimeInSeconds", seconds))
                }
            })
            server.enqueue(MockResponse().setBody(fixture.toString()))
            val route = planner(server).plan(trafficStops(), 1L, Transport.CAR)
            assertEquals(5.0, route.vertices[1].elapsedSeconds, 0.0)
            assertEquals(90.0, route.vertices[3].elapsedSeconds, 0.0)
            assertEquals(100.0, route.durationSeconds, 0.0)
            assertEquals("travelTime", server.takeRequest().requestUrl!!.queryParameter("extendedRouteRepresentation"))
        }
    }

    @Test fun invalidProgressCannotProduceASeeminglyCompleteTrafficTimeline() {
        MockWebServer().use { server ->
            listOf(
                "[{\"pointIndex\":1,\"travelTimeInSeconds\":5},{\"pointIndex\":4,\"travelTimeInSeconds\":100}]",
                "[{\"pointIndex\":0,\"travelTimeInSeconds\":0},{\"pointIndex\":3,\"travelTimeInSeconds\":120},{\"pointIndex\":4,\"travelTimeInSeconds\":100}]",
                "[{\"pointIndex\":0,\"travelTimeInSeconds\":0},{\"pointIndex\":99,\"travelTimeInSeconds\":100}]",
            ).forEach { progress ->
                val fixture = JSONObject(tomTomFixture())
                fixture.getJSONArray("routes").getJSONObject(0).put("progress", org.json.JSONArray(progress))
                server.enqueue(MockResponse().setBody(fixture.toString()))
                assertThrows(RouteServiceException::class.java) { runBlocking { planner(server).plan(trafficStops(), 1L, Transport.CAR) } }
            }
        }
    }

    @Test fun malformedOrMissingGuidanceCannotBecomeTrafficOnlyEta() {
        MockWebServer().use { server ->
            val noGuidance = JSONObject(tomTomFixture()).apply { getJSONArray("routes").getJSONObject(0).remove("guidance") }.toString()
            val backwards = tomTomFixture().replace("\"travelTimeInSeconds\":20", "\"travelTimeInSeconds\":120")
            listOf(noGuidance, backwards, tomTomFixture().replace("\"pointIndex\":2", "\"pointIndex\":999")).forEach { body ->
                server.enqueue(MockResponse().setBody(body))
                assertThrows(RouteServiceException::class.java) { runBlocking { planner(server).plan(trafficStops(), 1L, Transport.CAR) } }
            }
        }
    }

    @Test fun retryAfterPreventsSecondCallAndErrorsNeverExposeCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("fictional-key"))
            val planner = planner(server)
            repeat(2) {
                val error = runCatching { planner.plan(trafficStops(), 1L, Transport.CAR) }.exceptionOrNull()!!
                assertFalse(error.stackTraceToString().contains("fictional-key"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedAndRedirectResponsesAreBoundedWithoutFollowUpRequests() {
        MockWebServer().use { server ->
            listOf(MockResponse().setBody("x".repeat(2_000_001)), MockResponse().setResponseCode(302).setHeader("Location", server.url("/leak"))).forEach { response ->
                server.enqueue(response)
                assertThrows(RouteServiceException::class.java) { runBlocking { planner(server).plan(trafficStops(), 1L, Transport.CAR) } }
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellationCancelsHttpAndDoesNotReturnARoute() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tomTomFixture()).setBodyDelay(3, TimeUnit.SECONDS))
            val request = async(Dispatchers.IO) { planner(server).plan(trafficStops(), 1L, Transport.CAR) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        }
    }

    private fun planner(server: MockWebServer) = TomTomRoutePlanner("fictional-key", server.url("/routing/1/calculateRoute/").toString(), OkHttpClient(), { 500_000L }, TrafficRequestGate())
}

internal fun trafficStops() = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .004)))

internal fun tomTomFixture() = """
    {"routes":[{"summary":{"lengthInMeters":444,"travelTimeInSeconds":100,"trafficDelayInSeconds":30,"noTrafficTravelTimeInSeconds":70},
    "progress":[{"pointIndex":0,"travelTimeInSeconds":0},{"pointIndex":4,"travelTimeInSeconds":100}],
    "legs":[{"summary":{"lengthInMeters":444,"travelTimeInSeconds":100},"points":[
    {"latitude":0,"longitude":0},{"latitude":0,"longitude":0.001},{"latitude":0,"longitude":0.002},{"latitude":0,"longitude":0.003},{"latitude":0,"longitude":0.004}]}],
    "guidance":{"instructions":[
    {"routeOffsetInMeters":0,"travelTimeInSeconds":0,"pointIndex":0,"point":{"latitude":0,"longitude":0},"maneuver":"DEPART","message":"Başlayın"},
    {"routeOffsetInMeters":222,"travelTimeInSeconds":20,"pointIndex":2,"point":{"latitude":0,"longitude":0.002},"maneuver":"TURN_RIGHT","message":"Sağa dönün","street":"İnönü"},
    {"routeOffsetInMeters":444,"travelTimeInSeconds":100,"pointIndex":4,"point":{"latitude":0,"longitude":0.004},"maneuver":"ARRIVE","message":"Vardınız"}]}}]}
""".trimIndent()
