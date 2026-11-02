package org.iz.navigation.weather

import org.junit.Assert.*
import org.junit.Test

class RouteEditPolicyTest {
    private val stops = listOf(RouteStop("start", WeatherCoordinate(0.0,0.0)),
        RouteStop("rest", WeatherCoordinate(0.0,0.01)), RouteStop("fuel", WeatherCoordinate(0.0,0.02)),
        RouteStop("end", WeatherCoordinate(0.0,0.03)))
    private fun route() = PlannedRoute("route", stops,
        (0..30).map { RouteVertex(WeatherCoordinate(0.0,it * 0.001), it * 10.0) },
        3_300.0,300.0,0,stopElapsedSeconds=listOf(0.0,100.0,200.0,300.0),
        maneuvers=listOf(RouteManeuver(1,"straight","straight",emptyList(),0,30,0.0,300.0)))
    @Test fun skipRemovesOnlyChosenUnvisitedIntermediateStopAndKeepsDestination() {
        val request = RouteEditPolicy.prepare(route(), 50.0, WeatherCoordinate(0.0,0.005),1_000,
            RouteEditAction.SkipStop(1))
        assertEquals(listOf("Mevcut konum","fuel","end"),request.stops.map { it.label })
        assertTrue(request.avoidAreas.isEmpty())
        assertEquals(1_000L,request.departureAt)
    }
    @Test fun passedOrFinalStopCannotBeSkipped() {
        listOf(0,1,3,4).forEach { index ->
            try {
                RouteEditPolicy.prepare(route(),150.0,WeatherCoordinate(0.0,0.015),1_000,RouteEditAction.SkipStop(index))
                fail("ineligible stop $index")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun selectedRoadAvoidanceEmitsBoundedActualProviderAreaAndRetainsStops() {
        val request=RouteEditPolicy.prepare(route(),50.0,WeatherCoordinate(0.0,0.005),1_000,
            RouteEditAction.AvoidManeuver(0))
        assertEquals(listOf("Mevcut konum","rest","fuel","end"),request.stops.map { it.label })
        assertEquals(1,request.avoidAreas.size)
        val area=request.avoidAreas.single()
        assertTrue(area.west > 0.005)
        assertTrue(area.east < 0.020)
        assertTrue(area.south < 0.0 && area.north > 0.0)
    }
    @Test fun selectedPoiIsInsertedBeforeRemainingDestinationWithoutDiscardingOtherStops() {
        val request=RouteEditPolicy.prepare(route(),150.0,WeatherCoordinate(0.0,0.015),1_000,
            RouteEditAction.AddStop(RouteStop("new rest",WeatherCoordinate(0.001,0.018))))
        assertEquals(listOf("Mevcut konum","new rest","fuel","end"),request.stops.map { it.label })
    }
    @Test fun absentStopTimelineCannotInventWhichIntermediateStopWasPassed() {
        try {
            RouteEditPolicy.prepare(route().copy(stopElapsedSeconds=emptyList()),50.0,WeatherCoordinate(0.0,0.005),1_000,
                RouteEditAction.SkipStop(1))
            fail("cannot safely skip without stop progress")
        } catch (_: IllegalArgumentException) { }
    }
}