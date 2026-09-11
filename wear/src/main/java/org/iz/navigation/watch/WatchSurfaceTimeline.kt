package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.WearSnapshot

internal data class WatchSurfaceSegment(val frame: WatchSurfaceFrame, val start: Long, val end: Long?)
internal object WatchSurfaceTimeline {
    fun segments(snapshot: WearSnapshot?, connected: Boolean, now: Long, first: WatchSurfaceFrame): List<WatchSurfaceSegment> {
        val result = mutableListOf<WatchSurfaceSegment>()
        var start = now
        var frame = first
        // At most: current live heart -> measured recording -> expired. Daily and GPS need two entries.
        repeat(3) {
            val end = frame.validUntil?.takeIf { it > start }
            result += WatchSurfaceSegment(frame, start, end)
            if (end == null) return result
            start = end
            frame = WatchSurfacePolicy.frame(snapshot, false, start)
        }
        return result
    }
}
