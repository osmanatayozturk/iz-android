package com.atay.iz.ui

import com.atay.iz.data.Transport
import com.atay.iz.weather.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NavigationRecordingChoiceTest {
    private val a = RouteStop("Mevcut konum", WeatherCoordinate(41.0, 29.0))
    private val b = RouteStop("B", WeatherCoordinate(41.1, 29.1))
    private fun route(stops: List<RouteStop>, mode: Transport) = PlannedRoute("test", stops,
        listOf(RouteVertex(stops.first().coordinate, 0.0), RouteVertex(stops.last().coordinate, 600.0)),
        5000.0, 600.0, 100L, transport = mode)

    @Test fun explicitNoRecordReachesStartAndNextEntryRestoresRecording() = runBlocking {
        val choices = mutableListOf<Boolean>()
        val coordinator = PhoneDirectionsCoordinator(this, { a.coordinate }, ::route, {},
            activateWithRecording = { _, recording -> choices += recording })
        coordinator.enter(1, listOf(a, b))
        coordinator.setRecordJourney(false)
        coordinator.preview()!!.join()
        assertTrue(choices.isEmpty())
        coordinator.start()!!.join()
        assertEquals(listOf(false), choices)
        coordinator.enter(2, listOf(a, b))
        coordinator.preview()!!.join()
        coordinator.start()!!.join()
        assertEquals(listOf(false, true), choices)
    }

    @Test fun editingViasInvalidatesPreviewAndNewPlanUsesOrderedStops() = runBlocking {
        val coordinator = PhoneDirectionsCoordinator(this, { a.coordinate }, ::route, {})
        val via = RouteStop("Ara durak", WeatherCoordinate(41.05, 29.05))
        coordinator.enter(1, listOf(a, b))
        coordinator.preview()!!.join()
        coordinator.addVia(via)
        assertNull(coordinator.state.value.preview)
        coordinator.preview()!!.join()
        assertEquals(listOf(a, via, b), coordinator.state.value.preview!!.stops)
        coordinator.removeVia(0)
        assertNull(coordinator.state.value.preview)
        coordinator.preview()!!.join()
        assertEquals(listOf(a, b), coordinator.state.value.preview!!.stops)
    }
}
