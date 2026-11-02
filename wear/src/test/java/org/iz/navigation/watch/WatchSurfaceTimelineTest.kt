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
        assertEquals(2, timeline.size)
        assertEquals(now + 30_000, timeline.first().end)
        assertNull(timeline.last().end)
        assertEquals("Kayıt", timeline.first().frame.compact)
        assertTrue(timeline.first().frame.lines.any { it.contains("hazırlanacak") })
        assertFalse(timeline.first().frame.lines.any { it.contains("0:00") || it.contains("0,0 km") })
        assertEquals("Telefon bilgisi güncel değil", timeline.last().frame.lines.first())
        assertEquals("Son durum", timeline.last().frame.compact)
        assertFalse(timeline.last().frame.lines.any { it.contains("0:00") || it.contains("0,0 km") })
    }
}
