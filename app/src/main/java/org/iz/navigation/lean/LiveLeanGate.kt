package org.iz.navigation.lean

import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState

internal fun NavigationState.liveLeanSessionId(): String? {
    if (simulation || sessionTransport != Transport.MOTORCYCLE) return null
    if (recording) {
        val trip = journey ?: return null
        if (trip.transport != Transport.MOTORCYCLE || trip.status != JourneyStatus.CONFIRMED ||
            trip.endedAt != null || trip.interrupted) return null
    } else if (!guidance) return null
    return sessionId
}

internal fun NavigationState.hasLiveLeanFix() =
    fix != null && !gpsStale && !paused && plannedBreak == null && approximateOrigin == null

/** A recorder may attach to an existing navigation session with a different ID. */
internal fun NavigationState.liveLeanLocationSession(sourceId: String, fromRecording: Boolean): String? =
    liveLeanSessionId()?.takeIf { session ->
        hasLiveLeanFix() && if (fromRecording) recording && journey?.id == sourceId else !recording && session == sourceId
    }
