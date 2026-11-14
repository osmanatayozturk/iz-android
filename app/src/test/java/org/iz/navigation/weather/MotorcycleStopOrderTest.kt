package org.iz.navigation.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class MotorcycleStopOrderTest {
    private val credentials = TrafficCredentials(null, false, false, "stable")
    private val preferences = RoutePreferences(avoidHighways = true, avoidTolls = true, avoidFerries = true, avoidUnpaved = true)
    private fun stops(size: Int): List<RouteStop> {
        val values = listOf(0.0) + (1 until size - 1).reversed().map { it * .001 } + listOf(size * .001)
        return values.mapIndexed { index, lon -> RouteStop("Stop $index", WeatherCoordinate(35.0, 25.0 + lon), if (index in 1 until size - 1) index * 60 else 0) }
    }

    @Test fun fourFiveAndTwentyUseLocalCandidateAndOneComparableValhallaContextWithoutMatrix() = runBlocking {
        for (size in listOf(4, 5, 20)) {
            val original = stops(size)
            val settings = defaultWeatherSettings(Transport.MOTORCYCLE).copy(preferences = preferences)
            val calls = mutableListOf<Pair<Long, RouteProvider?>>()
            val planner = ConfiguredStopOrderPlanner({ credentials }, { 1000L },
                matrix = { _, _, _, _, _ -> error("Motorcycle must not request TomTom Matrix") },
                route = { _, _, _, _, _, _ -> error("Motorcycle must not request TomTom routes") },
                largeSettings = { settings }, largeRoute = { values, at, mode, actual, provider ->
                    calls += at to provider
                    assertEquals(Transport.MOTORCYCLE, mode)
                    assertEquals(preferences, actual)
                    assertEquals(RouteProvider.VALHALLA, provider)
                    itineraryFixture(values, if (values == original) 100.0 else 60.0, at = at, transport = mode, preferences = actual)
                })
            val proposal = requireNotNull(planner.propose(original, 200_000L, Transport.MOTORCYCLE, preferences))
            assertEquals(listOf(200_000L to RouteProvider.VALHALLA, 200_000L to RouteProvider.VALHALLA), calls)
            assertEquals(original.first(), proposal.orderedStops.first())
            assertEquals(original.last(), proposal.orderedStops.last())
            assertEquals(original.groupingBy { it }.eachCount(), proposal.orderedStops.groupingBy { it }.eachCount())
            assertTrue(proposal.heuristic)
            assertFalse(proposal.approximateMotorcycle)
            assertEquals(40.0, proposal.savedSeconds, 0.0)
            assertTrue(proposal.canApplyTo(original, Transport.MOTORCYCLE, preferences, "stable", 1000L, settings))
            assertFalse(proposal.canApplyTo(original, Transport.MOTORCYCLE, preferences, "stable", 1000L,
                settings.copy(routeEndpoint = "https://changed.invalid/route")))
        }
    }

    @Test fun unchangedLocalOrderMakesNoRouteOrMatrixRequest() = runBlocking {
        val values = (0..4).map { RouteStop("$it", WeatherCoordinate(35.0, 25.0 + it * .001)) }
        val planner = ConfiguredStopOrderPlanner({ credentials }, { 1000L },
            matrix = { _, _, _, _, _ -> error("No Matrix request") },
            largeRoute = { _, _, _, _, _ -> error("No route request when order is unchanged") })
        assertNull(planner.propose(values, 200_000L, Transport.MOTORCYCLE))
    }

    @Test fun equalOrWorseActualDurationNeverBecomesAnImprovementProposal() = runBlocking {
        val original = stops(4)
        for (candidateDuration in listOf(100.0, 120.0)) {
            var calls = 0
            val planner = ConfiguredStopOrderPlanner({ credentials }, { 1000L },
                matrix = { _, _, _, _, _ -> error("No Matrix") }, largeRoute = { values, at, mode, actual, _ ->
                    itineraryFixture(values, if (++calls == 1) 100.0 else candidateDuration, at = at, transport = mode, preferences = actual)
                })
            assertNull(planner.propose(original, 200_000L, Transport.MOTORCYCLE))
            assertEquals(2, calls)
        }
    }

    @Test fun duplicateOccurrencesAndBreaksSurviveTheProposal() = runBlocking {
        val original = stops(6).toMutableList().also { it[3] = it[2] }
        val planner = ConfiguredStopOrderPlanner({ credentials }, { 1000L }, largeRoute = { values, at, mode, actual, _ ->
            itineraryFixture(values, if (values == original) 100.0 else 60.0, at = at, transport = mode, preferences = actual)
        })
        val proposal = requireNotNull(planner.propose(original, 200_000L, Transport.MOTORCYCLE))
        assertEquals(original.groupingBy { it }.eachCount(), proposal.orderedStops.groupingBy { it }.eachCount())
        assertEquals(2, proposal.orderedStops.count { it == original[2] })
        assertEquals(original.sumOf { it.plannedBreakSeconds }, proposal.orderedStops.sumOf { it.plannedBreakSeconds })
    }

    @Test fun endpointReplacementAndCancellationRejectProposalBeforeSecondRequest() {
        var settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        var calls = 0
        val planner = ConfiguredStopOrderPlanner({ credentials }, { 1000L }, largeSettings = { settings }, largeRoute = { values, at, mode, actual, _ ->
            calls++
            settings = settings.copy(routeEndpoint = "https://changed.invalid/route")
            itineraryFixture(values, 100.0, at = at, transport = mode, preferences = actual)
        })
        assertThrows(CancellationException::class.java) { runBlocking { planner.propose(stops(4), 200_000L, Transport.MOTORCYCLE) } }
        assertEquals(1, calls)
        val cancelled = ConfiguredStopOrderPlanner({ credentials }, { 1000L }, largeRoute = { _, _, _, _, _ -> throw CancellationException("Synthetic cancellation") })
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.propose(stops(4), 200_000L, Transport.MOTORCYCLE) } }
    }

    @Test fun previouslySelectedMotorcycleTomTomMatrixProposalCannotBeApplied() {
        val original = stops(4)
        val ordered = listOf(original[0], original[2], original[1], original[3])
        val before = itineraryFixture(original, 100.0, RouteProvider.TOMTOM, 200_000L, Transport.MOTORCYCLE)
        val after = itineraryFixture(ordered, 60.0, RouteProvider.TOMTOM, 200_000L, Transport.MOTORCYCLE)
        val legacy = StopOrderProposal(original, ordered, before, after, 200_000L, true, 1000L, "stable")
        assertFalse(legacy.canApplyTo(original, Transport.MOTORCYCLE, before.preferences, "stable", 1000L))
    }
}
