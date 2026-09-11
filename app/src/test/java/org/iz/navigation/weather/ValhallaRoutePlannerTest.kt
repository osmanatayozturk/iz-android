package org.iz.navigation.weather

import org.iz.navigation.data.Transport
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject

class ValhallaRoutePlannerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun multipleLegsPreserveEveryShapeAndOffsetManeuversAfterTimedStationaryVertex() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"trip":{"summary":{"length":0.444},"legs":[
                  {"shape":"???o}@?o}@","maneuvers":[
                    {"type":1,"instruction":"Başlayın","time":5,"begin_shape_index":0,"end_shape_index":0},
                    {"type":10,"instruction":"Sağa dönün","verbal_pre_transition_instruction":"Sağa dönün","street_names":["İnönü"],"time":20,"begin_shape_index":0,"end_shape_index":2}]},
                  {"shape":"?_|B?o}@?o}@","maneuvers":[
                    {"type":26,"roundabout_exit_count":2,"time":30,"begin_shape_index":0,"end_shape_index":2}]}
                ]}}
            """.trimIndent()))
            val result = planner(server, temporary.newFolder()).plan(stops() + RouteStop("C", WeatherCoordinate(0.0, .004)), 1L)
            assertEquals(listOf(0.0, 5.0, 15.0, 25.0, 40.0, 55.0), result.vertices.map { it.elapsedSeconds })
            assertEquals(listOf(0, 1, 3), result.maneuvers.map { it.beginShapeIndex })
            assertEquals(listOf(1, 3, 5), result.maneuvers.map { it.endShapeIndex })
            assertEquals(listOf(0.0, 5.0, 25.0), result.maneuvers.map { it.beginElapsedSeconds })
            assertEquals(listOf(0.0, 25.0, 55.0), result.stopElapsedSeconds)
            assertEquals(listOf("İnönü"), result.maneuvers[1].streetNames)
            assertEquals(2, result.maneuvers.last().roundaboutExit)
        }
    }

    @Test fun eachTravelModeRequestsAccessibleRoadsAndPreservesItsIdentity() = runBlocking {
        val modes = listOf(
            Transport.CAR to "auto",
            Transport.MOTORCYCLE to "motorcycle",
            Transport.BICYCLE to "bicycle",
            Transport.WALK to "pedestrian",
            Transport.RUN to "pedestrian",
            Transport.PASSENGER to "auto",
        )
        MockWebServer().use { server ->
            modes.forEach { (transport, costing) ->
                server.enqueue(successfulRoute())
                val route = planner(server, temporary.newFolder(transport.name)).plan(stops(), 1L, transport)
                val body = JSONObject(server.takeRequest().body.readUtf8())

                assertEquals(transport.name, costing, body.getString("costing"))
                assertEquals(transport, route.transport)
                val options = body.getJSONObject("costing_options")
                when (transport) {
                    Transport.BICYCLE -> {
                        assertEquals(18.0, options.getJSONObject("bicycle").getDouble("cycling_speed"), 0.0)
                        assertEquals(18.0, route.travelSpeedKmh!!, 0.0)
                    }
                    Transport.WALK, Transport.RUN -> {
                        val expected = if (transport == Transport.WALK) 5.1 else 10.0
                        assertEquals(expected, options.getJSONObject("pedestrian").getDouble("walking_speed"), 0.0)
                        assertEquals(expected, route.travelSpeedKmh!!, 0.0)
                    }
                    else -> {
                        assertNull(route.travelSpeedKmh)
                        assertFalse(options.has("pedestrian"))
                        assertFalse(options.has("bicycle"))
                    }
                }
            }
        }
    }

    @Test fun chosenPaceIsSentToTheProviderAndRetainedForReroutes() = runBlocking {
        MockWebServer().use { server ->
            listOf(
                Triple(Transport.WALK, "pedestrian", 3.8),
                Triple(Transport.RUN, "pedestrian", 12.5),
                Triple(Transport.BICYCLE, "bicycle", 24.0),
            ).forEach { (transport, costing, speed) ->
                server.enqueue(successfulRoute())
                val route = planner(server, temporary.newFolder(transport.name)).plan(stops(), 1L, transport, speed)
                val options = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("costing_options").getJSONObject(costing)

                assertEquals(speed, options.getDouble(if (transport == Transport.BICYCLE) "cycling_speed" else "walking_speed"), 0.0)
                assertEquals(speed, route.travelSpeedKmh!!, 0.0)
            }
        }
    }

    @Test fun unknownModeOrInvalidPaceCannotSendAMisleadingRouteRequest() {
        MockWebServer().use { server ->
            val planner = planner(server, temporary.newFolder("invalid-mode"))
            listOf(
                Transport.UNKNOWN to null,
                Transport.WALK to 0.49,
                Transport.RUN to 25.01,
                Transport.BICYCLE to 4.99,
                Transport.BICYCLE to 60.01,
                Transport.RUN to Double.NaN,
                Transport.CAR to 10.0,
                Transport.MOTORCYCLE to 10.0,
                Transport.PASSENGER to 10.0,
            ).forEach { (transport, speed) ->
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { planner.plan(stops(), 1L, transport, speed) }
                }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun routeIdentityDistinguishesModesEvenWhenTheyUseTheSameCostingAndPace() = runBlocking {
        MockWebServer().use { server ->
            listOf(Transport.WALK to Transport.RUN, Transport.CAR to Transport.PASSENGER).forEach { (first, second) ->
                server.enqueue(successfulRoute())
                server.enqueue(successfulRoute())
                val speed = if (first == Transport.WALK) 8.0 else null
                val firstRoute = planner(server, temporary.newFolder(first.name)).plan(stops(), 1L, first, speed)
                val secondRoute = planner(server, temporary.newFolder(second.name)).plan(stops(), 1L, second, speed)

                assertNotEquals(firstRoute.id, secondRoute.id)
            }
        }
    }

    @Test fun maneuverTimesDrivePolylineTimelineIncludingAnIntermediateZeroTimeManeuver() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"trip":{"summary":{"length":0.222,"time":10},"legs":[{
                  "shape":"???o}@?o}@",
                  "maneuvers":[
                    {"time":10,"begin_shape_index":0,"end_shape_index":1},
                    {"time":0,"begin_shape_index":1,"end_shape_index":2}
                  ]
                }]}}
            """.trimIndent()))
            val planner = planner(server, temporary.newFolder("route"))

            val result = planner.plan(stops(), 1_700_000_000_000L)

            assertEquals(listOf(0.0, 10.0, 10.0), result.vertices.map { it.elapsedSeconds })
            assertEquals(listOf(0.0, 10.0), result.stopElapsedSeconds)
            assertEquals(222.0, result.distanceMeters, 0.001)
            assertEquals(10.0, result.durationSeconds, 0.0)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("\"costing\":\"motorcycle\""))
        }
    }

    @Test fun malformedPolylineIsReportedAsLocalizedRouteFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"trip":{"summary":{"length":1,"time":1},"legs":[{"shape":"_","maneuvers":[]}]}}"""))
            val error = assertThrows(RouteServiceException::class.java) {
                runBlocking { planner(server, temporary.newFolder("bad-shape")).plan(stops(), 1L) }
            }
            assertTrue(error.message!!.contains("Rota"))
        }
    }

    @Test fun retryAfterCooldownIsPersistedAcrossPlannerInstancesWithoutAnotherRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("busy"))
            val state = temporary.newFolder("cooldown")
            val first = planner(server, state)
            assertThrows(RouteServiceException::class.java) { runBlocking { first.plan(stops(), 1L) } }
            val second = planner(server, state)
            assertThrows(RouteServiceException::class.java) { runBlocking { second.plan(stops(), 1L) } }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun serviceUnavailableWithImmediateRetryHintIsNotAutomaticallyReplayed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
            server.enqueue(MockResponse().setBody("unexpected replay"))

            assertThrows(RouteServiceException::class.java) {
                runBlocking { planner(server, temporary.newFolder("no-replay")).plan(stops(), 1L) }
            }

            assertEquals(1, server.requestCount)
        }
    }

    private fun planner(server: MockWebServer, directory: File) = ValhallaRoutePlanner(
        endpoint = server.url("/route").toString(),
        storageDirectory = directory,
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        clock = { 1_700_000_000_000L },
    )

    private fun stops() = listOf(
        RouteStop("A", WeatherCoordinate(0.0, 0.0)),
        RouteStop("B", WeatherCoordinate(0.0, 0.002)),
    )

    private fun successfulRoute() = MockResponse().setBody("""
        {"trip":{"summary":{"length":0.222,"time":10},"legs":[{
          "shape":"???o}@?o}@",
          "maneuvers":[{"time":10,"begin_shape_index":0,"end_shape_index":2}]
        }]}}
    """.trimIndent())
}
