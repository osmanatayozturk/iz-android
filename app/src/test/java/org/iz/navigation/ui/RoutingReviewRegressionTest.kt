package org.iz.navigation.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.prepareRouteStart
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class RoutingReviewRegressionTest {
    private val current = WeatherCoordinate(0.0, -.01)
    private val a = RouteStop("A", WeatherCoordinate(0.0, 0.0))
    private val b = RouteStop("B", WeatherCoordinate(0.0, .002))
    private val via = RouteStop("V", WeatherCoordinate(0.0, .001))
    private val now = 10_000L

    private fun route(stops: List<RouteStop>, chosen: Boolean = false, at: Long = now): PlannedRoute {
        val vertices = stops.mapIndexed { index, stop -> RouteVertex(stop.coordinate, index * 50.0) }.toMutableList()
        if (chosen) vertices.add(1, RouteVertex(WeatherCoordinate(.001, .0005), 25.0))
        val duration = (stops.size - 1) * 50.0
        return PlannedRoute(if (chosen) "selected" else "primary", stops, vertices, duration * 10.0, duration, now,
            stops.indices.map { it * 50.0 }, Transport.CAR,
            maneuvers = listOf(RouteManeuver(1, "Devam", "Devam", emptyList(), 0, vertices.lastIndex, 0.0, duration),
                RouteManeuver(4, "Varış", "Varış", emptyList(), vertices.lastIndex, vertices.lastIndex, duration, duration)),
            provider = RouteProvider.TOMTOM, traffic = RouteTrafficInfo(now, 5.0, duration - 5.0),
            speedLimits = listOf(RouteSpeedLimitSection(0, vertices.lastIndex, 50.0)), effectiveDepartureAt = at)
    }

    private fun planner() = object : RoutePlanner {
        override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
            travelSpeedKmh: Double?, preferences: RoutePreferences) = route(stops, at = departureAt)
        override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
            travelSpeedKmh: Double?, preferences: RoutePreferences) = RouteAlternatives(listOf(route(stops,
                chosen = stops.first() == a, at = departureAt)))
    }

    private fun services(planner: RoutePlanner, activate: suspend (PlannedRoute, List<LocationForecast>) -> String = { _, _ -> "journey" }) =
        WeatherPlannerServices({ planner }, { object : WeatherProvider {
            override suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long) = emptyList<LocationForecast>()
        } }, ::defaultWeatherSettings, { _, _ -> }, {}, activate, clock = { now })

    @Test fun phoneStartsSelectedAlternativeAfterApproachingDistantCustomOrigin() = runBlocking {
        val planner = planner()
        var started: PlannedRoute? = null
        val coordinator = PhoneDirectionsCoordinator(this, { current }, { stops, _ -> route(stops) }, { started = it },
            routeAlternatives = { stops, mode, speed, preferences -> planner.alternatives(stops, now, mode, speed, preferences) }, clock = { now })
        coordinator.enter(1L, listOf(a, b), Transport.CAR, locateOnOpen = false)
        coordinator.preview()!!.join(); coordinator.requestAlternatives()!!.join(); coordinator.selectAlternative("selected")
        coordinator.start()!!.join()
        assertNotNull("The selected A-to-B suffix remains usable after a real approach leg", started)
        assertEquals(listOf(current, a.coordinate, b.coordinate), started!!.stops.map { it.coordinate })
        assertTrue(started!!.vertices.any { it.coordinate == WeatherCoordinate(.001, .0005) })
        assertEquals(1L, coordinator.state.value.startedCount)
    }

    @Test fun weatherStartsSelectedAlternativeAfterApproachingDistantCustomOrigin() = runBlocking {
        var started: PlannedRoute? = null
        val coordinator = WeatherPlannerCoordinator(this, services(planner()) { value, _ -> started = value; "journey" },
            SavedWeatherPlan(listOf(a, b), 60_000L, Transport.CAR))
        coordinator.calculate()!!.join(); coordinator.requestAlternatives()!!.join(); coordinator.selectAlternative("selected")!!.join()
        coordinator.startFromCurrentLocation(current)!!.join()
        assertNotNull("The selected A-to-B suffix remains usable after a real approach leg", started)
        assertEquals(listOf(current, a.coordinate, b.coordinate), started!!.stops.map { it.coordinate })
        assertTrue(started!!.vertices.any { it.coordinate == WeatherCoordinate(.001, .0005) })
        assertEquals("journey", coordinator.state.value.activeJourneyId)
    }

    @Test fun approachPreservesMultipleStopsAndOffsetsTimelineInstructionsAndSpeedSections() = runBlocking {
        val selected = route(listOf(a, via, b), chosen = true).copy(selectionLocked = true)
        val result = prepareRouteStart(planner(), selected, current, now)
        assertEquals(listOf(current, a.coordinate, via.coordinate, b.coordinate), result.stops.map { it.coordinate })
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0), result.stopElapsedSeconds)
        assertEquals(150.0, result.durationSeconds, 0.0)
        assertEquals(1, result.vertices.count { it.coordinate == a.coordinate })
        assertEquals(1, result.maneuvers.count { it.type in 4..6 })
        assertEquals(result.vertices.lastIndex, result.maneuvers.last().endShapeIndex)
        assertEquals(result.vertices.lastIndex, result.speedLimits.last().endShapeIndex)
        assertEquals(1, result.speedLimits.last().beginShapeIndex)
        assertEquals(75.0, result.vertices.first { it.coordinate == WeatherCoordinate(.001, .0005) }.elapsedSeconds, 0.0)
        assertEquals(10.0, result.traffic!!.delaySeconds, 0.0)
        assertEquals(140.0, result.traffic!!.noTrafficDurationSeconds!!, 0.0)
        assertEquals(now, result.effectiveDepartureAt)
        assertTrue(result.selectionLocked)
    }

    @Test fun verifiedDepartureLabelsStayWithTheCurrentlyAcceptedRouteGeometry() = runBlocking {
        val firstAt = 60_000L
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences) = route(stops, chosen = departureAt != firstAt, at = departureAt)
        }
        val coordinator = WeatherPlannerCoordinator(this, services(planner), SavedWeatherPlan(listOf(a, b), firstAt, Transport.CAR))
        coordinator.calculate()!!.join()
        assertEquals(setOf(firstAt), coordinator.state.value.verifiedDepartures)
        val secondAt = coordinator.state.value.comparisons[1].departureAt
        coordinator.selectDeparture(secondAt); yield()
        assertEquals(secondAt, coordinator.state.value.selectedDepartureAt)
        assertEquals(setOf(secondAt), coordinator.state.value.verifiedDepartures)
        coordinator.selectDeparture(firstAt); yield()
        assertEquals(firstAt, coordinator.state.value.selectedDepartureAt)
        assertEquals(setOf(firstAt), coordinator.state.value.verifiedDepartures)
    }

    @Test fun suffixIsRevalidatedForArrivalAtAWithOneBoundedAlternativeRequest() = runBlocking {
        val selected = route(listOf(a, b), chosen = true).copy(selectionLocked = true)
        val calls = mutableListOf<Pair<List<RouteStop>, Long>>()
        var plainCalls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
                plainCalls++
                return route(stops, at = departureAt + 2000L)
            }
            override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): RouteAlternatives {
                calls += stops to departureAt
                return RouteAlternatives(listOf(route(stops, chosen = stops.first() == a, at = departureAt)))
            }
        }
        val result = prepareRouteStart(planner, selected, current, now)
        assertEquals(1, plainCalls)
        assertEquals(listOf(listOf(a, b) to (now + 52_000L)), calls)
        assertEquals(now + 2000L, result.effectiveDepartureAt)
    }

    @Test fun disconnectedApproachOrDifferentProviderCannotCreateAnInventedJoin() {
        val selected = route(listOf(a, b), chosen = true).copy(selectionLocked = true)
        listOf(false, true).forEach { changeProvider ->
            val planner = object : RoutePlanner {
                override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                    travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
                    val approach = route(stops, at = departureAt)
                    return if (changeProvider) approach.copy(provider = RouteProvider.VALHALLA, traffic = null)
                    else approach.copy(vertices = approach.vertices.dropLast(1) +
                        approach.vertices.last().copy(coordinate = WeatherCoordinate(0.0, .0001)))
                }
                override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                    travelSpeedKmh: Double?, preferences: RoutePreferences) = RouteAlternatives(listOf(selected.copy(effectiveDepartureAt = departureAt)))
            }
            assertThrows(RouteServiceException::class.java) { runBlocking { prepareRouteStart(planner, selected, current, now) } }
        }
    }

    @Test fun productionPhonePlannerReceivesThePredictedSuffixDeparture() = runBlocking {
        val calls = mutableListOf<Pair<List<RouteStop>, Long>>()
        var started: PlannedRoute? = null
        val timedPlanner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
                calls += stops to departureAt
                return route(stops, at = departureAt)
            }
            override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): RouteAlternatives {
                calls += stops to departureAt
                return RouteAlternatives(listOf(route(stops, chosen = true, at = departureAt)))
            }
        }
        val coordinator = PhoneDirectionsCoordinator(this, { current }, { stops, _ -> route(stops) }, { started = it },
            routeAlternatives = { stops, _, _, _ -> RouteAlternatives(listOf(route(stops, chosen = true))) },
            startRoutePlanner = { timedPlanner }, clock = { now })
        coordinator.enter(1L, listOf(a, b), Transport.CAR, locateOnOpen = false)
        coordinator.preview()!!.join(); coordinator.requestAlternatives()!!.join(); coordinator.selectAlternative("selected")
        coordinator.start()!!.join()
        assertNotNull(started)
        assertEquals(listOf(listOf(RouteStop("Mevcut konum", current), a) to now, listOf(a, b) to (now + 50_000L)), calls)
    }

    @Test fun missingApproachTrafficDoesNotProduceAClaimAboutTheWholeTrip() = runBlocking {
        val selected = route(listOf(a, b), chosen = true).copy(selectionLocked = true)
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences) = route(stops, at = departureAt).copy(traffic = null)
            override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences) = RouteAlternatives(listOf(selected.copy(effectiveDepartureAt = departureAt)))
        }
        val result = prepareRouteStart(planner, selected, current, now)
        assertNull(result.traffic)
        assertFalse(result.trafficUnavailableReason.isNullOrBlank())
        assertEquals(100.0, result.durationSeconds, 0.0)
    }

    @Test fun activeModeCannotAttachAnApproachWithUnknownHighwayClassification() {
        val preferences = RoutePreferences.defaults(Transport.WALK)
        val selected = route(listOf(a, b), chosen = true).copy(transport = Transport.WALK, preferences = preferences,
            provider = RouteProvider.VALHALLA, traffic = null, hasHighway = false, selectionLocked = true)
        var alternativeCalls = 0
        val planner = object : RoutePlanner {
            override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences) = route(stops, at = departureAt).copy(transport = transport,
                preferences = preferences, provider = RouteProvider.VALHALLA, traffic = null, hasHighway = null)
            override suspend fun alternatives(stops: List<RouteStop>, departureAt: Long, transport: Transport,
                travelSpeedKmh: Double?, preferences: RoutePreferences): RouteAlternatives {
                alternativeCalls++
                return RouteAlternatives(listOf(selected))
            }
        }
        assertThrows(RouteServiceException::class.java) { runBlocking { prepareRouteStart(planner, selected, current, now) } }
        assertEquals(0, alternativeCalls)
    }
}
