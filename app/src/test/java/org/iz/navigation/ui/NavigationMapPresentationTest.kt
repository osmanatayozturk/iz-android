package org.iz.navigation.ui

import org.iz.navigation.data.*
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.RouteEditOwner
import org.iz.navigation.navigation.RouteEditPreview
import org.iz.navigation.speed.RoadSpeedLimit
import org.iz.navigation.speed.RoadSpeedSource
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class NavigationMapPresentationTest {
    private val a = RouteStop("A", WeatherCoordinate(41.0, 29.0))
    private val b = RouteStop("B", WeatherCoordinate(41.1, 29.1))
    private val route = PlannedRoute("old", listOf(a, b), listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 60.0)), 1000.0, 60.0, 1L)

    @Test fun liveNumericTicksKeepTheCameraIdentityAndRouteGeometry() {
        val live = NavigationState(sessionId = "live", guidance = true, route = route)
        val first = navigationMapPresentation(PhoneDirectionsState(), live, emptyList(), false)
        val updated = live.copy(roadSpeed = RoadSpeedState(ownKmh = 101.0, visible = true),
            progress = NavigationProgress(10.0, 50.0, 800.0, 0.0, 0, 90.0))
        val next = navigationMapPresentation(PhoneDirectionsState(), updated, emptyList(), false)
        assertEquals(first.cameraIdentity, next.cameraIdentity)
        assertSame(first.route, next.route)
        assertEquals(first.stops, next.stops)
    }

    @Test fun compactSpeedPreservesKnownUnknownUnlimitedAndGenericProvenance() {
        val known = RoadSpeedLimit(110.0, RoadSpeedSource.TOMTOM_POSTED, true, 1L, a.coordinate,
            Transport.MOTORCYCLE, "synthetic-road")
        val readout = compactRoadSpeedReadout(RoadSpeedState(100.0, known, true))
        assertEquals("100/110 km/sa", readout.value)
        assertEquals("Genel yol sınırı · TomTom", readout.source)
        assertTrue(readout.description.contains("Hızım 100 kilometre/saat"))
        assertTrue(readout.description.contains("Hız sınırı 110 kilometre/saat"))
        val unknown = compactRoadSpeedReadout(RoadSpeedState(visible = true))
        assertEquals("—/— km/sa", unknown.value)
        assertTrue(unknown.description.contains("Hız sınırı bilinmiyor"))
        val unlimited = compactRoadSpeedReadout(RoadSpeedState(0.0,
            known.copy(valueKmh = null, source = RoadSpeedSource.OSM, genericPosted = false), true))
        assertEquals("0/∞ km/sa", unlimited.value)
        assertEquals("OSM", unlimited.source)
        assertTrue(unlimited.description.contains("Hız sınırı sınırsız"))
    }

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

    private val c = RouteStop("Fuel", WeatherCoordinate(41.15, 29.25))
    private val editedRoute = route.copy(id = "edit", stops = listOf(a, c, b),
        vertices = listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(c.coordinate, 40.0), RouteVertex(b.coordinate, 120.0)),
        distanceMeters = 1800.0, durationSeconds = 120.0, transport = Transport.MOTORCYCLE)
    private fun editPreview(nav: NavigationState, requestId: String = "request-1") = RouteEditPreview(
        requestId, requireNotNull(RouteEditOwner.from(nav)), RouteEditAction.AddStop(c),
        RouteModificationRequest(editedRoute.stops, 1L, Transport.MOTORCYCLE, null, editedRoute.preferences), editedRoute)
    private fun activeNavigation() = NavigationState(sessionId = "live", guidance = true, recording = true,
        route = route, routeRevision = 7, sessionTransport = Transport.MOTORCYCLE)

    @Test fun stopPreviewDisplaysReturnedGeometryAndStopsBeforeCommit() {
        val nav = activeNavigation()
        val ui = PhoneDirectionsState(preview = route)
        val preview = editPreview(nav)
        val shown = navigationMapPresentation(ui, nav, emptyList(), true, preview)
        assertSame(editedRoute, shown.route)
        assertEquals(listOf(a, c, b), shown.stops)
        assertEquals(c.coordinate, shown.route!!.vertices[1].coordinate)
        assertEquals("Yeni rota önizlemesi · Onay bekleniyor", shown.editPreviewLabel)
        assertEquals(preview.id, shown.editPreviewId)
        assertSame(route, nav.route)
        assertTrue(nav.recording)
        assertSame(route, ui.preview)
        assertTrue(shown.trail.isEmpty())
        assertFalse(phoneDrivingCameraEnabled(nav, true))
    }

    @Test fun staleOrIneligibleEditCannotOverrideTheCurrentPlanner() {
        val nav = activeNavigation()
        val preview = editPreview(nav)
        val plannerRoute = route.copy(id = "planner")
        val ui = PhoneDirectionsState(preview = plannerRoute)
        val stale = listOf(
            preview.copy(owner = preview.owner.copy(sessionId = "other")),
            preview.copy(owner = preview.owner.copy(routeId = "other")),
            preview.copy(owner = preview.owner.copy(routeRevision = 8)),
            preview.copy(id = ""),
        )
        stale.forEach { candidate ->
            val shown = navigationMapPresentation(ui, nav, emptyList(), true, candidate)
            assertSame(plannerRoute, shown.route)
            assertNull(shown.editPreviewId)
            assertNull(shown.editPreviewLabel)
        }
        listOf(nav.copy(paused = true), nav.copy(arrived = true), nav.copy(simulation = true),
            nav.copy(guidance = false)).forEach { changed ->
            assertSame(plannerRoute, navigationMapPresentation(ui, changed, emptyList(), true, preview).route)
        }
    }

    @Test fun editCameraFitsNewRequestsWithoutResettingOnFixOrProgressTicks() {
        val nav = activeNavigation()
        val preview = editPreview(nav)
        val first = navigationMapPresentation(PhoneDirectionsState(), nav, emptyList(), true, preview)
        val moved = nav.copy(fix = NavigationFix(c.coordinate, 2L, 5f),
            progress = NavigationProgress(10.0, 110.0, 1700.0, 0.0, 0, 50.0))
        val tick = navigationMapPresentation(PhoneDirectionsState(), moved, emptyList(), true, preview)
        assertEquals(first.cameraIdentity, tick.cameraIdentity)
        assertNotEquals(first.cameraIdentity, navigationMapPresentation(PhoneDirectionsState(), nav,
            emptyList(), true, preview.copy(id = "request-2")).cameraIdentity)
    }

    @Test fun closingPlannerRestoresLiveGeometryAndDrivingCamera() {
        val nav = activeNavigation()
        val shown = navigationMapPresentation(PhoneDirectionsState(), nav, emptyList(), false, editPreview(nav))
        assertSame(route, shown.route)
        assertNull(shown.editPreviewLabel)
        assertNull(shown.editPreviewId)
        assertEquals("session:live", shown.cameraIdentity)
        assertTrue(phoneDrivingCameraEnabled(nav, false))
    }

    @Test fun onlineEditNeverBorrowsTheActivePreparedMapEvenWithAnEqualRoute() {
        val nav = activeNavigation().copy(preparedRouteFingerprint = "a".repeat(64))
        val live = navigationMapPresentation(PhoneDirectionsState(), nav, emptyList(), false)
        assertTrue(preparedNavigationMapApplies(live, nav))
        val equalRoutePreview = editPreview(nav).copy(route = route)
        val edit = navigationMapPresentation(PhoneDirectionsState(), nav, emptyList(), true, equalRoutePreview)
        assertFalse(preparedNavigationMapApplies(edit, nav))
        val equalIdDifferentGeometry = navigationMapPresentation(PhoneDirectionsState(preview = editedRoute.copy(id = route.id)),
            nav, emptyList(), true)
        assertFalse(preparedNavigationMapApplies(equalIdDifferentGeometry, nav))
        assertFalse(preparedNavigationMapApplies(live, nav.copy(preparedRouteFingerprint = null)))
        assertFalse(preparedNavigationMapApplies(live, nav.copy(guidance = false)))
    }
}
