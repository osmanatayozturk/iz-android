package org.iz.navigation.watch

import org.junit.Assert.*
import org.junit.Test

class WatchSensorRegistrationTest {
    @Test fun losingEligibilityInvalidatesAnExistingRegistration() {
        val registration = WatchSensorRegistration()
        assertTrue(registration.ensure(true, true, 1) { true })
        var released = false
        assertFalse(registration.ensure(false, true, 2, unregister = { released = true }) { error("idle sensor must not register") })
        assertTrue(released)
        assertFalse(registration.registered)
    }
    @Test fun callbacksMustBeFreshOrderedAndFromTheCurrentRegistration() {
        val registration = WatchSensorRegistration()
        registration.ensure(true, true, 1, nowNanos = 1_000_500) { true }
        val generation = registration.generation
        assertFalse(registration.accept(generation, 1_000_499, 2_000_000))
        assertFalse(registration.accept(generation, 1_000_500, 2_000_000))
        assertFalse(registration.accept(generation, 2_000_001, 2_000_000))
        assertTrue(registration.accept(generation, 1_000_501, 2_000_000))
        assertFalse(registration.accept(generation, 1_000_501, 2_000_000))
        assertFalse(registration.accept(generation, 1_000_502, 31_000_000_000))
        registration.reset()
        registration.ensure(true, true, 2) { true }
        assertFalse(registration.accept(generation, 2_000_001, 3_000_000))
    }
    @Test fun missingHeartRetriesWithoutTouchingWorkingStepRegistration() {
        val heart = WatchSensorRegistration(); val steps = WatchSensorRegistration()
        var attempts = 0
        assertFalse(heart.ensure(true, true, 0) { attempts++; false })
        assertTrue(steps.ensure(true, true, 0) { true })
        assertFalse(heart.ensure(true, true, 29_999) { error("too early") })
        assertTrue(steps.ensure(true, true, 30_000) { error("working sensor must not be registered twice") })
        assertTrue(heart.ensure(true, true, 30_000) { attempts++; true })
        assertEquals(2, attempts)
        assertEquals(0L, steps.registeredSince)
        assertEquals(30_000L, heart.registeredSince)
    }
    @Test fun offBodyListenerCanRecoverAndAbsentOrInactiveSensorsAreNeverPolled() {
        val body = WatchSensorRegistration()
        assertFalse(body.ensure(false, true, 0) { error("disabled") })
        assertFalse(body.ensure(true, false, 0) { error("absent") })
        assertFalse(body.ensure(true, true, 0) { false })
        assertTrue(body.ensure(true, true, 30_000) { true })
        body.reset()
        assertFalse(body.registered)
        assertTrue(body.ensure(true, true, 31_000) { true })
    }
}
