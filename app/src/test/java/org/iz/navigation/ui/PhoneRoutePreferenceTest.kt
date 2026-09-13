package org.iz.navigation.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class PhoneRoutePreferenceTest {
    @Test fun savedPlanPreferencesAndSpeedStayLocalAndReachPreviewAndStart() = runBlocking {
        val preferences = RoutePreferences(avoidFerries = true)
        var saved = 0
        val requests = mutableListOf<Pair<Double?, RoutePreferences>>()
        var activated: PlannedRoute? = null
        val coordinator = PhoneDirectionsCoordinator(this, { stops[0].coordinate }, { _, _ -> error("Old callback") }, { activated = it },
            savePreferences = { _, _ -> saved++ }, planWithPreferences = { points, mode, speed, prefs ->
                requests += speed to prefs; route(mode).copy(stops = points, preferences = prefs) })
        coordinator.enter(1L, initialPlan = SavedWeatherPlan(stops, 1L, Transport.WALK, preferences, false, 4.0), locateOnOpen = false)
        coordinator.preview()!!.join(); coordinator.start()!!.join()
        assertEquals(listOf(4.0 to preferences, 4.0 to preferences), requests)
        assertEquals(preferences, activated!!.preferences)
        assertEquals(0, saved)
    }

    @Test fun pendingAlternativeCannotRestoreEditedPreferenceAndSelectedPathMustBeRevalidated() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var activated = false
        val coordinator = PhoneDirectionsCoordinator(this, { stops[0].coordinate }, { _, mode -> route(mode) }, { activated = true },
            routeAlternatives = { _, mode, _, _ -> entered.complete(Unit); release.await(); RouteAlternatives(listOf(route(mode))) })
        coordinator.enter(1L, stops, Transport.CAR, false)
        coordinator.preview()!!.join()
        val pending = coordinator.requestAlternatives()!!
        entered.await()
        coordinator.setPreferences(RoutePreferences(avoidFerries = true))
        release.complete(Unit); pending.join()
        assertTrue(coordinator.state.value.alternatives.isEmpty())
        assertNull(coordinator.state.value.preview)
        assertFalse(activated)
    }

    @Test fun alternativeSelectionCannotSilentlyStartAnotherGeometry() = runBlocking {
        var activated = false
        var alternateCalls = 0
        val primary = route(Transport.CAR)
        val alternative = primary.copy(id = "alternate", vertices = listOf(primary.vertices[0],
            RouteVertex(WeatherCoordinate(.001, .005), 5.0), primary.vertices[1]))
        val coordinator = PhoneDirectionsCoordinator(this, { stops[0].coordinate }, { _, _ -> primary }, { activated = true },
            routeAlternatives = { _, _, _, _ -> RouteAlternatives(if (++alternateCalls == 1) listOf(primary, alternative) else listOf(primary)) })
        coordinator.enter(1L, stops, Transport.CAR, false)
        coordinator.preview()!!.join(); coordinator.requestAlternatives()!!.join()
        coordinator.selectAlternative("alternate")
        coordinator.start()!!.join()
        assertFalse(activated); assertNotNull(coordinator.state.value.message)
        assertEquals(2, alternateCalls)
    }
    private val stops = listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .01)))
    private fun route(mode: Transport) = PlannedRoute("synthetic", stops,
        listOf(RouteVertex(stops[0].coordinate, 0.0), RouteVertex(stops[1].coordinate, 10.0)), 1000.0, 10.0, 1L, transport = mode)

    @Test fun walkingPreviewCannotOfferAnUnclassifiedRouteForStart() = runBlocking {
        val coordinator = PhoneDirectionsCoordinator(this, { stops[0].coordinate }, { _, mode -> route(mode) }, {})
        coordinator.enter(1L, stops, Transport.WALK, locateOnOpen = false)
        coordinator.preview()!!.join()
        assertNull(coordinator.state.value.preview)
        assertNotNull(coordinator.state.value.message)
    }
}
