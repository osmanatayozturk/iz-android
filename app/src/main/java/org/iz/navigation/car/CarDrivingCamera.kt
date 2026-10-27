package org.iz.navigation.car

import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.DriveCameraMode
import org.iz.navigation.navigation.NavigationState

internal fun carDriveCameraEnabled(state: NavigationState): Boolean {
    val transport = state.sessionTransport ?: state.journey?.transport
    return transport in setOf(Transport.CAR, Transport.MOTORCYCLE) &&
        (state.guidance || state.recording && state.journey?.status == JourneyStatus.CONFIRMED)
}

/** Local surface interaction state. Preview dismissal must not silently cancel a manual hold. */
internal class CarCameraInteraction {
    var following = true
        private set
    var mode = DriveCameraMode.ADAPTIVE
        private set
    private var previewing = false
    private var followBeforePreview = true
    fun manual() { following = false; if (previewing) followBeforePreview = false }
    fun recenter() { following = true; if (previewing) followBeforePreview = true }
    fun setMode(value: DriveCameraMode) { mode = value }
    fun preview(value: Boolean) {
        if (value && !previewing) { followBeforePreview = following; following = false }
        if (!value && previewing) following = followBeforePreview
        previewing = value
    }
}
