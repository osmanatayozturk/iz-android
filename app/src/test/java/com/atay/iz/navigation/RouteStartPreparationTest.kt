package com.atay.iz.navigation

import com.atay.iz.weather.*
import org.junit.Assert.*
import org.junit.Test

class RouteStartPreparationTest {
    private val a = RouteStop("A", WeatherCoordinate(40.0, 29.0))
    private val b = RouteStop("B", WeatherCoordinate(40.03, 29.0))
    @Test fun distantCustomOriginRemainsFirstDestination() {
        val current = WeatherCoordinate(40.01, 29.01)
        val selected = listOf(a, b)
        val started = prepareRouteStartStops(selected, current)
        assertEquals(listOf(current, a.coordinate, b.coordinate), started.map { it.coordinate })
        assertEquals(listOf(a, b), selected)
    }
    @Test fun nearbyOriginIsReplacedByFreshFixWithoutBacktracking() {
        val current = WeatherCoordinate(40.0001, 29.0)
        assertEquals(listOf(current, b.coordinate), prepareRouteStartStops(listOf(a, b), current).map { it.coordinate })
    }
    @Test fun injectedOriginKeepsAllFiveUserStopsInOrder() {
        val selected = (0..4).map { RouteStop("Stop $it", WeatherCoordinate(40.0 + it * .01, 29.0)) }
        val result = prepareRouteStartStops(selected, WeatherCoordinate(41.0, 29.0))
        assertEquals(6, result.size)
        assertEquals(selected, result.drop(1))
    }
    @Test fun missingDestinationIsRejected() {
        assertTrue(runCatching { prepareRouteStartStops(listOf(a), a.coordinate) }.isFailure)
    }
}
