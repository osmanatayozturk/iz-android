package org.iz.navigation.integration

import org.iz.navigation.data.TrackPoint
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectionsMapTest {
    @Test fun endpointsRemainAAndBWithIntermediateStops() {
        val stops = listOf(stop(41.0), stop(41.01), stop(41.02))
        val features = JSONObject(routeStopsGeoJson(stops)).getJSONArray("features")
        assertEquals("A", features.getJSONObject(0).getJSONObject("properties").getString("marker"))
        assertEquals("1", features.getJSONObject(1).getJSONObject("properties").getString("marker"))
        assertEquals("B", features.getJSONObject(2).getJSONObject("properties").getString("marker"))
        assertEquals(41.02, features.getJSONObject(2).getJSONObject("geometry").getJSONArray("coordinates").getDouble(1), 0.0)
    }

    @Test fun recordedTrackNeverBridgesMissingFixAndKeepsSeparateGeometry() {
        val points = listOf(point(41.0, 0), point(41.00001, 1_000),
            point(41.00002, 2_000).copy(accuracy = 500f), point(41.00003, 3_000), point(41.00004, 4_000))
        val features = JSONObject(recordedRouteGeoJson(points)).getJSONArray("features")
        assertEquals(2, features.length())
        assertEquals(2, features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").length())
        assertEquals(2, features.getJSONObject(1).getJSONObject("geometry").getJSONArray("coordinates").length())
    }

    @Test fun userPanStopsFollowingUntilExplicitRecenterAndTrafficRefreshDoesNotReframe() {
        val camera = RouteMapCameraPolicy()
        assertTrue(camera.shouldFrame("A-B", hasCoordinates = true))
        camera.followCurrent()
        assertTrue(camera.following)
        camera.userGesture()
        assertFalse(camera.following)
        assertFalse(camera.shouldFrame("A-B", hasCoordinates = true))
        camera.followCurrent()
        assertTrue(camera.following)
        assertTrue(camera.shouldFrame("A-C", hasCoordinates = true))
        assertFalse(camera.following)
    }

    @Test fun emptyInitialMapStillFramesWhenOpeningGpsArrives() {
        val camera = RouteMapCameraPolicy()
        assertFalse(camera.shouldFrame("opening", hasCoordinates = false))
        assertTrue(camera.shouldFrame("opening", hasCoordinates = true))
        assertFalse(camera.shouldFrame("opening", hasCoordinates = true))
    }

    @Test fun changingLiveOriginAfterPanKeepsCameraButExplicitNewPlanFrames() {
        val camera = RouteMapCameraPolicy()
        val original = listOf(stop(41.0), stop(41.02))
        val refreshed = listOf(stop(41.01), stop(41.02))
        assertTrue(camera.shouldFrame(routeMapCameraIdentity("journey-1", original), true))
        camera.userGesture()
        assertFalse(camera.shouldFrame(routeMapCameraIdentity("journey-1", refreshed), true))
        assertTrue(camera.shouldFrame(routeMapCameraIdentity("preview-new", refreshed), true))
    }

    private fun stop(latitude: Double) = RouteStop("Stop", WeatherCoordinate(latitude, 29.0))
    private fun point(latitude: Double, at: Long) = TrackPoint(
        journeyId = "journey", latitude = latitude, longitude = 29.0, recordedAt = at, accuracy = 5f,
    )
}
