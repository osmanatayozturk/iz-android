package org.iz.navigation.power

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStats
import org.iz.navigation.data.JourneyStatus

/** Only immutable completed GPS-derived facts; deliberately excludes health/watch summaries. */
internal data class PreparedRecordedJourney(val journey: Journey, val fingerprint: String, val stats: JourneyStats)

/**
 * A new active journey may dirty the global work queue without changing completed GPS rows.
 * DiaryRepository.addPoint refuses ended journeys; restore/delete/end edits synchronously purge
 * their prepared entries before committing. Keep existing historical recorded facts readable,
 * while the worker remains blocked by ALL active-session and battery gates. General health
 * readers continue to use AnalysisState.ready and its stricter global pending policy.
 */
internal fun AnalysisState.preparedRecorded(journey: Journey): PreparedRecordedJourney? {
    if (journey.status != JourneyStatus.CONFIRMED || journey.endedAt == null) return null
    val entry = entries[journey.id]?.takeIf { it.matches(journey) && it.fingerprint.matches(Regex("[a-f0-9]{64}")) } ?: return null
    return PreparedRecordedJourney(journey, entry.fingerprint, entry.stats)
}