package com.atay.iz.weather

internal object RideWeatherTiming {
    const val GPS_TTL_MS = 90_000L
    const val FORECAST_TTL_MS = 3_600_000L
    const val REFRESH_MS = 900_000L
    const val EVALUATE_MS = 60_000L
    private fun fresh(time: Long, now: Long, ttl: Long) =
        time >= 0 && now >= 0 && if (time > now) time - now <= 5_000L else now - time < ttl
    fun gpsFresh(recordedAt: Long, now: Long): Boolean = fresh(recordedAt, now, GPS_TTL_MS)
    fun forecastFresh(fetchedAt: Long?, now: Long): Boolean =
        fetchedAt != null && fresh(fetchedAt, now, FORECAST_TTL_MS)
}

/** Called only with accepted GPS; inaccurate, stale, repeated fixes add no evidence. */
internal class OffRouteGate {
    private var firstAt: Long? = null
    private var previousAt: Long? = null
    private var count = 0
    private var lastRerouteAt: Long? = null
    fun accept(distanceMeters: Double, accuracyMeters: Float, recordedAt: Long, now: Long): Boolean {
        if (!distanceMeters.isFinite() || distanceMeters <= 250.0 ||
            !accuracyMeters.isFinite() || accuracyMeters !in 0f..50f ||
            !RideWeatherTiming.gpsFresh(recordedAt, now)) {
            firstAt = null; count = 0
            return false
        }
        if (previousAt?.let { recordedAt <= it } == true) return false
        previousAt = recordedAt
        if (firstAt == null) firstAt = recordedAt
        count++
        if (count < 3 || recordedAt - firstAt!! < 15_000L) return false
        if (lastRerouteAt?.let { now - it < 60_000L } == true) return false
        lastRerouteAt = now
        firstAt = null; count = 0
        return true
    }
}

/** Continuous events are announced once; re-entry has a separate per-type cooldown. */
internal class WeatherAlertGate {
    private var previous: Set<String> = emptySet()
    private val announcedAt = mutableMapOf<String, Long>()
    fun events(hazards: Set<String>, now: Long, usable: Boolean): Set<String> {
        if (!usable || now < 0) return emptySet()
        val result = (hazards - previous).filterTo(mutableSetOf()) { type ->
            announcedAt[type]?.let { now - it >= 1_800_000L } ?: true
        }
        previous = hazards.toSet()
        result.forEach { announcedAt[it] = now }
        return result
    }
}
