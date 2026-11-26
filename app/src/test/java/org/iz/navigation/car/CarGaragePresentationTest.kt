package org.iz.navigation.car

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.garage.GarageVehicle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CarGaragePresentationTest {
    @Test fun implicitStartNeverBackfillsARecordedOrPendingJourneyOrOverwritesAnUnrecordedChoice() {
        val unrecorded = NavigationState(sessionId = "session")
        assertTrue(carShouldBindStartVehicle(unrecorded, explicit = false, hasUnrecordedSelection = false))
        assertFalse(carShouldBindStartVehicle(unrecorded, explicit = false, hasUnrecordedSelection = true))
        assertTrue(carShouldBindStartVehicle(unrecorded, explicit = true, hasUnrecordedSelection = true))
        for (recorded in listOf(unrecorded.copy(recording = true), unrecorded.copy(recordingPending = true),
            unrecorded.copy(journey = Journey(transport = Transport.CAR)),
            unrecorded.copy(journey = Journey(transport = Transport.CAR, status = JourneyStatus.TEMPORARY)))) {
            assertFalse(carShouldBindStartVehicle(recorded, explicit = false, hasUnrecordedSelection = false))
            assertTrue(carShouldBindStartVehicle(recorded, explicit = true, hasUnrecordedSelection = false))
        }
        assertFalse(carShouldBindStartVehicle(unrecorded.copy(simulation = true), explicit = true, hasUnrecordedSelection = false))
        assertFalse(carShouldBindStartVehicle(NavigationState(), explicit = true, hasUnrecordedSelection = false))
    }
    private fun vehicle(id: String, mode: Transport) = GarageVehicle(id = id, name = id, transport = mode, capacityLitres = 12.0)

    @Test fun emptyGarageAndOneMatchingVehicleCanStartButMultipleMustChooseEachTime() = runTest {
        val car = vehicle("car", Transport.CAR)
        val first = vehicle("bike-a", Transport.MOTORCYCLE)
        val second = vehicle("bike-b", Transport.MOTORCYCLE)
        val started = mutableListOf<String?>()
        var choices = 0
        suspend fun resolve(fleet: List<GarageVehicle>) = resolveCarStartVehicle(flowOf(fleet), Transport.MOTORCYCLE,
            { true }, { started += it }, { choices++ })
        resolve(emptyList())
        resolve(listOf(car, first))
        repeat(2) { resolve(listOf(car, first, second)) }
        assertEquals(listOf(null, "bike-a"), started)
        assertEquals(2, choices)
    }

    @Test fun lostOwnerOrGarageReadFailureDoesNotStartOrChoose() = runTest {
        var effects = 0
        try {
            resolveCarStartVehicle(flowOf(emptyList()), Transport.CAR, { false }, { effects++ }, { effects++ })
            fail("A stale owner must fail")
        } catch (_: IllegalStateException) { }
        try {
            resolveCarStartVehicle(flow { throw IllegalArgumentException("read failure") }, Transport.CAR,
                { true }, { effects++ }, { effects++ })
            fail("A failed read must not be treated as an empty garage")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, effects)
    }

    @Test fun choiceCannotApplyToAnotherSessionRouteModeOrPromotedCandidate() {
        val state = NavigationState(sessionId = "automatic", recording = true,
            journey = Journey(id = "candidate", transport = Transport.UNKNOWN, status = JourneyStatus.TEMPORARY))
        val expected = carVehicleStartSnapshot(state, Transport.MOTORCYCLE)
        assertEquals(expected, carVehicleStartSnapshot(state.copy(message = "GPS", gpsStale = false), Transport.MOTORCYCLE))
        for (changed in listOf(state.copy(sessionId = "other"), state.copy(routeRevision = 1),
            state.copy(sessionTransport = Transport.CAR),
            state.copy(guidance = true), state.copy(simulation = true),
            state.copy(journey = state.journey!!.copy(status = JourneyStatus.CONFIRMED)),
            state.copy(journey = state.journey!!.copy(id = "other")))) {
            assertNotEquals(expected, carVehicleStartSnapshot(changed, Transport.MOTORCYCLE))
        }
        assertNotEquals(expected, carVehicleStartSnapshot(state, Transport.CAR))
    }

    @Test fun deadlineAndOwnershipPreventOldOrDoubleOfferActions() {
        assertTrue(carFuelActionAllowed("offer", "offer", 20_000, 19_999, visible = true, busy = false))
        assertFalse(carFuelActionAllowed("offer", "offer", 20_000, 20_000, visible = true, busy = false))
        assertFalse(carFuelActionAllowed("offer", "new", 20_000, 10, visible = true, busy = false))
        assertFalse(carFuelActionAllowed("offer", null, 20_000, 10, visible = true, busy = false))
        assertFalse(carFuelActionAllowed("offer", "offer", 20_000, 10, visible = false, busy = false))
        assertFalse(carFuelActionAllowed("offer", "offer", 20_000, 10, visible = true, busy = true))
    }
}
