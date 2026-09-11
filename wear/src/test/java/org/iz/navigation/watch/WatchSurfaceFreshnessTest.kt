package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class WatchSurfaceFreshnessTest {
    private val now = 1_000_000L
    private fun nav(fix: Long = now) = WearNavigationSummary("nav", true, fix, false, false, false, false,
        false, 3, "Sağa dön", 150.0, 2200.0, now + 600_000, "Ev")
    private fun day() = WearDailySummary("1970-01-01", "UTC", 0, 86_400_000, now, now,
        WearDailyJourneyTotal(), WearDailyJourneyTotal(), null, 4200, null, null,
        WearDailyHealthStatus.AVAILABLE, WearDailyHealthStatus.UNAVAILABLE, WearDailyHealthStatus.UNAVAILABLE)
    @Test fun navigationDoesNotRequireRecordingAndWinsOverRecording() {
        val state = WearSnapshot(now, navigation = nav())
        assertEquals(WatchSurfaceRoute.NAVIGATION, WatchSurfacePolicy.frame(state, true, now).route)
        assertEquals(WatchSurfaceRoute.NAVIGATION,
            WatchSurfacePolicy.frame(state.copy(journeyId = "j", recording = true), true, now).route)
    }
    @Test fun republishingNeverFreshensOldGps() {
        val frame = WatchSurfacePolicy.frame(WearSnapshot(now, navigation = nav(now - 31_000)), true, now)
        assertFalse(frame.lines.any { it.contains("150 m") })
        assertEquals("İz’i aç", frame.compact)
    }
    @Test fun dailyCacheSurvivesSnapshotTtlButExpiresAtPhoneMidnight() {
        val state = WearSnapshot(now, daily = day())
        assertEquals("4200", WatchSurfacePolicy.frame(state, false, now + 100_000).compact)
        assertEquals("İz’i aç", WatchSurfacePolicy.frame(state, false, 86_400_000).compact)
    }
    @Test fun disconnectedRecordingNeverExtrapolates() {
        val state = WearSnapshot(now, journeyId = "j", recording = true, elapsedMillis = 120_000)
        assertEquals("2:00", WatchSurfacePolicy.frame(state, false, now + 60_000).compact)
    }
    @Test fun staleActiveNeverFallsBackToPlausibleDailyStepCount() {
        val state = WearSnapshot(now, journeyId = "j", recording = true, elapsedMillis = 120_000, daily = day())
        assertEquals("İz’i aç", WatchSurfacePolicy.frame(state, false, now + 300_001).compact)
    }
}
