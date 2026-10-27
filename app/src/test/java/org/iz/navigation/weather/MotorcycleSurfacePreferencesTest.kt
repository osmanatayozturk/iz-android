package org.iz.navigation.weather

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationRouteCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotorcycleSurfacePreferencesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun absentSurfaceChoiceDefaultsOnlyMotorcycleAndKeepsExplicitOldChoices() {
        for (mode in Transport.entries.filter { it != Transport.UNKNOWN }) {
            val defaults = RoutePreferences.defaults(mode)
            assertEquals(mode == Transport.MOTORCYCLE, defaults.avoidUnpaved)
            val legacy = JSONObject("""{"avoidHighways":true,"avoidTolls":true,"avoidFerries":true}""")
            val restored = routePreferences(legacy, defaults)
            assertTrue(restored.avoidHighways && restored.avoidTolls && restored.avoidFerries)
            assertEquals(mode == Transport.MOTORCYCLE, restored.avoidUnpaved)
            assertFalse(routePreferences(legacy.put("avoidUnpaved", false), defaults).avoidUnpaved)
        }
    }

    @Test fun codecFourRoundTripsFalseAndReadsOldSettingsWithoutResettingChoices() {
        val defaults = defaultWeatherSettings(Transport.MOTORCYCLE)
        val settings = defaults.copy(preferences = defaults.preferences.copy(avoidHighways = true, avoidTolls = true, avoidUnpaved = false))
        val current = JSONObject(WeatherSettingsCodec.encode(settings))
        assertEquals(4, current.getInt("version"))
        assertEquals(settings, WeatherSettingsCodec.decode(current.toString(), defaults))
        current.put("version", 3).getJSONObject("preferences").remove("avoidUnpaved")
        val old = WeatherSettingsCodec.decode(current.toString(), defaults)
        assertTrue(old.preferences.avoidUnpaved && old.preferences.avoidHighways && old.preferences.avoidTolls)
        assertEquals(false, routePreferences(settings.preferences.json(), defaults.preferences).avoidUnpaved)
        assertThrows(IllegalArgumentException::class.java) {
            routePreferences(JSONObject("""{"avoidUnpaved":"false"}"""), defaults.preferences)
        }
    }

    @Test fun motorcycleRequestsSameAvoidanceForPlanAndAlternativesWithoutCarFallback() = runBlocking {
        MockWebServer().use { server ->
            val preferences = RoutePreferences.defaults(Transport.MOTORCYCLE)
            repeat(2) { server.enqueue(MockResponse().setBody(tomTomFixture())) }
            val planner = TomTomRoutePlanner("synthetic-key", server.url("/routing/1/calculateRoute/").toString(),
                OkHttpClient(), { 500_000L }, TrafficRequestGate())
            val first = planner.plan(trafficStops(), 1L, Transport.MOTORCYCLE, null, preferences)
            val second = planner.alternatives(trafficStops(), 1L, Transport.MOTORCYCLE, null, preferences).routes.single()
            for (route in listOf(first, second)) {
                assertEquals(preferences, route.preferences)
                assertTrue(route.providerWarnings.isNotEmpty())
                val url = server.takeRequest().requestUrl!!
                assertEquals("motorcycle", url.queryParameter("travelMode"))
                assertEquals("fastest", url.queryParameter("routeType"))
                assertEquals("true", url.queryParameter("traffic"))
                assertEquals(listOf("unpavedRoads"), url.queryParameterValues("avoid"))
            }
        }
    }

    @Test fun valhallaExclusionIsRequestedAndEndpointSurfaceUncertaintyIsNotHidden() = runBlocking {
        MockWebServer().use { server ->
            val response = """{"trip":{"summary":{"length":0.222,"has_highway":false},"legs":[{"shape":"???o}@?o}@","summary":{"has_highway":false},"maneuvers":[{"time":10,"begin_shape_index":0,"end_shape_index":2}]}]}}"""
            repeat(2) { server.enqueue(MockResponse().setBody(response)) }
            val planner = ValhallaRoutePlanner(server.url("/route").toString(), temp.newFolder(), OkHttpClient(), { 1L })
            for (avoid in listOf(true, false)) {
                val route = planner.plan(trafficStops(), 1L, Transport.MOTORCYCLE, null, RoutePreferences(avoidUnpaved = avoid))
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("motorcycle", body.getString("costing"))
                val options = body.getJSONObject("costing_options").getJSONObject("motorcycle")
                assertEquals(avoid, options.optBoolean("exclude_unpaved"))
                assertEquals(avoid, route.providerWarnings.isNotEmpty())
                assertNull(route.traffic)
            }
        }
    }

    @Test fun cacheThreeRetainsSurfaceChoiceAndWarningsButOldCacheDoesNotClaimProof() {
        val dir = temp.newFolder()
        val p = WeatherCoordinate(40.0, 29.0)
        val route = PlannedRoute("surface", listOf(RouteStop("A", p), RouteStop("B", p)),
            listOf(RouteVertex(p, 0.0), RouteVertex(p, 3.0)), 0.0, 3.0, 123,
            preferences = RoutePreferences(avoidUnpaved = false), providerWarnings = listOf("Surface not confirmed"))
        val cache = NavigationRouteCache(dir)
        cache.write(route)
        val file = java.io.File(dir, "route.json")
        assertEquals(3, JSONObject(file.readText()).getInt("version"))
        assertEquals(route, cache.read())
        val legacy = JSONObject(file.readText()).put("version", 2)
        legacy.getJSONObject("preferences").remove("avoidUnpaved")
        file.writeText(legacy.toString())
        assertTrue(cache.read()!!.preferences.avoidUnpaved)
        assertTrue(cache.read()!!.providerWarnings.any { it.contains("yeniden", ignoreCase = true) })
    }

    @Test fun unsupportedSavedSurfaceChoiceIsPreservedButNeverClaimedAsRequested() {
        val selected = RoutePreferences(avoidUnpaved = true)
        val restored = routePreferences(selected.json(), RoutePreferences.defaults(Transport.WALK))
        assertTrue(restored.avoidUnpaved)
        assertTrue(surfacePreferenceWarnings(restored, RouteProvider.VALHALLA, Transport.WALK).single().contains("uygulanamadı"))
    }
    @Test fun matrixCannotSilentlyDropRequestedSurfaceAvoidance() {
        assertNotNull(RoutePreferences(avoidUnpaved = true).matrixUnavailableReason(Transport.MOTORCYCLE))
        assertNull(RoutePreferences(avoidUnpaved = false).matrixUnavailableReason(Transport.MOTORCYCLE))
    }
}