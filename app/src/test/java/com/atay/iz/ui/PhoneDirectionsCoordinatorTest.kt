package com.atay.iz.ui

import com.atay.iz.data.Transport
import com.atay.iz.weather.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneDirectionsCoordinatorTest {
    private val current = WeatherCoordinate(41.0, 29.0)
    private val custom = RouteStop("Custom A", WeatherCoordinate(41.02, 29.02))
    private val destination = RouteStop("B", WeatherCoordinate(41.05, 29.05))

    @Test fun lateOpeningGpsCannotReplaceManualOrigin() = runBlocking {
        val gps = CompletableDeferred<WeatherCoordinate>()
        val coordinator = PhoneDirectionsCoordinator(this, { withContext(NonCancellable) { gps.await() } }, ::route, {})
        coordinator.enter(1)
        yield()
        coordinator.setOrigin(custom)
        gps.complete(current)
        yield()
        assertEquals(custom, coordinator.state.value.origin)
        assertFalse(coordinator.state.value.originCurrent)
        assertFalse(coordinator.state.value.locating)
    }

    @Test fun freshEntryClearsOldPreviewAndRestoresCurrentOrigin() = runBlocking {
        val coordinator = PhoneDirectionsCoordinator(this, { current }, ::route, {})
        coordinator.enter(1, listOf(custom, destination), Transport.BICYCLE)
        coordinator.preview()!!.join()
        assertNotNull(coordinator.state.value.preview)
        coordinator.enter(2)
        yield()
        assertNull(coordinator.state.value.preview)
        assertNull(coordinator.state.value.destination)
        assertTrue(coordinator.state.value.originCurrent)
        assertEquals(current, coordinator.state.value.origin?.coordinate)
    }

    @Test fun weatherStopsAndModeArePreservedAcrossSameEntryRecomposition() = runBlocking {
        var locationCalls = 0
        val stops = listOf(custom, RouteStop("Via", WeatherCoordinate(41.03, 29.03)), destination)
        val coordinator = PhoneDirectionsCoordinator(this, { locationCalls++; current }, ::route, {})
        coordinator.enter(7, stops, Transport.RUN)
        coordinator.enter(7)
        yield()
        assertEquals(stops, coordinator.state.value.stops)
        assertEquals(Transport.RUN, coordinator.state.value.transport)
        assertFalse(coordinator.state.value.originCurrent)
        assertEquals(0, locationCalls)
    }

    @Test fun weatherCurrentOriginRefreshesBeforeStartAndPreservesVia() = runBlocking {
        val via = RouteStop("Via", WeatherCoordinate(41.03, 29.03))
        val importedCurrent = RouteStop("Mevcut konum", WeatherCoordinate(40.0, 28.0))
        var started: PlannedRoute? = null
        val coordinator = PhoneDirectionsCoordinator(this, { current }, ::route, { started = it })
        coordinator.enter(1, listOf(importedCurrent, via, destination), Transport.WALK)
        coordinator.preview()!!.join()
        coordinator.start()!!.join()
        assertTrue(coordinator.state.value.originCurrent)
        assertEquals(listOf(current, via.coordinate, destination.coordinate), started?.stops?.map { it.coordinate })
    }

    @Test fun previewDoesNotActivateAndEveryModeReachesStartUnchanged() = runBlocking {
        for (mode in Transport.entries.filter { it != Transport.UNKNOWN }) {
            val activations = mutableListOf<PlannedRoute>()
            val coordinator = PhoneDirectionsCoordinator(this, { current }, ::route, { activations += it })
            coordinator.enter(1, listOf(custom, destination), mode)
            coordinator.preview()!!.join()
            assertTrue(activations.isEmpty())
            assertEquals(mode, coordinator.state.value.preview?.transport)
            coordinator.start()!!.join()
            assertEquals(1, activations.size)
            assertEquals(mode, activations.single().transport)
            assertEquals(listOf(current, custom.coordinate, destination.coordinate), activations.single().stops.map { it.coordinate })
            assertEquals(custom, coordinator.state.value.origin)
            assertNull(coordinator.state.value.preview)
        }
    }

    @Test fun startRefreshesCurrentOriginAndRejectsDuplicateStart() = runBlocking {
        var fix = current
        val gate = CompletableDeferred<Unit>()
        val activations = mutableListOf<PlannedRoute>()
        val coordinator = PhoneDirectionsCoordinator(this, { fix }, ::route, { gate.await(); activations += it })
        coordinator.enter(1)
        yield()
        coordinator.setDestination(destination)
        coordinator.preview()!!.join()
        fix = WeatherCoordinate(41.01, 29.01)
        val start = coordinator.start()!!
        yield()
        assertNull(coordinator.start())
        coordinator.setOrigin(custom)
        gate.complete(Unit)
        start.join()
        assertEquals(1, activations.size)
        assertEquals(listOf(fix, destination.coordinate), activations.single().stops.map { it.coordinate })
        assertTrue(coordinator.state.value.originCurrent)
    }

    @Test fun editedDestinationRejectsLatePreviewAndAllowsNewPreview() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val coordinator = PhoneDirectionsCoordinator(this, { current }, { stops, mode ->
            if (++requests == 1) withContext(NonCancellable) { gate.await() }
            route(stops, mode)
        }, {})
        coordinator.enter(1, listOf(custom, destination))
        val old = coordinator.preview()!!
        yield()
        val next = RouteStop("Next", WeatherCoordinate(41.1, 29.1))
        coordinator.setDestination(next)
        coordinator.preview()!!.join()
        gate.complete(Unit)
        old.join()
        assertEquals(next, coordinator.state.value.preview?.stops?.last())
        assertFalse(coordinator.state.value.busy)
    }

    @Test fun permissionFailureKeepsBothEndpointsEditable() = runBlocking {
        val coordinator = PhoneDirectionsCoordinator(this, { error("Location permission") }, ::route, {})
        coordinator.enter(1)
        yield()
        assertFalse(coordinator.state.value.locating)
        assertNotNull(coordinator.state.value.message)
        coordinator.setOrigin(custom)
        coordinator.setDestination(destination)
        coordinator.preview()!!.join()
        assertEquals(custom, coordinator.state.value.preview?.stops?.first())
    }

    @Test fun finishCancelsSlowPreviewAndCannotBeOverwrittenByItsLateResult() = runBlocking {
        val network = CompletableDeferred<Unit>()
        var finished = false
        val coordinator = PhoneDirectionsCoordinator(this, { current }, { stops, mode ->
            withContext(NonCancellable) { network.await() }
            route(stops, mode)
        }, {})
        val work = PhoneNavigationWork(this, {}, { throw it })
        coordinator.enter(1, listOf(custom, destination))
        val preview = coordinator.preview()!!
        yield()
        try {
            finishNavigationWork(coordinator, work) { finished = true }
            yield()
            assertTrue("Finishing must not wait for read-only route HTTP", finished)
            assertFalse(coordinator.state.value.previewing)
        } finally { network.complete(Unit); preview.join() }
        assertNull(coordinator.state.value.preview)
    }

    @Test fun finishCannotInterruptAnAlreadyOwnedStartMutation() = runBlocking {
        val recording = CompletableDeferred<Unit>()
        var finished = false
        val coordinator = PhoneDirectionsCoordinator(this, { current }, ::route, { recording.await() })
        val work = PhoneNavigationWork(this, {}, { throw it })
        coordinator.enter(1, listOf(custom, destination))
        coordinator.preview()!!.join()
        val start = coordinator.start()!!
        yield()
        finishNavigationWork(coordinator, work) { finished = true }
        yield()
        assertFalse(finished)
        recording.complete(Unit)
        start.join()
    }

    @Test fun groupCurrentOriginRefreshesAtStartWithoutBecomingAnOldWaypoint() = runBlocking {
        val oldOrigin = RouteStop("Konumum", current)
        val via = RouteStop("Grup durağı", WeatherCoordinate(41.03, 29.03))
        var fix = current
        var started: PlannedRoute? = null
        val coordinator = PhoneDirectionsCoordinator(this, { fix }, ::route, { started = it })
        coordinator.enter(77, listOf(oldOrigin, via, destination), Transport.BICYCLE)
        coordinator.preview()!!.join()
        fix = WeatherCoordinate(41.002, 29.0)
        coordinator.start()!!.join()
        assertEquals(listOf(fix, via.coordinate, destination.coordinate), started!!.stops.map { it.coordinate })
        assertTrue(coordinator.state.value.originCurrent)
    }
    private fun route(stops: List<RouteStop>, mode: Transport) = PlannedRoute(
        id = stops.last().label,
        stops = stops,
        vertices = listOf(RouteVertex(stops.first().coordinate, 0.0), RouteVertex(stops.last().coordinate, 600.0)),
        distanceMeters = 5_000.0, durationSeconds = 600.0, createdAt = 100L, transport = mode,
    )
}

