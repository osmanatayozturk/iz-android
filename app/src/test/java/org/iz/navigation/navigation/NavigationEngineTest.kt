package org.iz.navigation.navigation

import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class NavigationEngineTest {
    private fun route() = PlannedRoute("route", listOf(RouteStop("A", p(0.0)), RouteStop("B", p(.01))),
        (0..10).map { RouteVertex(p(it / 1000.0), it * 10.0) }, 1112.0, 100.0, 1,
        maneuvers = listOf(RouteManeuver(1, "", "", emptyList(), 0, 5, 0.0, 50.0),
            RouteManeuver(10, "", "", emptyList(), 5, 10, 50.0, 100.0)))
    private fun p(lon: Double, lat: Double = 0.0) = WeatherCoordinate(lat, lon)
    private fun fix(lon: Double, at: Long, lat: Double = 0.0, accuracy: Float = 5f) = NavigationFix(p(lon, lat), at, accuracy, 10f, 90f)

    @Test fun invalidStaleAndOutOfOrderFixesDoNotAdvance() {
        val engine = NavigationEngine(route())
        assertNull(engine.update(fix(.005, 1), 10002))
        assertNull(engine.update(fix(.005, 10002, accuracy = 51f), 10002))
        val first = engine.update(fix(.0, 10003), 10003)!!
        assertNull(engine.update(fix(.005, 10003), 10003))
        assertEquals(0.0, first.elapsedSeconds, .01)
    }

    @Test fun offRouteRequiresThreeFixesOverSixSecondsAndCannotAdvance() {
        val engine = NavigationEngine(route())
        engine.update(fix(0.0, 10000), 10000)
        assertFalse(engine.update(fix(.001, 13000, .001), 13000)!!.offRoute)
        assertFalse(engine.update(fix(.002, 16000, .001), 16000)!!.offRoute)
        val lost = engine.update(fix(.003, 19000, .001), 19000)!!
        assertTrue(lost.offRoute)
        assertEquals(0.0, lost.elapsedSeconds, .001)
        assertFalse(engine.update(fix(.0001, 20000), 20000)!!.offRoute)
    }

    @Test fun loopAtDestinationDoesNotSkipToArrival() {
        val loop = route().copy(vertices = listOf(RouteVertex(p(0.0), 0.0), RouteVertex(p(.01), 50.0), RouteVertex(p(0.0), 100.0)),
            stops = listOf(RouteStop("A", p(0.0)), RouteStop("B", p(0.0))), maneuvers = emptyList())
        val engine = NavigationEngine(loop)
        repeat(3) { assertFalse(engine.update(fix(0.0, 10000L + it * 3000), 10000L + it * 3000)!!.arrived) }
    }

    @Test fun orderedTravelArrivesOnlyAfterThreeNearEndFixes() {
        val engine = NavigationEngine(route())
        for (i in 0..9) engine.update(fix(i / 1000.0, 10000L + i * 10000), 10000L + i * 10000)
        assertFalse(engine.update(fix(.01, 110000), 110000)!!.arrived)
        assertFalse(engine.update(fix(.01, 113000), 113000)!!.arrived)
        assertTrue(engine.update(fix(.01, 116000), 116000)!!.arrived)
    }

    @Test fun cuesDeduplicateApproachAndNowAndUnknownTypesStaySilent() {
        val policy = NavigationCuePolicy()
        val progress = NavigationProgress(30.0, 70.0, 700.0, 0.0, 1, 150.0)
        val fix = fix(.003, 10000)
        assertTrue(policy.cue(route(), progress, fix)!!.contains("sağa"))
        assertNull(policy.cue(route(), progress, fix))
        assertNotNull(policy.cue(route(), progress.copy(nextManeuverDistanceMeters = 10.0), fix))
        assertNull(policy.cue(route(), progress.copy(nextManeuverDistanceMeters = 10.0), fix))
        policy.reset()
        val unknown = route().copy(maneuvers = route().maneuvers.map { it.copy(type = 999) })
        assertNull(policy.cue(unknown, progress, fix))
    }

    @Test fun rampDirectionsFollowValhallaTypesAndTransitIsNeverStraight() {
        val progress = NavigationProgress(30.0, 70.0, 700.0, 0.0, 1, 10.0)
        fun cue(type: Int): String? = NavigationCuePolicy().cue(
            route().copy(maneuvers = route().maneuvers.map { it.copy(type = type) }), progress, fix(.003, 10000))
        assertFalse(cue(17)!!.contains("Sağ"))
        assertTrue(cue(18)!!.contains("Sağ"))
        assertTrue(cue(19)!!.contains("Sol"))
        assertNull(cue(30))
    }

    @Test fun tomTomGenericExitUsesProviderTextWithoutInventingDirection() {
        val progress = NavigationProgress(30.0, 70.0, 700.0, 0.0, 1, 150.0)
        val route = route().copy(provider = RouteProvider.TOMTOM,
            maneuvers = route().maneuvers.map { it.copy(type = 0, verbalInstruction = "Çıkışa girin") })
        val policy = NavigationCuePolicy()
        assertEquals("150 metre sonra Çıkışa girin.", policy.cue(route, progress, fix(.003, 10000)))
        assertNull(policy.cue(route, progress, fix(.003, 10000)))
        assertEquals("Şimdi Çıkışa girin.", policy.cue(route, progress.copy(nextManeuverDistanceMeters = 10.0), fix(.003, 10000)))
        assertNull(NavigationCuePolicy().cue(route.copy(provider = RouteProvider.VALHALLA), progress, fix(.003, 10000)))
        assertNull(NavigationCuePolicy().cue(route, progress.copy(offRoute = true), fix(.003, 10000)))
        assertNull(NavigationCuePolicy().cue(route.copy(maneuvers = route.maneuvers.map { it.copy(verbalInstruction = " ") }), progress, fix(.003, 10000)))
    }

    @Test fun oppositeHeadingCannotJumpToParallelReturnRoad() {
        val parallel = route().copy(vertices = listOf(RouteVertex(p(0.0), 0.0), RouteVertex(p(.01), 50.0),
            RouteVertex(p(.01, .0001), 51.0), RouteVertex(p(0.0, .0001), 100.0)), maneuvers = emptyList())
        val progress = NavigationEngine(parallel).update(fix(.005, 10000, .0001), 10000)!!
        assertEquals(25.0, progress.elapsedSeconds, .1)
        assertFalse(progress.arrived)
    }

    @Test fun intermediateStopsAreKeptUntilPassedAndDestinationIsAlwaysKept() {
        val r = route().copy(stops = listOf(RouteStop("A", p(0.0)), RouteStop("Via", p(.005)), RouteStop("B", p(.01))),
            stopElapsedSeconds = listOf(0.0, 50.0, 100.0))
        val engine = NavigationEngine(r)
        assertEquals(listOf("Via", "B"), engine.remainingStops().map { it.label })
        engine.update(fix(.006, 10000), 10000)
        assertEquals(listOf("B"), engine.remainingStops().map { it.label })
    }

    @Test fun zeroTimeLegDoesNotDiscardUnvisitedIntermediateStop() {
        val r = route().copy(stops = listOf(RouteStop("A", p(0.0)), RouteStop("Via", p(.005)), RouteStop("B", p(.01))),
            vertices = route().vertices.map { it.copy(elapsedSeconds = 0.0) },
            durationSeconds = 0.0, stopElapsedSeconds = listOf(0.0, 0.0, 0.0), maneuvers = emptyList())
        val engine = NavigationEngine(r)
        engine.update(fix(0.0, 10000), 10000)
        assertEquals(listOf("Via", "B"), engine.remainingStops().map { it.label })
    }
}
