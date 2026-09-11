package org.iz.navigation.car

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CarDisplayGuardTest {
    @Test fun disconnectedDisplayDoesNotEscapeAndNextUpdateStillRuns() {
        var updates = 0
        assertFalse(safelyUpdateCarDisplay { throw IllegalStateException("Host disconnected") })
        assertTrue(safelyUpdateCarDisplay { updates++ })
        assertEquals(1, updates)
    }
    @Test fun cancellationStillCancelsCollector() {
        val cancellation = CancellationException("screen closed")
        try {
            safelyUpdateCarDisplay { throw cancellation }
            fail("Cancellation must reach coroutine owner")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }
}
