package org.iz.navigation.navigation

import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class PreparedRouteStartTest {
    private fun route()=PlannedRoute("saved",listOf(RouteStop("a",WeatherCoordinate(0.0,0.0)),
        RouteStop("b",WeatherCoordinate(0.0,0.01))),
        listOf(RouteVertex(WeatherCoordinate(0.0,0.0),0.0),RouteVertex(WeatherCoordinate(0.0,0.01),100.0)),
        1_100.0,100.0,0,maneuvers=listOf(RouteManeuver(15,"left","left",emptyList(),1,1,100.0,100.0)))
    private fun fix(lat:Double=0.0,lon:Double=0.003,at:Long=1_000L,accuracy:Float=5f,bearing:Float=90f)=
        NavigationFix(WeatherCoordinate(lat,lon),at,accuracy,10f,bearing)
    @Test fun exactPreparedGeometryCanStartAtFreshOnRoutePositionWithoutPlanner() {
        val match=PreparedRouteStart.inspect(route(),fix(),1_000)
        assertTrue(match.message,match.ready)
        assertEquals(30.0,match.progress!!.elapsedSeconds,0.01)
        assertEquals("saved",route().id)
    }
    @Test fun missingStalePoorWrongDirectionOrOutsidePositionCannotInventAConnector() {
        listOf(null,fix(at=0),fix(accuracy=30f),fix(lat=0.001),fix(bearing=270f)).forEach {
            assertFalse(PreparedRouteStart.inspect(route(),it,20_000).ready)
        }
        assertFalse(PreparedRouteStart.inspect(route(),fix(lat=0.001),1_000).ready)
        assertFalse(PreparedRouteStart.inspect(route(),fix(bearing=270f),1_000).ready)
    }
    @Test fun trafficProviderOrMissingManeuversCannotBecomeAnOfflinePreparedRoute() {
        assertFalse(PreparedRouteStart.inspect(route().copy(provider=RouteProvider.TOMTOM),fix(),1_000).ready)
        assertFalse(PreparedRouteStart.inspect(route().copy(maneuvers=emptyList()),fix(),1_000).ready)
    }
    @Test fun closeParallelOutAndBackSectionsAreAmbiguousUntilLocationDistinguishesThem() {
        val base=route()
        val loop=base.copy(vertices=listOf(RouteVertex(WeatherCoordinate(0.0,0.0),0.0),
            RouteVertex(WeatherCoordinate(0.0,0.01),100.0),RouteVertex(WeatherCoordinate(0.00004,0.01),101.0),
            RouteVertex(WeatherCoordinate(0.00004,0.0),201.0)),durationSeconds=201.0)
        assertFalse(PreparedRouteStart.inspect(loop,fix().copy(speedMps=0f,bearingDegrees=null),1_000).ready)
    }
}