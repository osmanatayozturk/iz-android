package com.atay.iz.weather

import kotlin.math.ceil

internal data class RouteTravelTiming(
    val remainingSeconds: Double,
    val arrivalAt: Long?,
    val updatedAt: Long?,
    val gpsStale: Boolean,
)

/** Route costing supplies the remaining duration; no learned speed or traffic data is implied. */
internal object RouteTravelTime {
    fun plannedArrival(departureAt: Long, durationSeconds: Double): Long {
        require(departureAt >= 0 && durationSeconds.isFinite() && durationSeconds >= 0)
        val millis = ceil(durationSeconds * 1_000).toLong().coerceAtMost(Long.MAX_VALUE - departureAt)
        return departureAt + millis
    }

    fun advance(previous: RouteTravelTiming?, remainingSeconds: Double, lastFixAt: Long?, now: Long): RouteTravelTiming {
        require(remainingSeconds.isFinite() && remainingSeconds >= 0)
        val fresh = lastFixAt?.let { RideWeatherTiming.gpsFresh(it, now) } ?: false
        if (!fresh) return previous?.copy(gpsStale = true)
            ?: RouteTravelTiming(remainingSeconds, null, null, gpsStale = true)
        return RouteTravelTiming(remainingSeconds, plannedArrival(now, remainingSeconds), now, gpsStale = false)
    }
}
