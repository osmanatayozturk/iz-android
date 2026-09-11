package org.iz.navigation.car

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class CarDriveModeTest {
    @Test fun unresolvedTemporaryCandidateUsesSelectedCarModeAndCanChangeIt() {
        val state = NavigationState(recording = true, journey = Journey(status = JourneyStatus.TEMPORARY,
            transport = Transport.UNKNOWN))
        assertTrue(canSelectCarMode(state))
        listOf(Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER).forEach {
            assertEquals(it, effectiveCarMode(state, it))
        }
    }

    @Test fun knownActiveModesAreNeverSilentlyReplaced() {
        listOf(JourneyStatus.TEMPORARY, JourneyStatus.CONFIRMED).forEach { status ->
            val state = NavigationState(recording = true, journey = Journey(status = status, transport = Transport.MOTORCYCLE))
            assertFalse(canSelectCarMode(state))
            assertEquals(Transport.MOTORCYCLE, effectiveCarMode(state, Transport.CAR))
        }
    }

    @Test fun noRecordNavigationKeepsItsSessionModeOnCarDisplay() {
        val state = NavigationState(sessionId = "live", sessionTransport = Transport.MOTORCYCLE,
            guidance = true, recording = false)
        assertFalse(canSelectCarMode(state))
        assertEquals(Transport.MOTORCYCLE, effectiveCarMode(state, Transport.CAR))
    }

    @Test fun idleDriveUsesSelectedMode() {
        assertTrue(canSelectCarMode(NavigationState()))
        assertEquals(Transport.CAR, effectiveCarMode(NavigationState(), Transport.CAR))
    }
}
