package org.iz.navigation.weather

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.iz.navigation.data.Transport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HighwayPreferenceRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val stops = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .002)))

    @Test fun walkingRequestsMotorwayExclusionForTheRouteAndEveryStop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture(false)))
            planner(server).plan(stops, 1L, Transport.WALK)
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(request.getJSONObject("costing_options").getJSONObject("pedestrian").optBoolean("exclude_highways"))
            val locations = request.getJSONArray("locations")
            for (i in 0 until locations.length()) assertEquals("trunk", locations.getJSONObject(i).getJSONObject("search_filter").getString("max_road_class"))
        }
    }

    @Test fun walkingRejectsMotorwayEvenWhenTheRequestWasAccepted() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture(true)))
            assertThrows(RouteServiceException::class.java) { runBlocking { planner(server).plan(stops, 1L, Transport.WALK) } }
        }
    }

    @Test fun missingHighwayEvidenceIsNotProofOfAMotorwayFreeRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture(null)))
            assertThrows(RouteServiceException::class.java) { runBlocking { planner(server).plan(stops, 1L, Transport.BICYCLE) } }
        }
    }

    private fun planner(server: MockWebServer) = ValhallaRoutePlanner(server.url("/route").toString(), temporary.newFolder(), OkHttpClient(), { 500_000L })
    private fun fixture(highway: Boolean?) = """{"trip":{"summary":{"length":0.222${highway?.let { ",\"has_highway\":$it" }.orEmpty()}},"legs":[{"summary":{"length":0.222${highway?.let { ",\"has_highway\":$it" }.orEmpty()}},"shape":"???o}@?o}@","maneuvers":[{"type":10,"instruction":"Devam","time":10,"begin_shape_index":0,"end_shape_index":2}]}]}}"""
}
