package org.iz.navigation.navigation

import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class FuelStationRoutingTest {
    private val origin = RouteStop("Origin", WeatherCoordinate(40.0, 29.0))
    private val via = RouteStop("Existing break", WeatherCoordinate(40.02, 29.0), 120)
    private val finish = RouteStop("Existing destination", WeatherCoordinate(40.03, 29.0))
    private val fix = NavigationFix(origin.coordinate, 1000, 3f)
    private val preferences = RoutePreferences.defaults(Transport.MOTORCYCLE)
    private val stations = listOf(station(1, "No legal approach", 40.001), station(2, "Closer", 40.004),
        station(3, "Faster detour", 40.008))

    private fun station(id: Long, title: String, lat: Double) =
        SelectedOsmPlace(title, lat, 29.0, OsmRef(OsmType.NODE, id))

    private fun route(stops: List<RouteStop>, duration: Double, provider: RouteProvider = RouteProvider.VALHALLA): PlannedRoute {
        val time = stops.indices.map { duration * it / stops.lastIndex }
        val vertices = stops.zip(time).map { (stop, at) -> RouteVertex(stop.coordinate, at) }
        return PlannedRoute(stops.joinToString { it.label }, stops, vertices,
            stops.zipWithNext().sumOf { (a, b) -> WeatherEngine.distanceMeters(a.coordinate, b.coordinate) },
            duration, 1_000, time, Transport.MOTORCYCLE, provider = provider, preferences = preferences,
            maneuvers = listOf(RouteManeuver(1, "Start", "Start", emptyList(), 0, stops.lastIndex, 0.0, duration)))
    }

    private fun owner(active: PlannedRoute?) = FuelStationOwner(1, "run", "trip", true, active?.id, 1,
        Transport.MOTORCYCLE, fix, listOf(1, 2).takeIf { active != null }.orEmpty())

    @Test fun routedChoiceMinimizesExtraTimeAndKeepsTheRemainingStopsInOrder() = runTest {
        val active = route(listOf(origin, via, finish), 100.0)
        val progress = NavigationProgress(0.0, 100.0, active.distanceMeters, 0.0, 0, 0.0)
        val plan = FuelStationRouting.choose(owner(active), active, progress, stations, { stop ->
            RouteEditPolicy.prepare(active, 0.0, origin.coordinate, 1_000,
                RouteEditAction.AddStop(stop), listOf(1, 2))
        }, { request ->
            when (request.stops[1].label) {
                "No legal approach" -> error("Unreachable")
                "Closer" -> route(request.stops, 170.0)
                else -> route(request.stops, 140.0)
            }
        }, { true })

        assertEquals("Faster detour", plan?.title)
        assertEquals(40.0, plan!!.additionalSeconds!!, .001)
        assertEquals(listOf(via, finish), plan.route.stops.drop(2))
    }

    @Test fun unroutedChoiceUsesRoadDistanceAndRejectsGeometryThatMissesTheStation() = runTest {
        val plan = FuelStationRouting.choose(owner(null), null, null, stations, { stop ->
            RouteModificationRequest(listOf(origin, stop), 1_000, Transport.MOTORCYCLE, null, preferences)
        }, { request ->
            val base = route(request.stops, 60.0)
            when (request.stops[1].label) {
                "No legal approach" -> base.copy(vertices = base.vertices.dropLast(1) + RouteVertex(finish.coordinate, 60.0))
                "Closer" -> base.copy(vertices = listOf(base.vertices.first(),
                    RouteVertex(WeatherCoordinate(40.0, 29.02), 30.0), base.vertices.last()),
                    maneuvers = base.maneuvers.map { it.copy(endShapeIndex = 2) })
                else -> base
            }
        }, { true })
        assertEquals("Faster detour", plan?.title)
        assertEquals(2, plan?.route?.stops?.size)
        assertNull(plan?.additionalSeconds)
    }

    @Test fun aLateRouteCannotLeaveThePlannerAfterOwnershipChanges() = runTest {
        var owned = true
        val plan = FuelStationRouting.choose(owner(null), null, null, stations, { stop ->
            RouteModificationRequest(listOf(origin, stop), 1_000, Transport.MOTORCYCLE, null, preferences)
        }, { request -> owned = false; route(request.stops, 60.0) }, { owned })
        assertNull(plan)
    }
}
