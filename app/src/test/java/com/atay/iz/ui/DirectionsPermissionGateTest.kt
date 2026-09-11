package com.atay.iz.ui

import org.junit.Assert.*
import org.junit.Test

class DirectionsPermissionGateTest {
    @Test fun permissionResultContinuesExactIntentOnlyOnce() {
        for (action in DirectionsAction.entries) {
            val gate = DirectionsPermissionGate()
            gate.request(action, 10, 3)
            assertEquals(action, gate.consume(10, 3, true))
            assertNull(gate.consume(10, 3, true))
        }
    }
    @Test fun editedPlanOrNewEntryRejectsOldPermissionContinuation() {
        val gate = DirectionsPermissionGate()
        gate.request(DirectionsAction.START, 10, 3)
        assertNull(gate.consume(10, 4, true))
        gate.request(DirectionsAction.FREE_DRIVE, 10, 4)
        assertNull(gate.consume(11, 4, true))
    }
    @Test fun deniedPermissionNeverStartsAndDoesNotLeakToLaterGrant() {
        val gate = DirectionsPermissionGate()
        gate.request(DirectionsAction.START, 10, 3)
        assertNull(gate.consume(10, 3, false))
        assertNull(gate.consume(10, 3, true))
    }
}
