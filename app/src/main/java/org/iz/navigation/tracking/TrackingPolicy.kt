package org.iz.navigation.tracking

import org.iz.navigation.data.Transport
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import kotlin.math.*

/** Platform-independent rules; distances are between observations, never road-snapped. */
object TrackingPolicy {
    const val AUTOMATIC_WINDOW_MS = 15L * 60 * 1000
    fun automaticCandidateDeadline(journey: Journey): Long? =
        if (journey.status == JourneyStatus.TEMPORARY && journey.endedAt == null) journey.startedAt + AUTOMATIC_WINDOW_MS else null

    /** The deadline is exclusive: a fix at exactly 15:00 belongs to neither the old route nor its distance. */
    fun automaticCandidateDecision(journey: Journey, points: List<TrackPoint>, now: Long): AutomaticCandidateDecision {
        val deadline = automaticCandidateDeadline(journey) ?: return AutomaticCandidateDecision.KEEP
        // Recover a threshold already measured before a service interruption without losing the original start.
        if ((automaticCandidateProgress(journey, points) ?: 0.0) >= AutomaticJourneyDistance.REQUIRED_METERS) return AutomaticCandidateDecision.CONFIRM
        return if (now >= deadline) AutomaticCandidateDecision.RESET else AutomaticCandidateDecision.KEEP
    }

    /** Qualification progress, distinct from historical route statistics. Null means no open candidate. */
    fun automaticCandidateProgress(journey: Journey, points: List<TrackPoint>): Double? {
        val deadline = automaticCandidateDeadline(journey) ?: return null
        val inWindow = points.filter {
            it.journeyId == journey.id && it.recordedAt >= journey.startedAt && it.recordedAt < deadline
        }
        return AutomaticJourneyDistance(inWindow).meters
    }
    const val TEMPORARY_LIFETIME_MS = 24L * 60 * 60 * 1000
    const val MAX_ACCURACY_METRES = 50f
    const val GAP_MS = 60_000L
    const val MAX_FIX_AGE_MS = 30_000L
    fun expired(expiresAt: Long?, now: Long): Boolean = expiresAt != null && now >= expiresAt
    fun valid(sample: PositionSample, now: Long): Boolean =
        sample.latitude.isFinite() && sample.latitude in -90.0..90.0 &&
        sample.longitude.isFinite() && sample.longitude in -180.0..180.0 &&
        sample.accuracy.isFinite() && sample.accuracy in 0f..MAX_ACCURACY_METRES &&
        sample.time <= now + 5_000 && now - sample.time <= MAX_FIX_AGE_MS

    fun distance(a: PositionSample, b: PositionSample): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun sameDetectedMode(current: Transport, suggestion: Transport): Boolean =
        current == suggestion || (suggestion == Transport.CAR && current in listOf(Transport.MOTORCYCLE, Transport.PASSENGER))
}

enum class AutomaticCandidateDecision { KEEP, CONFIRM, RESET }

data class PositionSample(
    val latitude: Double,
    val longitude: Double,
    val time: Long,
    val accuracy: Float,
    val speed: Float? = null,
    val altitude: Double? = null,
)

data class AcceptedPosition(val sample: PositionSample, val breakBefore: Boolean)

/** Rejected/missing fixes break the route instead of drawing a fictional connecting line. */
class PositionFilter {
    private var previous: PositionSample? = null
    private var gap = true

    fun accept(sample: PositionSample, now: Long): AcceptedPosition? {
        val prior = previous
        // A stale duplicate is not evidence of a new missing interval.
        if (prior != null && sample.time <= prior.time) return null
        if (!TrackingPolicy.valid(sample, now)) { gap = true; return null }
        if (prior != null) {
            val seconds = (sample.time - prior.time) / 1000.0
            if (TrackingPolicy.distance(prior, sample) / seconds > 100.0) {
                gap = true
                return null
            }
        }
        val result = AcceptedPosition(sample, gap || prior == null || sample.time - prior.time > TrackingPolicy.GAP_MS)
        previous = sample
        gap = false
        return result
    }
}

/** Only fresh, reasonably accurate stationary fixes can form a dwell. */
class DwellDetector {
    private var anchor: PositionSample? = null
    private var latestAt = 0L
    private var notified = false

    fun observe(sample: PositionSample) {
        if (sample.time <= latestAt) return
        val old = anchor
        val observationGap = latestAt != 0L && sample.time - latestAt > TrackingPolicy.GAP_MS
        val moving = (sample.speed ?: 0f) > 1.5f
        val displaced = old != null && TrackingPolicy.distance(old, sample) > max(35.0, (old.accuracy + sample.accuracy).toDouble())
        if (old == null || observationGap || moving || displaced) {
            anchor = if (moving) null else sample
            notified = false
        }
        latestAt = sample.time
    }

    fun shouldPrompt(now: Long, stopMinutes: Int): Boolean {
        val start = anchor ?: return false
        if (notified || now - latestAt !in 0..TrackingPolicy.MAX_FIX_AGE_MS) return false
        if (now - start.time < stopMinutes.coerceIn(1, 60) * 60_000L) return false
        notified = true
        return true
    }
}
