package org.iz.navigation.tracking

import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneStepPolicyTest {
    @Test fun walkingToRunningEditKeepsStepMeasurementEligibleOnTheSameJourney() {
        val walk = Journey(id = "same-trip", transport = Transport.WALK, startedAt = 1_000, stepCount = 56)
        assertTrue(canMeasurePhoneSteps(walk, walk.id, permissionGranted = true))
        assertTrue(canMeasurePhoneSteps(walk.copy(transport = Transport.RUN), walk.id, permissionGranted = true))
        assertFalse(canMeasurePhoneSteps(walk.copy(transport = Transport.CAR), walk.id, permissionGranted = true))
    }

    @Test fun permissionRevocationOrFinishingStopsBothWalkingAndRunningMeasurement() {
        listOf(Transport.WALK, Transport.RUN).forEach { mode ->
            val journey = Journey(transport = mode, startedAt = 1_000)
            assertFalse(canMeasurePhoneSteps(journey, journey.id, permissionGranted = false))
            assertFalse(canMeasurePhoneSteps(journey.copy(endedAt = 2_000), journey.id, permissionGranted = true))
        }
    }

    @Test fun aNewJourneyCannotReceiveThePreviousRunsQueuedSteps() {
        val old = Journey(id = "old-run", transport = Transport.RUN, startedAt = 1_000)
        assertFalse(canMeasurePhoneSteps(old, "new-run", permissionGranted = true))
        assertFalse(canMeasurePhoneSteps(old, null, permissionGranted = true))
    }
}
