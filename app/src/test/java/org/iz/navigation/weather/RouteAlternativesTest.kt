package org.iz.navigation.weather

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.iz.navigation.data.Transport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteAlternativesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val stops = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .002)))
    private fun fixture(shape: String = "???o}@?o}@", highway: Boolean? = false) = JSONObject("""{"trip":{"summary":{"length":0.222},"legs":[{"summary":{"length":0.222},"shape":"$shape","maneuvers":[{"time":10,"begin_shape_index":0,"end_shape_index":2}]}]}}""").apply {
        highway?.let { getJSONObject("trip").getJSONObject("summary").put("has_highway", it)
            getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONObject("summary").put("has_highway", it) }
    }
    private fun planner(server: MockWebServer) = ValhallaRoutePlanner(server.url("/route").toString(), temporary.newFolder(), OkHttpClient(), { 500_000L })

    @Test fun valhallaAlternativesDiscardDuplicatesAndDoNotAddTimeDependence() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture().put("alternates", JSONArray().put(fixture()).put(fixture("??o}@?n}@_|B"))).toString()))
            val result = planner(server).alternatives(stops, 1L, Transport.WALK)
            assertEquals(2, result.routes.size)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(2, body.getInt("alternates"))
            assertFalse(body.has("date_time"))
            assertTrue(result.routes.all { it.hasHighway == false })
        }
    }

    @Test fun invalidPrimaryDoesNotHideIndependentlyValidAlternate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture(highway = true).put("alternates", JSONArray().put(fixture())).toString()))
            val result = planner(server).alternatives(stops, 1L, Transport.RUN)
            assertEquals(1, result.routes.size)
            assertEquals(false, result.routes.single().hasHighway)
            assertNotNull(result.message)
        }
    }

    @Test fun tripWarningIsVisibleButExplicitFalseClassificationRemainsUsable() = runBlocking {
        MockWebServer().use { server ->
            val body = fixture().apply { getJSONObject("trip").put("warnings", JSONArray().put(JSONObject().put("code", 208))) }
            server.enqueue(MockResponse().setBody(body.toString()))
            val route = planner(server).plan(stops, 1L, Transport.BICYCLE)
            assertEquals(false, route.hasHighway)
            assertTrue(route.providerWarnings.isNotEmpty())
        }
    }

    @Test fun conflictingLegClassificationAndStringFalseAreRejected() {
        MockWebServer().use { server ->
            listOf(true, "false").forEach { flag ->
                val body = fixture().apply { getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONObject("summary").put("has_highway", flag) }
                server.enqueue(MockResponse().setBody(body.toString()))
                assertThrows(RouteServiceException::class.java) { runBlocking { planner(server).plan(stops, 1L, Transport.WALK) } }
            }
        }
    }

    @Test fun explicitFalseAndMotorizedSoftAvoidanceHaveDifferentPayloads() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture(highway = null).toString()))
            planner(server).plan(stops, 1L, Transport.WALK, null, RoutePreferences(avoidFerries = true))
            val walking = JSONObject(server.takeRequest().body.readUtf8())
            val walkingOptions = walking.getJSONObject("costing_options").getJSONObject("pedestrian")
            assertFalse(walkingOptions.has("exclude_highways")); assertEquals(0, walkingOptions.getInt("use_ferry"))
            assertFalse(walking.getJSONArray("locations").getJSONObject(0).has("search_filter"))
            server.enqueue(MockResponse().setBody(fixture().toString()))
            planner(server).plan(stops, 1L, Transport.MOTORCYCLE, null, RoutePreferences(true, true, true))
            val motor = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("costing_options").getJSONObject("motorcycle")
            assertEquals(0, motor.getInt("use_highways")); assertEquals(0, motor.getInt("use_tolls")); assertEquals(0, motor.getInt("use_ferry"))
            assertFalse(motor.has("exclude_highways"))
        }
    }

    @Test fun tomTomUsesRepeatedAvoidAndBoundsDeduplicatedCandidates() = runBlocking {
        MockWebServer().use { server ->
            val original = JSONObject(tomTomFixture()).getJSONArray("routes").getJSONObject(0)
            server.enqueue(MockResponse().setBody(JSONObject().put("routes", JSONArray().put(original).put(original).put(JSONObject())).toString()))
            val preferences = RoutePreferences(true, true, true)
            val routes = TomTomRoutePlanner("fictional-key", server.url("/routing/1/calculateRoute/").toString(), OkHttpClient(), { 500_000L }, TrafficRequestGate())
                .alternatives(trafficStops(), 1L, Transport.MOTORCYCLE, null, preferences).routes
            assertEquals(1, routes.size); assertEquals(preferences, routes.single().preferences)
            val url = server.takeRequest().requestUrl!!
            assertEquals("2", url.queryParameter("maxAlternatives")); assertEquals("false", url.queryParameter("computeBestOrder"))
            assertEquals(setOf("motorways", "tollRoads", "ferries"), url.queryParameterValues("avoid").toSet())
        }
    }

    @Test fun selectedGeometryMustMatchAndMayNotFallBackToFirstRoute() = runBlocking {
        val first = PlannedRoute("A", stops, listOf(RouteVertex(stops[0].coordinate, 0.0), RouteVertex(stops[1].coordinate, 10.0)), 222.0, 10.0, 1L)
        val different = first.copy(id = "B", vertices = listOf(first.vertices[0], RouteVertex(WeatherCoordinate(.001, .001), 5.0), first.vertices[1]))
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences) = different
            override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences) = RouteAlternatives(listOf(different))
        }
        assertThrows(RouteServiceException::class.java) { runBlocking {
            planner.revalidateSelection(first, stops, 10L, Transport.MOTORCYCLE, null, RoutePreferences())
        } }
        Unit
    }
}
