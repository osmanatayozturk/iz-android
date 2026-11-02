package org.iz.navigation.data

/** A caller must first match each cache entry against the current journey metadata. */
data class PreparedJourneyComparison(val journey: Journey, val fingerprint: String, val stats: JourneyStats)
data class JourneyComparisonResult(
    val first: PreparedJourneyComparison, val second: PreparedJourneyComparison,
    val differentTransport: Boolean, val incompleteCoverage: Boolean,
    val routeIdentityVerified: Boolean = false,
)
object JourneyComparison {
    fun compare(first: PreparedJourneyComparison, second: PreparedJourneyComparison): JourneyComparisonResult? {
        if (first.journey.id == second.journey.id || !valid(first) || !valid(second)) return null
        return JourneyComparisonResult(first, second, first.journey.transport != second.journey.transport,
            first.stats.unobservedMillis > 0 || second.stats.unobservedMillis > 0 ||
                first.stats.observedMillis == 0L || second.stats.observedMillis == 0L)
    }
    private fun valid(input: PreparedJourneyComparison): Boolean {
        val j = input.journey; val s = input.stats; val end = j.endedAt ?: return false
        if (j.id.isBlank() || j.status != JourneyStatus.CONFIRMED || j.startedAt < 0 || end < j.startedAt ||
            !input.fingerprint.matches(Regex("[a-f0-9]{64}"))) return false
        if (!s.distanceMeters.isFinite() || s.distanceMeters < 0 || s.elapsedMillis != end - j.startedAt ||
            listOf(s.elapsedMillis, s.observedMillis, s.movingMillis, s.stoppedMillis, s.unobservedMillis).any { it < 0 } ||
            s.observedMillis > s.elapsedMillis || s.movingMillis > s.observedMillis ||
            s.stoppedMillis != s.observedMillis - s.movingMillis ||
            s.unobservedMillis != s.elapsedMillis - s.observedMillis) return false
        if (listOf(s.averageSpeedKmh, s.movingAverageSpeedKmh, s.maxSpeedKmh).any { it != null && (!it.isFinite() || it < 0) }) return false
        if (s.observedMillis == 0L && (s.distanceMeters != 0.0 || s.averageSpeedKmh != null || s.maxSpeedKmh != null)) return false
        if (s.movingMillis == 0L && s.movingAverageSpeedKmh != null) return false
        return true
    }
}