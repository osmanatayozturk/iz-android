package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*

/** Pure sensor policy. The runtime supplies phone-anchored measurement time and local elapsed lease time. */
internal class WatchHealthCapture {
    var sessionId: String? = null; private set
    var journeyId: String? = null; private set
    var worn: Boolean? = null; private set
    var sequence = 0L; private set
    private var startAt = 0L
    private var leaseUntil = 0L
    private var stepBaseline: Pair<Double, Long>? = null
    private var lastHeartAt = 0L
    fun attach(session: String, journey: String, createdAt: Long, expiresElapsed: Long, initialSequence: Long = 0) {
        if (sessionId != session) { sessionId = session; journeyId = journey; startAt = createdAt
            sequence = initialSequence; stepBaseline = null; lastHeartAt = 0; worn = null }
        leaseUntil = expiresElapsed
    }
    fun detach() { sessionId = null; journeyId = null; worn = null; startAt = 0; leaseUntil = 0; sequence = 0; stepBaseline = null; lastHeartAt = 0 }
    fun onBody(value: Boolean?) { if (worn != value) { stepBaseline = null; lastHeartAt = 0 }; worn = value }
    /** Listeners are registered anew after wear/resume; queued events from earlier registrations are excluded. */
    fun resumeAt(measuredAt: Long) { startAt = maxOf(startAt, measuredAt); stepBaseline = null; lastHeartAt = 0 }
    fun active(nowElapsed: Long): Boolean = sessionId != null && worn == true && nowElapsed < leaseUntil
    fun expired(nowElapsed: Long): Boolean = sessionId != null && nowElapsed >= leaseUntil
    fun heart(value: Double, accuracy: Int, measuredAt: Long, nowElapsed: Long): HealthReading? {
        if (!active(nowElapsed) || accuracy <= 0 || !value.isFinite() || value !in 1.0..300.0 ||
            measuredAt < startAt || measuredAt <= lastHeartAt || (lastHeartAt > 0 && measuredAt - lastHeartAt < 1_000)) return null
        lastHeartAt = measuredAt
        return HealthReading(++sequence, HealthReadingType.HEART_RATE, measuredAt, measuredAt, value)
    }
    fun steps(counter: Double, measuredAt: Long, nowElapsed: Long): HealthReading? {
        if (!active(nowElapsed) || !counter.isFinite() || counter < 0 || counter % 1.0 != 0.0 || measuredAt < startAt) return null
        val old = stepBaseline
        if (old != null && measuredAt <= old.second) return null
        stepBaseline = counter to measuredAt
        if (old == null || counter < old.first || counter - old.first > 1_000_000) return null
        return HealthReading(++sequence, HealthReadingType.STEPS, old.second, measuredAt, counter - old.first)
    }
}
