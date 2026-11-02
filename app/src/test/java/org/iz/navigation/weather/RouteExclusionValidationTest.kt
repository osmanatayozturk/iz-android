package org.iz.navigation.weather

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RouteExclusionValidationTest {
    // Coordinates below are hand-checked Cartesian polyline fixtures, not surveyed road geometry.
    private val box = RouteAvoidArea(-0.02, -0.02, 0.02, 0.02)
    private fun point(lat: Double, lon: Double) = WeatherCoordinate(lat, lon)
    private fun route(vararg points: WeatherCoordinate): PlannedRoute = PlannedRoute("returned-provider-route",
        listOf(RouteStop("Başlangıç", points.first()), RouteStop("Asıl hedef", points.last())),
        points.mapIndexed { index, coordinate -> RouteVertex(coordinate, index.toDouble()) },
        1000.0, points.size.toDouble(), 1000L)
    private fun rejected(candidate: PlannedRoute, areas: List<RouteAvoidArea> = listOf(box)) {
        try {
            requireAvoidedAreas(candidate, areas)
            fail("Returned geometry must not touch an excluded closed area")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun emptyExclusionsReturnTheSameProviderRouteWithoutChangingDestination() {
        val candidate = route(point(0.0, -0.1), point(0.0, 0.1))
        val destination = candidate.stops.last()
        assertSame(candidate, requireAvoidedAreas(candidate, emptyList()))
        assertSame(destination, candidate.stops.last())
    }

    @Test fun outsideEndpointsDoNotHideACrossingReturnedEdge() {
        // Both vertices/stops are outside; the intervening edge crosses the whole box.
        rejected(route(point(0.0, -0.1), point(0.0, 0.1)))
        rejected(route(point(-0.1, 0.0), point(0.1, 0.0)))
    }

    @Test fun closedBoundaryCornerInsideAndZeroLengthContactsAreRejected() {
        val candidates = listOf(
            route(point(0.02, -0.1), point(0.02, 0.1)), // north boundary, parallel edge
            route(point(0.06, -0.02), point(-0.02, 0.06)), // corner-only tangent at (.02,.02)
            route(point(0.02, 0.0), point(0.06, 0.0)), // boundary endpoint
            route(point(0.0, 0.0), point(0.06, 0.06)), // starts inside
            route(point(0.0, 0.0)), // single-point returned geometry inside
            route(point(0.02, 0.02), point(0.02, 0.02))) // stationary corner
        candidates.forEach { rejected(it) }
    }

    @Test fun overlappingOverallBoundsDoNotRejectEdgesThatActuallyGoAroundTheBox() {
        val around = route(point(0.0, -0.1), point(0.06, -0.1), point(0.06, 0.1), point(0.0, 0.1))
        assertSame(around, requireAvoidedAreas(around, listOf(box)))
        val justOutside = route(point(0.020001, -0.1), point(0.020001, 0.1))
        assertSame(justOutside, requireAvoidedAreas(justOutside, listOf(box)))
        val stationaryOutside = route(point(0.06, 0.06), point(0.06, 0.06))
        assertSame(stationaryOutside, requireAvoidedAreas(stationaryOutside, listOf(box)))
    }

    @Test fun everyRequestedAreaIsCheckedInsteadOfOnlyTheFirst() {
        val other = RouteAvoidArea(0.1, 0.1, 0.14, 0.14)
        rejected(route(point(0.0, -0.1), point(0.0, 0.1)), listOf(other, box))
    }

    @Test fun shortestAntimeridianEdgesDoNotCrossGreenwichButDoCrossDatelineAreas() {
        val across = route(point(0.0, 179.9), point(0.0, -179.9))
        assertSame(across, requireAvoidedAreas(across, listOf(box)))
        rejected(across, listOf(RouteAvoidArea(-0.02, 179.94, 0.02, 179.98)))
        rejected(across, listOf(RouteAvoidArea(-0.02, -179.98, 0.02, -179.94)))
        val reverse = route(point(0.0, -179.9), point(0.0, 179.9))
        assertSame(reverse, requireAvoidedAreas(reverse, listOf(box)))
        rejected(reverse, listOf(RouteAvoidArea(-0.02, 179.94, 0.02, 179.98)))
        rejected(reverse, listOf(RouteAvoidArea(-0.02, -179.98, 0.02, -179.94)))
    }

    @Test fun bothRepresentationsOfTheDatelineShareTheSameClosedBoundary() {
        rejected(route(point(0.0, 180.0)), listOf(RouteAvoidArea(-0.02, -180.0, 0.02, -179.96)))
        rejected(route(point(0.0, -180.0)), listOf(RouteAvoidArea(-0.02, 179.96, 0.02, 180.0)))
        val eastOfBox = route(point(0.0, -179.99), point(0.0, -179.8))
        assertSame(eastOfBox, requireAvoidedAreas(eastOfBox, listOf(RouteAvoidArea(-0.02, 179.96, 0.02, 180.0))))
    }

    @Test fun rejectedProviderCandidateCannotReplaceThePriorRouteOrItsDestination() {
        val old = route(point(0.06, -0.1), point(0.06, 0.1)).copy(id = "old-owner")
        val destination = old.stops.last()
        var selected = old
        try { selected = requireAvoidedAreas(route(point(0.0, -0.1), point(0.0, 0.1)), listOf(box)) }
        catch (_: IllegalArgumentException) { }
        assertSame(old, selected)
        assertSame(destination, selected.stops.last())
    }

    @Test fun cancellationAtEntryPropagatesTheExactOwnerCancellation() {
        val cancelled = CancellationException("Replaced route owner")
        try {
            requireAvoidedAreas(route(point(0.06, -0.1), point(0.06, 0.1)), listOf(box)) { throw cancelled }
            fail("Cancellation must not become a provider success")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun traversalContainsCancellationCheckpointsAndDoesNotWrapCancellationAsGeometryFailure() {
        val candidate = route(*(0..120).map { point(0.2, it / 1000.0) }.toTypedArray())
        val cancelled = CancellationException("Route became stale during validation")
        var checks = 0
        try {
            requireAvoidedAreas(candidate, listOf(box)) { if (++checks == 8) throw cancelled }
            fail("Long traversal must check its current owner before returning")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertEquals(8, checks)
        assertEquals("Asıl hedef", candidate.stops.last().label)
    }
}
