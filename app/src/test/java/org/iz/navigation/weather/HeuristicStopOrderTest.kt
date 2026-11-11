package org.iz.navigation.weather

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class HeuristicStopOrderTest {
    @Test fun heuristicKeepsEndpointsOccurrencesAndBreakMetadataDeterministically() {
        val stops = listOf(0.0, .08, .02, .06, .04, .10).mapIndexed { i, lon ->
            RouteStop("Occurrence $i", WeatherCoordinate(0.0, lon), if (i in 1..4) i * 60 else 0)
        }
        val order = heuristicStopOrder(stops)
        assertEquals(listOf(0, 2, 4, 3, 1, 5), order)
        repeat(5) { assertEquals(order, heuristicStopOrder(stops)) }
        assertEquals(stops.indices.toSet(), order.toSet())
        val duplicate = stops.toMutableList().also { it[3] = it[2].copy(plannedBreakSeconds = 180) }
        val ordered = heuristicStopOrder(duplicate).map(duplicate::get)
        assertEquals(duplicate.groupingBy { it }.eachCount(), ordered.groupingBy { it }.eachCount())
        assertEquals(duplicate.first(), ordered.first())
        assertEquals(duplicate.last(), ordered.last())
    }

    @Test fun twentyStopSearchIsBoundedAndCancellationIsObserved() {
        val stops = (0 until 20).map { RouteStop("$it", WeatherCoordinate(0.0, ((it * 7) % 20) * .001)) }
        var checks = 0
        val order = heuristicStopOrder(stops) { checks++ }
        assertEquals(stops.indices.toSet(), order.toSet())
        assertTrue(checks <= 20_000)
        assertThrows(CancellationException::class.java) {
            heuristicStopOrder(stops) { throw CancellationException("fixture") }
        }
    }
}
