package org.iz.navigation.integration

import org.iz.navigation.navigation.DriveCameraViewport
import org.junit.Assert.*
import org.junit.Test

class MotorcycleMapAdapterPolicyTest {
    @Test fun plannerAndActionsRoundTripCannotReleaseManualHold() {
        val follow = DrivingMapFollowPolicy()
        assertTrue(follow.follows(true))
        follow.userGesture()
        repeat(2) {
            assertFalse(follow.follows(false)) // Planning/Actions opens.
            assertFalse(follow.follows(true)) // Dismissal must not resume GPS follow.
        }
        follow.recenter()
        assertTrue(follow.follows(true))
    }
    @Test fun fullyOccludedAndUnknownViewportsCannotMoveDrivingCamera() {
        assertFalse(drivingMapHasVisibleViewport(DriveCameraViewport(640, 320, 28, 220, 84, 112)))
        assertFalse(drivingMapHasVisibleViewport(DriveCameraViewport(640, 320, 28, 208, 84, 112)))
        assertFalse(drivingMapHasVisibleViewport(DriveCameraViewport(640, 320, 28, 20, 84, 112, false)))
        assertFalse(drivingMapHasVisibleViewport(DriveCameraViewport(640, 320, Int.MAX_VALUE, 0, Int.MAX_VALUE, 0)))
        assertFalse(drivingMapHasVisibleViewport(DriveCameraViewport(640, 320, -1, 0, 0, 0)))
    }
    @Test fun viewportCanRecoverWithoutReleasingManualHold() {
        val follow = DrivingMapFollowPolicy()
        follow.userGesture()
        assertFalse(drivingMapHasVisibleViewport(DriveCameraViewport(640, 320, 28, 300, 84, 112)))
        assertTrue(drivingMapHasVisibleViewport(DriveCameraViewport(360, 640, 28, 260, 84, 112)))
        assertFalse(follow.follows(true))
    }
}