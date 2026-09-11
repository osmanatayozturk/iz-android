package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class WatchSurfacePolicyTest {
    private val now = 1_000_000L
    @Test fun confirmedRecordingShowsMeasuredDurationAndDistance() {
        val state = WearSnapshot(generatedAt = now, journeyId = "j", recording = true,
            elapsedMillis = 120_000, distanceMeters = 2500.0)
        val frame = WatchSurfacePolicy.frame(state, true, now + 10_000)
        assertEquals(WatchSurfaceRoute.RECORDING, frame.route)
        assertTrue(frame.lines.any { it.contains("2:00") })
        assertTrue(frame.lines.any { it.contains("2,5 km") })
    }
    @Test fun activeComplicationExpiresAfterFiveMinutes() {
        val state = WearSnapshot(generatedAt = now, journeyId = "j", recording = true, elapsedMillis = 120_000)
        assertEquals("İz’i aç", WatchSurfacePolicy.frame(state, false, now + 300_001).compact)
    }
    @Test fun candidateDoesNotShowRecordingOrHealth() {
        val state = WearSnapshot(generatedAt = now, journeyId = "candidate", recording = true, temporary = true,
            health = WearHealthSummary(latestHeartRateBpm = 99.0))
        val frame = WatchSurfacePolicy.frame(state, true, now)
        assertEquals(WatchSurfaceRoute.DAILY, frame.route)
        assertTrue(frame.lines.any { it.contains("Algılama") })
        assertFalse(frame.lines.any { it.contains("99") })
    }
}
