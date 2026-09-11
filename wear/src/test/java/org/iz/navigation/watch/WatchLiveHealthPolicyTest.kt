package org.iz.navigation.watch

import org.junit.Assert.*
import org.junit.Test

class WatchLiveHealthPolicyTest {
    private val state = WatchLiveHealthState(armed = true, onBody = true, phoneId = "p", sessionId = "s",
        journeyId = "j", capturing = true, latestHeartRate = 88.0, latestHeartAt = 1000)
    @Test fun heartRequiresExactPhoneJourneyAndSession() {
        assertEquals(88.0, WatchLiveHealthPolicy.heart(state, "p", "j", "s", 2000))
        assertNull(WatchLiveHealthPolicy.heart(state, "other", "j", "s", 2000))
        assertNull(WatchLiveHealthPolicy.heart(state, "p", "other", "s", 2000))
        assertNull(WatchLiveHealthPolicy.heart(state, "p", "j", "other", 2000))
    }
    @Test fun expiredFutureOffBodyAndStoppedSamplesAreNeverLive() {
        assertNull(WatchLiveHealthPolicy.heart(state, "p", "j", "s", 31_001))
        assertNull(WatchLiveHealthPolicy.heart(state, "p", "j", "s", 999))
        assertNull(WatchLiveHealthPolicy.heart(state.copy(onBody = false), "p", "j", "s", 2000))
        assertNull(WatchLiveHealthPolicy.heart(state.copy(capturing = false), "p", "j", "s", 2000))
        assertNull(WatchLiveHealthPolicy.heart(state.copy(armed = false), "p", "j", "s", 2000))
    }
}
