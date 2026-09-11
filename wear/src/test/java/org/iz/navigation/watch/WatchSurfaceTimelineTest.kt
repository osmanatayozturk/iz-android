package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.WearSnapshot
import org.junit.Assert.*
import org.junit.Test

class WatchSurfaceTimelineTest {
    @Test fun liveHeartExpiryStillSchedulesRecordingExpiryAndTerminalPlaceholder() {
        val now = 1_000_000L
        val snapshot = WearSnapshot(now, journeyId = "j", recording = true, elapsedMillis = 120_000)
        val heartFrame = WatchSurfacePolicy.frame(snapshot, true, now).copy(validUntil = now + 30_000)
        val timeline = WatchSurfaceTimeline.segments(snapshot, true, now, heartFrame)
        assertEquals(3, timeline.size)
        assertEquals(now + 30_000, timeline[0].end)
        assertEquals(now + 300_000, timeline[1].end)
        assertEquals("İz’i aç", timeline[2].frame.compact)
        assertNull(timeline[2].end)
    }
}
