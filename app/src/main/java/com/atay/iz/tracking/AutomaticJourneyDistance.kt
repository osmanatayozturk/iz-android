package com.atay.iz.tracking

import com.atay.iz.data.DiaryRules
import com.atay.iz.data.TrackPoint
import kotlin.math.max

/** Distance survives pauses and GPS gaps, but never counts the unobserved gap itself. */
internal class AutomaticJourneyDistance(points: List<TrackPoint> = emptyList()) {
    var meters: Double = 0.0
        private set
    private var previous: TrackPoint? = null
    private var anchor: TrackPoint? = null
    val qualified: Boolean get() = meters >= REQUIRED_METERS

    init { points.sortedBy { it.recordedAt }.forEach(::observe) }

    fun observe(point: TrackPoint) {
        val last = previous
        if (last != null && point.recordedAt <= last.recordedAt) return
        if (!DiaryRules.isUsablePoint(point) || point.accuracy > TrackingPolicy.MAX_ACCURACY_METRES) {
            previous = point
            anchor = null
            return
        }
        if (last == null || !DiaryRules.connects(last, point) ||
            point.recordedAt - last.recordedAt > TrackingPolicy.GAP_MS) {
            previous = point
            anchor = point
            return
        }
        previous = point
        val reference = anchor ?: run { anchor = point; return }
        val displacement = DiaryRules.metersBetween(reference, point)
        // Keep short walking fixes until movement clears both accuracy radii. Advancing the
        // anchor on every fix would discard them; summing them would count stationary jitter.
        val uncertainty = max(3.0, reference.accuracy.toDouble() + point.accuracy)
        if (displacement - uncertainty > 0.001) {
            meters += displacement
            anchor = point
        }
    }

    companion object { const val REQUIRED_METERS = 500.0 }
}
