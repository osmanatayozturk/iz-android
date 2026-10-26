package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.WearSnapshot
import org.junit.Assert.*
import org.junit.Test

class WatchSurfaceTimelineTest {
    @Test fun deferredRecordingNeverSchedulesFabricatedMetricTimeline() {
        val now = 1_000_000L
        val snapshot = WearSnapshot(now, journeyId = "j", recording = true, statsDeferred = true)
        val frame = WatchSurfacePolicy.frame(snapshot, true, now)
        val timeline = WatchSurfaceTimeline.segments(snapshot, true, now, frame)
        assertEquals(1, timeline.size)
        assertNull(timeline.single().end)
        assertEquals("Kayıt", timeline.single().frame.compact)
        assertTrue(timeline.single().frame.lines.any { it.contains("hazırlanacak") })
        assertFalse(timeline.single().frame.lines.any { it.contains("0:00") || it.contains("0,0 km") })
    }
}
