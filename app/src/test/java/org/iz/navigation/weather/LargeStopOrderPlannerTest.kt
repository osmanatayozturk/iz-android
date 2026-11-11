package org.iz.navigation.weather

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class LargeStopOrderPlannerTest {
    private val stops = listOf(0.0, .08, .02, .06, .04, .10).mapIndexed { i, lon ->
        RouteStop("$i", WeatherCoordinate(0.0, lon), if (i in 1..4) i * 60 else 0)
    }

    @Test fun largeProposalUsesActualSameProviderRoutesWithoutMatrixAndNeedsNoMatrixEntitlement() = runBlocking {
        val requests = mutableListOf<Pair<Long, RouteProvider?>>()
        val planner = ConfiguredStopOrderPlanner(
            credentials = { TrafficCredentials(null, false, false, "fixture") },
            clock = { 1_000L },
            matrix = { _, _, _, _, _ -> error("Large plans must not use matrix") },
            largeRoute = { values, at, mode, preferences, provider ->
                requests += at to provider
                itineraryFixture(values, if (values == stops) 100.0 else 60.0, at = at,
                    transport = mode, preferences = preferences)
            },
        )
        val proposal = requireNotNull(planner.propose(stops, 200_000L, Transport.WALK))
        assertEquals(stops, proposal.originalStops)
        assertEquals(40.0, proposal.savedSeconds, 0.0)
        assertTrue(proposal.heuristic)
        assertFalse(proposal.approximateMotorcycle)
        assertEquals(listOf(200_000L to null, 200_000L to RouteProvider.VALHALLA), requests)
    }

    @Test fun differentProviderOrSlowerActualRouteCannotProduceAnImprovementProposal() = runBlocking {
        for (differentProvider in listOf(false, true)) {
            var calls = 0
            val planner = ConfiguredStopOrderPlanner(
                credentials = { TrafficCredentials(null, false, false, "fixture") }, clock = { 1_000L },
                largeRoute = { values, at, mode, preferences, _ ->
                    calls++
                    itineraryFixture(values, if (calls == 1) 100.0 else if (differentProvider) 60.0 else 120.0,
                        provider = if (calls == 2 && differentProvider) RouteProvider.TOMTOM else RouteProvider.VALHALLA,
                        at = at, transport = mode, preferences = preferences)
                },
            )
            if (differentProvider) assertThrows(IllegalStateException::class.java) {
                runBlocking { planner.propose(stops, 200_000L, Transport.WALK) }
            } else assertNull(planner.propose(stops, 200_000L, Transport.WALK))
        }
    }

    @Test fun changedSettingsDuringComparisonCancelTheProposalBeforeTheCandidateRequest() {
        var settings = defaultWeatherSettings(Transport.WALK)
        var calls = 0
        val planner = ConfiguredStopOrderPlanner(
            credentials = { TrafficCredentials(null, false, false, "fixture") }, clock = { 1_000L },
            largeSettings = { settings },
            largeRoute = { values, at, mode, preferences, _ ->
                calls++
                settings = settings.copy(routeEndpoint = "https://changed.example/route")
                itineraryFixture(values, at = at, transport = mode, preferences = preferences)
            },
        )
        assertThrows(CancellationException::class.java) { runBlocking {
            planner.propose(stops, 200_000L, Transport.WALK)
        } }
        assertEquals(1, calls)
    }

    @Test fun comparisonThatExpiresDuringTheCandidateRequestIsRejected() {
        var now = 1_000L
        var calls = 0
        val planner = ConfiguredStopOrderPlanner(
            credentials = { TrafficCredentials(null, false, false, "fixture") }, clock = { now },
            largeRoute = { values, at, mode, preferences, _ ->
                calls++
                if (calls == 2) now = at
                itineraryFixture(values, if (calls == 1) 100.0 else 60.0, at = at,
                    transport = mode, preferences = preferences)
            },
        )
        assertThrows(IllegalStateException::class.java) { runBlocking {
            planner.propose(stops, 200_000L, Transport.WALK)
        } }
    }
}
