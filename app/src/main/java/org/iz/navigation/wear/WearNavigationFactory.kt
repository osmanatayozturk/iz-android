package org.iz.navigation.wear

import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.wearprotocol.WearNavigationSummary

/** Only the phone's existing route progress produces guidance; publishing never renews a GPS fix. */
internal object WearNavigationFactory {
    fun create(state: NavigationState, now: Long): WearNavigationSummary? {
        val session = state.sessionId?.takeIf { it.isNotBlank() } ?: return null
        if (!state.guidance && !state.loading && !state.arrived) return null
        val fixAt = state.fix?.recordedAt?.takeIf { it >= 0 && it <= now + 5_000 }
        val stale = state.gpsStale || fixAt == null || now - fixAt >= 30_000
        val progress = state.progress
        val offRoute = progress?.offRoute == true
        val arrived = state.arrived || progress?.arrived == true
        val validProgress = progress != null && listOf(progress.remainingSeconds,
            progress.remainingMeters, progress.nextManeuverDistanceMeters).all { it.isFinite() && it >= 0.0 }
        val usable = state.guidance && !state.loading && !stale && !offRoute && !arrived && validProgress
        val maneuver = if (usable) state.route?.maneuvers?.getOrNull(progress!!.maneuverIndex) else null
        val arrival = if (usable) {
            val millis = progress!!.remainingSeconds * 1_000
            if (millis < Long.MAX_VALUE.toDouble()) runCatching { Math.addExact(fixAt!!, millis.toLong()) }.getOrNull() else null
        } else null
        return WearNavigationSummary(
            sessionId = session, guidance = state.guidance, fixAt = fixAt, gpsStale = stale,
            loading = state.loading, offRoute = offRoute, arrived = arrived, simulation = state.simulation,
            maneuverType = maneuver?.type?.takeIf { it >= 0 },
            instruction = if (usable) wireText(maneuver?.instruction.orEmpty()) else "",
            nextManeuverMeters = progress?.nextManeuverDistanceMeters?.takeIf { usable },
            remainingMeters = progress?.remainingMeters?.takeIf { usable }, arrivalAt = arrival,
            destination = wireText(state.route?.stops?.lastOrNull()?.label.orEmpty())
        )
    }

    fun shouldPublish(recording: Boolean, guidance: Boolean, wasRecording: Boolean,
        wasGuiding: Boolean, resumed: Int): Boolean = recording || guidance || wasRecording || wasGuiding || resumed > 0

    private fun wireText(value: String): String {
        val result = StringBuilder()
        var bytes = 0
        val points = value.codePoints().iterator()
        while (points.hasNext()) {
            val point = points.nextInt()
            if (Character.isISOControl(point) || point in 0xD800..0xDFFF) continue
            val text = String(Character.toChars(point))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 240) break
            result.append(text)
            bytes += size
        }
        return result.toString().trim()
    }
}
