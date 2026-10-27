package org.iz.navigation.car

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.DriveCameraMode
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class CarDrivingCameraTest {
    @Test fun guidanceAndConfirmedRecordingActivateOnlyCarAndMotorcycle() {
        for (mode in listOf(Transport.CAR, Transport.MOTORCYCLE)) {
            assertTrue(carDriveCameraEnabled(NavigationState(guidance = true, sessionTransport = mode)))
            assertTrue(carDriveCameraEnabled(NavigationState(recording = true,
                journey = Journey(transport = mode, status = JourneyStatus.CONFIRMED))))
            assertFalse(carDriveCameraEnabled(NavigationState(recording = true,
                journey = Journey(transport = mode, status = JourneyStatus.TEMPORARY))))
        }
        for (mode in Transport.entries.filter { it !in listOf(Transport.CAR, Transport.MOTORCYCLE) }) {
            assertFalse(carDriveCameraEnabled(NavigationState(guidance = true, sessionTransport = mode)))
        }
        assertFalse(carDriveCameraEnabled(NavigationState(sessionTransport = Transport.CAR)))
    }

    @Test fun panZoomAndOverviewHoldUntilExplicitRecenterAndPreviewPreservesHold() {
        val controls = CarCameraInteraction()
        assertTrue(controls.following)
        controls.manual()
        assertFalse(controls.following)
        controls.preview(true)
        controls.preview(false)
        assertFalse(controls.following)
        controls.setMode(DriveCameraMode.SIMPLE_2D)
        assertFalse(controls.following)
        controls.recenter()
        assertTrue(controls.following)
        controls.preview(true)
        assertFalse(controls.following)
        controls.preview(false)
        assertTrue(controls.following)
        controls.manual()
        controls.preview(false)
        assertFalse(controls.following)
    }
}
