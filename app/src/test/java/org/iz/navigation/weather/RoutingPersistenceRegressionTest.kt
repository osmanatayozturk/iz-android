package org.iz.navigation.weather

import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationRouteCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutingPersistenceRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun explicitFalseForWalkingSurvivesSettingsRoundTrip() {
        val old = JSONObject(WeatherSettingsCodec.encode(defaultWeatherSettings(Transport.WALK)))
            .put("preferences", JSONObject().put("avoidHighways", false).put("avoidTolls", false).put("avoidFerries", true))
        val loaded = WeatherSettingsCodec.decode(old.toString(), defaultWeatherSettings(Transport.WALK))
        val stored = JSONObject(WeatherSettingsCodec.encode(loaded))
        assertTrue(stored.has("preferences"))
        assertEquals(false, stored.getJSONObject("preferences").get("avoidHighways"))
        assertEquals(true, stored.getJSONObject("preferences").get("avoidFerries"))
    }

    @Test fun savedPlanKeepsPreferencesInsteadOfTakingCurrentModeDefaults() {
        val old = JSONObject(WeatherPlanCodec.encode(SavedWeatherPlan(stops(), 10L, Transport.RUN)))
            .put("preferences", JSONObject().put("avoidHighways", false).put("avoidTolls", false).put("avoidFerries", true))
        val loaded = requireNotNull(WeatherPlanCodec.decode(old.toString()))
        val stored = JSONObject(WeatherPlanCodec.encode(loaded))
        assertTrue(stored.has("preferences"))
        assertEquals(false, stored.getJSONObject("preferences").get("avoidHighways"))
    }

    @Test fun oldWalkingCacheWithoutHighwayClassificationCannotBeResumed() {
        val folder = temporary.newFolder()
        val legacy = """{"version":1,"id":"old","distance":222,"duration":10,"createdAt":10,"transport":"WALK","speed":5.1,"stops":[{"label":"A","lat":0,"lon":0},{"label":"B","lat":0,"lon":0.002}],"vertices":[{"lat":0,"lon":0,"elapsed":0},{"lat":0,"lon":0.002,"elapsed":10}],"stopTimes":[],"maneuvers":[]}"""
        java.io.File(folder, "route.json").writeText(legacy)
        assertNull(NavigationRouteCache(folder).read())
    }

    private fun stops() = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .002)))
}
