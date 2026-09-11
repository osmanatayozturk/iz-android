package com.atay.iz.ui

import com.atay.iz.data.*
import com.atay.iz.navigation.NavigationState
import com.atay.iz.weather.*
import org.junit.Assert.*
import org.junit.Test

class NavigationMapPresentationTest {
    private val a = RouteStop("A", WeatherCoordinate(41.0, 29.0))
    private val b = RouteStop("B", WeatherCoordinate(41.1, 29.1))
    private val route = PlannedRoute("old", listOf(a, b), listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 60.0)), 1000.0, 60.0, 1L)

    @Test fun pendingGpsSessionStillShowsLiveControls() {
        val result = navigationMapPresentation(PhoneDirectionsState(), NavigationState(sessionId = "pending"), emptyList(), false)
        assertTrue(result.sessionActive)
    }

    @Test fun editedEndpointsShowBeforePreviewAndReplaceOldGuidanceGeometry() {
        val c = RouteStop("C", WeatherCoordinate(41.2, 29.2))
        val nav = NavigationState(sessionId = "live", guidance = true, route = route)
        val before = navigationMapPresentation(PhoneDirectionsState(origin = a, destination = b), nav, emptyList(), true)
        val after = navigationMapPresentation(PhoneDirectionsState(origin = a, destination = c), nav, emptyList(), true)
        assertEquals(listOf(a, c), after.stops)
        assertNull(after.route)
        assertNotEquals(before.cameraIdentity, after.cameraIdentity)
    }

    @Test fun idleMapNeverDrawsOldRouteOrPreviouslyObservedDiaryPoints() {
        val oldPoint = TrackPoint(journeyId = "old", latitude = 41.0, longitude = 29.0, recordedAt = 1L, accuracy = 5f)
        val result = navigationMapPresentation(PhoneDirectionsState(), NavigationState(route = route), listOf(oldPoint), false)
        assertNull(result.route)
        assertTrue(result.stops.isEmpty())
        assertTrue(result.trail.isEmpty())
        assertFalse(result.sessionActive)
    }
}
