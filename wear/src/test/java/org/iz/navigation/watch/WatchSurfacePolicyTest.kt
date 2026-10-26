package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class WatchSurfacePolicyTest {
    private val now = 1_000_000L
    @Test fun confirmedRecordingShowsStatusWithoutLiveMeasurements() {
        val state = WearSnapshot(generatedAt = now, journeyId = "j", recording = true,
            elapsedMillis = 120_000, distanceMeters = 2500.0)
        val frame = WatchSurfacePolicy.frame(state, true, now + 10_000)
        assertEquals(WatchSurfaceRoute.RECORDING, frame.route)
        assertTrue(frame.lines.any { it.contains("Kayıt sürüyor") })
        assertTrue(frame.lines.any { it.contains("hazırlanacak") })
        assertFalse(frame.lines.any { it.contains("2:00") || it.contains("2,5 km") || it.contains("0,0 km") })
        assertEquals("Kayıt", frame.compact)
    }
    @Test fun oldRecordingIsExplicitlyLastStateWithoutMeasurements() {
        val state = WearSnapshot(generatedAt = now, journeyId = "j", recording = true, elapsedMillis = 120_000)
        assertEquals("Son durum", WatchSurfacePolicy.frame(state, false, now + 300_001).compact)
    }
    @Test fun candidateDoesNotShowRecordingOrHealth() {
        val state = WearSnapshot(generatedAt = now, journeyId = "candidate", recording = true, temporary = true,
            health = WearHealthSummary(latestHeartRateBpm = 99.0))
        val frame = WatchSurfacePolicy.frame(state, true, now)
        assertEquals(WatchSurfaceRoute.RECORDING, frame.route)
        assertTrue(frame.lines.any { it.contains("Algılama") })
        assertFalse(frame.lines.any { it.contains("99") })
    }
}
