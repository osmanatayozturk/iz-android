package org.iz.navigation.ui

import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class PhoneDirectionsEntryTest {
    @Test fun adoptingUiEntryPreservesPreparationAndNeverLocatesPlansOrStarts() = runTest {
        var requests = 0
        val coordinator = PhoneDirectionsCoordinator(this,
            locate = { requests++; error("No location replay") },
            plan = { _, _ -> requests++; error("No replan") }, activate = { requests++; error("No start") })
        val origin = RouteStop("A", WeatherCoordinate(41.0, 29.0))
        val destination = RouteStop("B", WeatherCoordinate(41.1, 29.1))
        coordinator.enter(1, listOf(origin, destination), Transport.CAR, locateOnOpen = false)
        val before = coordinator.state.value
        coordinator.adoptUiEntry(2)
        coordinator.enter(2, locateOnOpen = true)
        assertEquals(before, coordinator.state.value); assertEquals(0, requests)
        val context = coordinator.workingInput()
        coordinator.setDestination(destination.copy(label = "Other"))
        coordinator.restoreUiInput(context)
        assertEquals(destination, coordinator.state.value.destination); assertEquals(0, requests)
    }
}
