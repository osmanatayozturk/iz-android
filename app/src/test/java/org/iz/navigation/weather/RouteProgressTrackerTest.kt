package org.iz.navigation.weather

import org.junit.Assert.*
import org.junit.Test

class RouteProgressTrackerTest {
    @Test fun projectsFractionalTimeAndDistanceAndDoesNotTravelBackwards() {
        val tracker = RouteProgressTracker(route(listOf(c(0.0), c(0.01), c(0.02))))
        val halfway = tracker.project(c(0.005))
        assertEquals(500.0, halfway.elapsedSeconds, 0.01)
        assertEquals(1_500.0, halfway.remainingSeconds, 0.01)
        assertEquals(1_500.0, halfway.remainingMeters, 0.01)
        val behind = tracker.project(c(0.004))
        assertEquals(halfway.elapsedSeconds, behind.elapsedSeconds, 0.01)
        val end = tracker.project(c(0.02))
        assertEquals(0.0, end.remainingMeters, 0.01)
        assertEquals(0.0, end.remainingSeconds, 0.01)
    }

    @Test fun distantFixReportsDeviationWithoutAdvancingProgress() {
        val tracker = RouteProgressTracker(route(listOf(c(0.0), c(0.01), c(0.02))))
        tracker.project(c(0.005))
        val far = tracker.project(WeatherCoordinate(0.01, 0.018))
        assertTrue(far.distanceFromRouteMeters > 1_000.0)
        assertEquals(500.0, far.elapsedSeconds, 0.01)
        assertEquals(1_500.0, far.remainingMeters, 0.01)
    }

    @Test fun repeatedCrossingKeepsEarliestUntravelledOccurrence() {
        val points = listOf(c(0.0), c(0.01), WeatherCoordinate(0.01, 0.01), c(0.0), c(-0.01))
        val tracker = RouteProgressTracker(route(points))
        assertEquals(0.0, tracker.project(c(0.0)).elapsedSeconds, 0.01)
        assertEquals(1_000.0, tracker.project(c(0.01)).elapsedSeconds, 0.01)
        assertEquals(2_000.0, tracker.project(WeatherCoordinate(0.01, 0.01)).elapsedSeconds, 0.01)
        assertEquals(3_000.0, tracker.project(c(0.0)).elapsedSeconds, 0.01)
    }

    @Test fun stopTimelinePreservesUnvisitedViaAtSameCoordinatesAsOrigin() {
        val points = listOf(c(0.0), c(0.01), c(0.0), c(-0.01))
        val planned = route(points).copy(
            stops = listOf(RouteStop("Origin",points[0]), RouteStop("Return via",points[2]), RouteStop("Target",points[3])),
            stopElapsedSeconds = listOf(0.0, 2_000.0, 3_000.0))
        val tracker = RouteProgressTracker(planned)
        assertEquals(listOf("Return via","Target"), tracker.remainingStops(500.0).map { it.label })
        assertEquals(listOf("Target"), tracker.remainingStops(2_001.0).map { it.label })
        assertEquals(listOf("Target"), tracker.remainingStops(3_000.0).map { it.label })
    }

    @Test fun projectionWrapsLongitudeAtInternationalDateLine() {
        val tracker = RouteProgressTracker(route(listOf(c(179.99), c(-179.99))))
        val middle = tracker.project(c(180.0))
        assertEquals(500.0, middle.elapsedSeconds, 0.01)
        assertEquals(0.0, middle.distanceFromRouteMeters, 0.01)
    }


    @Test fun noisyFixDoesNotSkipToLaterParallelLoopBranch() {
        val points = listOf(
            WeatherCoordinate(41.0,29.0), WeatherCoordinate(41.002,29.0),
            WeatherCoordinate(41.002,29.002), WeatherCoordinate(41.0,29.002),
            WeatherCoordinate(41.0,29.0001), WeatherCoordinate(41.002,29.0001))
        val planned = route(points).copy(
            vertices = points.mapIndexed { index,p -> RouteVertex(p,index*600.0) },
            durationSeconds=3_000.0)
        val tracker = RouteProgressTracker(planned)
        val progress = tracker.project(WeatherCoordinate(41.0005,29.00008))
        assertEquals("A nearby later loop branch must not skip unvisited road",150.0,progress.elapsedSeconds,0.01)
    }

    private fun c(longitude: Double) = WeatherCoordinate(0.0, longitude)
    private fun route(points: List<WeatherCoordinate>) = PlannedRoute(
        "projection", listOf(RouteStop("Origin",points.first()), RouteStop("Target",points.last())),
        points.mapIndexed { index, coordinate -> RouteVertex(coordinate, index * 1_000.0) },
        (points.size-1)*1_000.0, (points.size-1)*1_000.0, 0L)
}
