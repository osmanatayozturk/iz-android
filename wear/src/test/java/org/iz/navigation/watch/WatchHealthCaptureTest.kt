package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class WatchHealthCaptureTest {
    @Test fun detachAndNewSessionRequireFreshBodyConfirmation() {
        val capture = WatchHealthCapture()
        capture.attach("s", "trip", 1000, 100_000); capture.onBody(true)
        capture.attach("new", "trip2", 2000, 100_000)
        assertNull(capture.worn)
        assertFalse(capture.active(3000))
        capture.onBody(true); capture.detach()
        assertNull(capture.worn)
    }
    @Test fun resumeRejectsQueuedMeasurementsFromBeforeNewRegistration() {
        val capture = WatchHealthCapture()
        capture.attach("s", "trip", 1000, 100_000); capture.onBody(true)
        capture.resumeAt(2000)
        assertNotNull(capture.heart(80.0, 3, 2500, 2500))
        assertNull(capture.steps(100.0, 2500, 2500))
        capture.onBody(false); capture.onBody(true); capture.resumeAt(6000)
        assertNull(capture.heart(90.0, 3, 4000, 6500))
        assertNull(capture.steps(103.0, 4000, 6500))
        assertNull(capture.steps(108.0, 6500, 6500))
        assertEquals(2.0, capture.steps(110.0, 7500, 7500)!!.value, 0.0)
    }
    @Test fun captureRequiresArmedConfirmedSessionOnBodyAndFreshLease() {
        val capture = WatchHealthCapture()
        capture.attach("session", "trip", 1000, 100_000)
        assertNull(capture.heart(90.0, 3, 2000, 2000))
        capture.onBody(true)
        assertEquals(90.0, capture.heart(90.0, 3, 2000, 2000)!!.value, 0.0)
        assertNull(capture.heart(90.0, 3, 3000, 101_000))
        capture.detach()
        assertNull(capture.heart(90.0, 3, 3000, 3000))
    }
    @Test fun removalCounterResetAndUnreliableHeartDoNotInventMeasurements() {
        val capture = WatchHealthCapture()
        capture.attach("s", "trip", 1000, 100_000); capture.onBody(true)
        assertNull(capture.heart(0.0, 3, 2000, 2000))
        assertNull(capture.heart(80.0, -1, 2000, 2000))
        assertNull(capture.heart(80.0, 0, 2000, 2000))
        assertNull(capture.steps(100.0, 2000, 2000))
        assertEquals(3.0, capture.steps(103.0, 3000, 3000)!!.value, 0.0)
        capture.onBody(false)
        assertNull(capture.steps(200.0, 4000, 4000))
        capture.onBody(true)
        assertNull(capture.steps(201.0, 5000, 5000))
        assertEquals(1.0, capture.steps(202.0, 6000, 6000)!!.value, 0.0)
        assertNull(capture.steps(1.0, 7000, 7000))
        assertEquals(1.0, capture.steps(2.0, 8000, 8000)!!.value, 0.0)
    }
}
