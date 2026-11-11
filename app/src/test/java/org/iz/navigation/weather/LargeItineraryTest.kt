package org.iz.navigation.weather

import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationRouteCache
import org.iz.navigation.navigation.prepareRouteStartStops
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LargeItineraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun stops(count: Int) = (0 until count).map {
        RouteStop("Stop $it", WeatherCoordinate(40.0, 29.0 + it * .001),
            if (it in 1 until count - 1) 60 else 0)
    }

    @Test fun twentyPlannedStopsKeepEveryOccurrenceAndBreakWhenApproachMakesTwentyOne() {
        val stops = stops(20).toMutableList().also { it[3] = it[2].copy(plannedBreakSeconds = 120) }
        val result = prepareRouteStartStops(stops, WeatherCoordinate(41.0, 29.0))
        assertEquals(21, result.size)
        assertEquals(stops, result.drop(1))
        assertThrows(IllegalArgumentException::class.java) {
            prepareRouteStartStops(stops(21), WeatherCoordinate(41.0, 29.0))
        }
    }

    @Test fun effectiveRouteAndCacheRetainTwentyOneStopsWhilePlanCodecStopsAtTwenty() {
        val planned = stops(20)
        val saved = SavedWeatherPlan(planned, 100L, Transport.CAR)
        assertEquals(saved, WeatherPlanCodec.decode(WeatherPlanCodec.encode(saved)))
        assertThrows(IllegalArgumentException::class.java) {
            WeatherPlanCodec.encode(saved.copy(stops = stops(21)))
        }
        val effective = itineraryFixture(stops(21))
        val cache = NavigationRouteCache(temporary.newFolder())
        cache.write(effective)
        assertEquals(effective.stops, cache.read()!!.stops)
        assertEquals(effective.stopElapsedSeconds, cache.read()!!.stopElapsedSeconds)
        assertThrows(IllegalArgumentException::class.java) {
            itineraryFixture(stops(22))
        }
    }

    @Test fun legacyWeatherPlanStillLoadsWithoutLosingBreaks() {
        val saved = SavedWeatherPlan(stops(5), 100L, Transport.CAR)
        val legacy = JSONObject(WeatherPlanCodec.encode(saved)).put("version", 3).toString()
        assertEquals(saved, WeatherPlanCodec.decode(legacy))
    }

    @Test fun expandedCountsRequireTheNewPlanAndCacheVersions() {
        val saved = SavedWeatherPlan(stops(20), 100L, Transport.CAR)
        assertNull(WeatherPlanCodec.decode(JSONObject(WeatherPlanCodec.encode(saved)).put("version", 3).toString()))
        val directory = temporary.newFolder()
        val cache = NavigationRouteCache(directory)
        val file = File(directory, "route.json")
        cache.write(itineraryFixture(stops(21)))
        file.writeText(JSONObject(file.readText()).put("version", 4).toString())
        assertNull(cache.read())
        val legacy = itineraryFixture(stops(6))
        cache.write(legacy)
        file.writeText(JSONObject(file.readText()).put("version", 4).toString())
        assertEquals(legacy.stops, cache.read()!!.stops)
    }

    @Test fun approachJoinUsesTwoAndTwentyStopLegsWithShiftedTimesAndNoDroppedStops() = runBlocking {
        val effective = stops(21).toMutableList().also { it[1] = it[1].copy(plannedBreakSeconds = 0) }
        val calls = mutableListOf<Pair<List<RouteStop>, Long>>()
        val joined = planApproachRoute(effective, 1_000L) { values, at ->
            calls += values to at
            itineraryFixture(values).copy(effectiveDepartureAt = at)
        }
        assertEquals(listOf(2, 20), calls.map { it.first.size })
        assertEquals(11_000L, calls.last().second)
        assertEquals(effective, joined.stops)
        assertEquals((0..20).map { it * 10.0 }, joined.stopElapsedSeconds)
        assertEquals(200.0, joined.durationSeconds, 0.0)
        assertEquals(RouteProvider.VALHALLA, joined.provider)
    }

    @Test fun approachJoinRejectsDifferentProvidersAndPropagatesFailure() {
        val effective = stops(21).toMutableList().also { it[1] = it[1].copy(plannedBreakSeconds = 0) }
        var calls = 0
        assertThrows(RouteServiceException::class.java) { runBlocking {
            planApproachRoute(effective, 1_000L) { values, _ ->
                calls++
                itineraryFixture(values).copy(provider = if (calls == 1) RouteProvider.VALHALLA else RouteProvider.TOMTOM)
            }
        } }
        assertThrows(IllegalStateException::class.java) { runBlocking {
            planApproachRoute(effective, 1_000L) { _, _ -> error("provider failure") }
        } }
    }

    @Test fun approachJoinRejectsChangedProfileDepartureAndGeometryAtTheJunction() {
        val effective = stops(21).toMutableList().also { it[1] = it[1].copy(plannedBreakSeconds = 0) }
        for (mismatch in 0..2) {
            var calls = 0
            assertThrows(RouteServiceException::class.java) { runBlocking {
                planApproachRoute(effective, 1_000L) { values, at ->
                    calls++
                    val route = itineraryFixture(values, at = at)
                    if (calls == 1) route else when (mismatch) {
                        0 -> route.copy(transport = Transport.MOTORCYCLE)
                        1 -> route.copy(effectiveDepartureAt = at + 1_000L)
                        else -> route.copy(vertices = route.vertices.toMutableList().also {
                            it[0] = it[0].copy(coordinate = WeatherCoordinate(41.0, 30.0))
                        })
                    }
                }
            } }
        }
    }
}

internal fun itineraryFixture(stops: List<RouteStop>, duration: Double = (stops.size - 1) * 10.0,
    provider: RouteProvider = RouteProvider.VALHALLA, at: Long? = null,
    transport: Transport = Transport.CAR, preferences: RoutePreferences = RoutePreferences.defaults(transport)): PlannedRoute {
    val times = stops.indices.map { duration * it / stops.lastIndex }
    return PlannedRoute("itinerary", stops, stops.mapIndexed { i, stop -> RouteVertex(stop.coordinate, times[i]) },
        1000.0, duration, 1_000L, times, transport, provider = provider, effectiveDepartureAt = at,
        preferences = preferences, hasHighway = false)
}
