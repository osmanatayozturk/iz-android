package org.iz.navigation.tracking

import org.junit.Assert.*
import org.junit.Test

class LocationRequestStateTest {
    @Test fun failedCadenceReplacementKeepsAcknowledgedDeliveryAndCanRetry() {
        val state = LocationRequestState()
        val first = state.request(5000)!!
        state.succeeded(first)
        val change = state.request(1000)!!
        assertEquals(5000L, state.currentIntervalMillis)
        state.failed(change)
        assertTrue(state.active)
        assertEquals(5000L, state.currentIntervalMillis)
        assertEquals(1000L, state.desiredIntervalMillis)
        assertTrue(state.retryPending)
        val retry = state.request(1000)!!
        state.succeeded(retry)
        assertEquals(1000L, state.currentIntervalMillis)
        assertFalse(state.retryPending)
    }

    @Test fun lateFailureCannotEraseNewRequestOrRestartAfterStop() {
        val state = LocationRequestState()
        val first = state.request(5000)!!
        state.succeeded(first)
        val fast = state.request(1000)!!
        state.succeeded(fast)
        state.failed(first)
        assertEquals(1000L, state.currentIntervalMillis)
        state.stop()
        state.succeeded(fast)
        assertFalse(state.active)
    }

    @Test fun initialFailureIsNotReportedAsActiveDelivery() {
        val state = LocationRequestState()
        val request = state.request(5000)!!
        assertFalse(state.active)
        state.failed(request)
        assertFalse(state.active)
        assertTrue(state.retryPending)
    }
}
