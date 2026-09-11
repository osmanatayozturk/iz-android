package org.iz.navigation.watch

import org.junit.Assert.*
import org.junit.Test

class WatchHealthSensorControllerTest {
    private class Hardware : WatchHealthSensorController.Hardware {
        val listeners = mutableMapOf<WatchHealthSensorController.Kind, Long>()
        var registrations = 0
        override fun available(kind: WatchHealthSensorController.Kind) = true
        override fun register(kind: WatchHealthSensorController.Kind, generation: Long): Boolean {
            check(listeners.put(kind, generation) == null); registrations++; return true
        }
        override fun unregister(kind: WatchHealthSensorController.Kind) { listeners.remove(kind) }
    }
    private val body = WatchHealthSensorController.Kind.BODY
    private fun start(c: WatchHealthCapture, p: WatchHealthSensorController, h: Hardware, nanos: Long = 1_000_000_000L) {
        c.attach("s", "trip", 0, 100_000)
        p.update(true, nanos)
        assertEquals(setOf(body), h.listeners.keys)
        assertNull(c.worn)
        assertTrue(p.onBody(h.listeners.getValue(body), nanos + 1, nanos + 1, true))
        p.update(true, nanos + 1)
        assertEquals(3, h.listeners.size)
    }
    @Test fun idleAndTemporaryStateKeepEverySensorOff() {
        val c = WatchHealthCapture(); val h = Hardware(); val p = WatchHealthSensorController(c, h) {}
        p.update(true, 1_000_000_000)
        assertTrue(h.listeners.isEmpty()); assertEquals(0, h.registrations)
        start(c, p, h)
        c.detach() // Temporary/nonrecording snapshot invalidates the confirmed session.
        p.update(true, 2_000_000_000)
        assertTrue(h.listeners.isEmpty()); assertFalse(p.collecting); assertNull(c.worn)
    }
    @Test fun removalRetainsOnlyBodyAndRewearResetsStepBaseline() {
        val c = WatchHealthCapture(); val h = Hardware(); val p = WatchHealthSensorController(c, h) {}
        start(c, p, h)
        c.steps(100.0, 2000, 2000)
        assertEquals(3.0, c.steps(103.0, 3000, 3000)!!.value, 0.0)
        val token = h.listeners.getValue(body)
        assertTrue(p.onBody(token, 4_000_000_000, 4_000_000_000, false)); p.update(true, 4_000_000_000)
        assertEquals(setOf(body), h.listeners.keys); assertFalse(p.collecting)
        assertTrue(p.onBody(token, 5_000_000_000, 5_000_000_000, true)); p.update(true, 5_000_000_000)
        assertEquals(3, h.listeners.size)
        assertNull(c.steps(200.0, 6000, 6000))
        assertEquals(1.0, c.steps(201.0, 7000, 7000)!!.value, 0.0)
    }
    @Test fun finishPermissionLossAndExactLeaseBoundaryUnregisterEverything() {
        for (reason in listOf("finish", "permission", "expiry")) {
            val c = WatchHealthCapture(); val h = Hardware(); val p = WatchHealthSensorController(c, h) {}
            start(c, p, h)
            if (reason == "expiry") { p.update(true, 99_999_000_000); assertEquals(3, h.listeners.size) }
            if (reason == "finish") c.detach()
            p.update(reason != "permission", if (reason == "expiry") 100_000_000_000 else 2_000_000_000)
            assertTrue(reason, h.listeners.isEmpty()); assertNull(c.sessionId); assertNull(c.worn); assertFalse(p.collecting)
        }
    }
    @Test fun sameSessionRenewalPreservesListenersAndStepsButNewSessionRejectsOldCallbacks() {
        val c = WatchHealthCapture(); val h = Hardware(); val p = WatchHealthSensorController(c, h) {}
        start(c, p, h)
        val old = h.listeners.toMap()
        c.steps(100.0, 2000, 2000)
        c.attach("s", "trip", 0, 200_000); p.update(true, 3_000_000_000)
        assertEquals(old, h.listeners); assertEquals(3, h.registrations)
        assertEquals(4.0, c.steps(104.0, 4000, 4000)!!.value, 0.0)
        c.attach("s2", "trip2", 0, 200_000); p.update(true, 5_000_000_500)
        assertEquals(setOf(body), h.listeners.keys); assertNull(c.worn)
        assertFalse(p.onBody(old.getValue(body), 5_000_000_600, 5_000_000_700, true))
        val fresh = h.listeners.getValue(body)
        assertFalse(p.onBody(fresh, 5_000_000_701, 5_000_000_700, true))
        assertTrue(p.onBody(fresh, 5_000_000_600, 5_000_000_700, true)); p.update(true, 5_000_000_700)
        assertFalse(p.onBody(old.getValue(body), 5_000_000_800, 5_000_000_800, false))
        assertFalse(p.onBody(fresh, 5_000_000_550, 5_000_000_800, false))
        assertTrue(c.worn!!); assertEquals(3, h.listeners.size)
        assertFalse(p.accept(WatchHealthSensorController.Kind.HEART, old.getValue(WatchHealthSensorController.Kind.HEART), 6_000_000_000, 6_000_000_000))
        assertNull(c.steps(200.0, 6000, 6000))
    }
    @Test fun currentListenerAcceptsCachedInitialBodyStateWithoutRewearButOldListenersCannotChangeIt() {
        val c = WatchHealthCapture(); val h = Hardware(); val p = WatchHealthSensorController(c, h) {}
        start(c, p, h)
        val old = h.listeners.getValue(body)
        c.attach("new", "trip2", 0, 200_000); p.update(true, 60_000_000_000)
        val current = h.listeners.getValue(body)
        assertFalse(p.onBody(old, 1_000_000_000, 60_000_000_000, true))
        assertFalse(p.onBody(old, 60_000_000_001, 60_000_000_001, false))
        assertNull(c.worn)
        // SensorService forwards the cached ON_CHANGE state with its original timestamp.
        assertTrue(p.onBody(current, 1_000_000_000, 60_000_000_001, true))
        p.update(true, 60_000_000_001)
        assertEquals(3, h.listeners.size); assertTrue(c.worn!!)
        val heart = WatchHealthSensorController.Kind.HEART
        assertFalse(p.accept(heart, h.listeners.getValue(heart), 1_000_000_000, 60_000_000_001))
        assertFalse(p.onBody(current, 999_999_999, 60_000_000_001, false))
        assertFalse(p.onBody(current, 2_000_000_000, 60_000_000_001, false))
        assertFalse(p.onBody(old, 61_000_000_000, 61_000_000_000, false))
        assertTrue(c.worn!!)
        assertTrue(p.onBody(current, 61_000_000_000, 61_000_000_000, false))
        p.update(true, 61_000_000_000)
        assertEquals(setOf(body), h.listeners.keys)
        assertFalse(p.onBody(current, 60_500_000_000, 61_000_000_000, true))
        assertTrue(p.onBody(current, 62_000_000_000, 62_000_000_000, true))
        p.update(true, 62_000_000_000)
        assertEquals(3, h.listeners.size)
    }
}
