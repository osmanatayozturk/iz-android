package org.iz.navigation.ui

import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class PhoneItineraryBoundsTest {
    private fun stops(count: Int) = (0 until count).map {
        RouteStop("$it", WeatherCoordinate(0.0, it * .001), if (it in 1 until count - 1) 60 else 0)
    }

    @Test fun twentyStopEntryIsPreservedAndTwentyOneStopEntryIsExplicitlyRejected() = runTest {
        var requests = 0
        val coordinator = PhoneDirectionsCoordinator(this,
            locate = { requests++; error("No lookup on entry") },
            plan = { _, _ -> requests++; error("No planning on entry") },
            activate = { requests++ })
        val planned = stops(20)
        coordinator.enter(1, planned, Transport.CAR, locateOnOpen = false)
        assertEquals(planned, coordinator.state.value.stops)
        coordinator.enter(2, stops(21), Transport.CAR, locateOnOpen = false)
        assertEquals(planned, coordinator.state.value.stops)
        assertTrue(coordinator.state.value.message.orEmpty().contains("20"))
        assertEquals(0, requests)
    }

    @Test fun largeOrderRemainsAProposalUntilExplicitAcceptanceAndKeepsBreakOccurrences() = runTest {
        val stops = stops(6)
        val ordered = listOf(stops[0], stops[2], stops[4], stops[1], stops[3], stops[5])
        val coordinator = PhoneDirectionsCoordinator(this, locate = { error("No location") },
            plan = { _, _ -> error("No plan while accepting order") }, activate = { error("No activation") },
            suggestOrder = { _, _, _ ->
                StopOrderProposal(stops, ordered, itineraryFixture(stops, 100.0), itineraryFixture(ordered, 60.0),
                    121_000L, false, 1_000L, "", heuristic = true)
            }, clock = { 1_000L })
        coordinator.enter(1, stops, Transport.CAR, locateOnOpen = false)
        coordinator.suggestStopOrder()!!.join()
        assertEquals(stops, coordinator.state.value.stops)
        assertTrue(coordinator.state.value.orderProposal!!.heuristic)
        coordinator.acceptStopOrder()
        assertEquals(ordered, coordinator.state.value.stops)
        assertNull(coordinator.state.value.preview)
    }

    @Test fun previewRejectsDroppedRepeatedOccurrenceEvenWithoutBreaks() = runTest {
        val a = RouteStop("A", WeatherCoordinate(0.0, 0.0))
        val b = RouteStop("B", WeatherCoordinate(0.0, .01))
        val selected = listOf(a, b, b, a)
        val coordinator = PhoneDirectionsCoordinator(this, locate = { a.coordinate },
            plan = { values, _ -> itineraryFixture(values.distinct()) }, activate = { error("No activation") })
        coordinator.enter(1, selected, Transport.CAR, locateOnOpen = false)
        coordinator.preview()!!.join()
        assertNull(coordinator.state.value.preview)
        assertNotNull(coordinator.state.value.message)
        assertEquals(selected, coordinator.state.value.stops)
    }

    @Test fun changedRouteSettingsAfterTheProposalPreventAcceptance() = runTest {
        val selected = stops(6)
        val ordered = listOf(selected[0], selected[2], selected[1], selected[3], selected[4], selected[5])
        var settings = defaultWeatherSettings(Transport.CAR)
        val coordinator = PhoneDirectionsCoordinator(this, locate = { error("No location") },
            plan = { _, _ -> error("No planning") }, activate = { error("No activation") },
            readSettings = { settings }, clock = { 1_000L },
            suggestOrder = { _, _, _ ->
                StopOrderProposal(selected, ordered, itineraryFixture(selected, 100.0), itineraryFixture(ordered, 60.0),
                    121_000L, false, 1_000L, "", heuristic = true, routeSettings = settings)
            })
        coordinator.enter(1, selected, Transport.CAR, locateOnOpen = false)
        coordinator.suggestStopOrder()!!.join()
        settings = settings.copy(routeEndpoint = "https://changed.example/route")
        coordinator.acceptStopOrder()
        assertEquals(selected, coordinator.state.value.stops)
        assertNull(coordinator.state.value.orderProposal)
    }
}
