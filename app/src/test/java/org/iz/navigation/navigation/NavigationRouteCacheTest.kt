package org.iz.navigation.navigation

import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NavigationRouteCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun tomTomGeometryIsNeverWrittenToDisk() {
        val dir = temp.newFolder()
        val cache = NavigationRouteCache(dir)
        val point = WeatherCoordinate(40.0, 29.0)
        val route = PlannedRoute("private-tomtom", listOf(RouteStop("A", point), RouteStop("B", point)),
            listOf(RouteVertex(point, 0.0), RouteVertex(point, 3.0)), 0.0, 3.0, 123,
            provider = RouteProvider.TOMTOM, traffic = RouteTrafficInfo(123, 1.0, 2.0))
        cache.write(route)
        assertNull(cache.read())
        assertFalse(java.io.File(dir, "route.json").exists())
    }
    @Test fun sixStopApproachRouteCanBeRestored() {
        val points = (0..5).map { WeatherCoordinate(40.0 + .001 * it, 29.0) }
        val route = PlannedRoute("six-stops", points.mapIndexed { i, p -> RouteStop("$i", p) },
            points.mapIndexed { i, p -> RouteVertex(p, i.toDouble()) }, 500.0, 5.0, 123,
            stopElapsedSeconds = (0..5).map { it.toDouble() })
        val cache = NavigationRouteCache(temp.newFolder())
        cache.write(route)
        assertEquals(route, cache.read())
    }
    @Test fun cacheRoundTripsManeuversAndClearRemovesRoute() {
        val p = WeatherCoordinate(40.0, 29.0)
        val route = PlannedRoute("cached", listOf(RouteStop("A", p), RouteStop("B", p)),
            listOf(RouteVertex(p, 0.0), RouteVertex(p, 3.0)), 0.0, 3.0, 123,
            maneuvers = listOf(RouteManeuver(26, "Dönel kavşağa girin", "İkinci çıkış", listOf("İnönü"), 0, 1, 0.0, 3.0, 2)))
        val dir = temp.newFolder()
        NavigationRouteCache(dir).write(route)
        assertEquals(route, NavigationRouteCache(dir).read())
        NavigationRouteCache(dir).clear()
        assertNull(NavigationRouteCache(dir).read())
    }
    @Test fun corruptAndOversizedPayloadAreIgnored() {
        val dir = temp.newFolder()
        val cache = NavigationRouteCache(dir)
        java.io.File(dir, "route.json").writeText("{bad json")
        assertNull(cache.read())
        java.io.File(dir, "route.json").writeBytes(ByteArray(8_000_001))
        assertNull(cache.read())
    }

    @Test fun oldPayloadDefaultsToNoManeuversAndInvalidIndexesAreRejected() {
        val dir = temp.newFolder()
        val file = java.io.File(dir, "route.json")
        val json = org.json.JSONObject("""{"version":1,"id":"old","stops":[{"label":"A","lat":0,"lon":0},{"label":"B","lat":0,"lon":0.01}],"vertices":[{"lat":0,"lon":0,"elapsed":0},{"lat":0,"lon":0.01,"elapsed":10}],"distance":1112,"duration":10,"createdAt":1}""")
        file.writeText(json.toString())
        assertEquals(emptyList<RouteManeuver>(), NavigationRouteCache(dir).read()!!.maneuvers)
        json.put("maneuvers", org.json.JSONArray("""[{"type":10,"begin":0,"end":999,"beginTime":0,"endTime":10}]"""))
        file.writeText(json.toString())
        assertNull(NavigationRouteCache(dir).read())
        json.put("version", 2)
        file.writeText(json.toString())
        assertNull(NavigationRouteCache(dir).read())
    }
}
