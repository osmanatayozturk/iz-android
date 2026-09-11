package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.WearSnapshot

/** Surface cache accepts old summaries, but never crosses a source/version boundary or regresses ordering. */
internal object WatchSurfaceAcceptance {
    fun accept(source: String, expectedSource: String, version: Int, expectedVersion: Int,
        previous: WearSnapshot?, value: WearSnapshot, now: Long): Boolean =
        source == expectedSource && version == expectedVersion && value.generatedAt <= now + 5000 &&
            value.generatedAt >= 0 && (previous == null || value.generatedAt > previous.generatedAt)
}
