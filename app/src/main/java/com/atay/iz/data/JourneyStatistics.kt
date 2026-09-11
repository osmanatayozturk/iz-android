package com.atay.iz.data

/**
 * Recorded route statistics, without interpolating missing GPS fixes.
 *
 * [elapsedMillis] includes the whole journey; [observedMillis] only includes
 * continuous, usable GPS intervals. Missing time is neither movement nor a stop.
 * Speeds are in km/h and null when their denominator has not been observed.
 */
data class JourneyStats(
    val distanceMeters: Double,
    val elapsedMillis: Long,
    val observedMillis: Long,
    val movingMillis: Long,
    val stoppedMillis: Long,
    val unobservedMillis: Long,
    /** Recorded distance divided by the whole journey duration, including stops and gaps. */
    val averageSpeedKmh: Double?,
    /** Recorded distance in moving intervals divided by the duration of those intervals. */
    val movingAverageSpeedKmh: Double?,
    /** Fastest usable GPS interval; this is a sampled interval speed, not an instantaneous peak. */
    val maxSpeedKmh: Double?,
)

/** Pure, mode-independent calculations shared by active and completed journey screens. */
object JourneyStatistics {
    // Match DiaryRules' coordinate-jump ceiling; an impossible sensor speed is not motion evidence.
    private const val MAX_REPORTED_SPEED_METERS_PER_SECOND = 100f

    fun calculate(
        journey: Journey,
        points: List<TrackPoint>,
        now: Long = System.currentTimeMillis(),
    ): JourneyStats {
        val end = (journey.endedAt ?: now).coerceAtLeast(journey.startedAt)
        val elapsedMillis = (end - journey.startedAt).coerceAtLeast(0)
        val route = points.asSequence()
            .filter { it.journeyId == journey.id && it.recordedAt in journey.startedAt..end }
            // Keep invalid fixes in place: dropping them could join their valid neighbours.
            .map { point ->
                if (point.speed != null && point.speed.isFinite() && point.speed > MAX_REPORTED_SPEED_METERS_PER_SECOND) {
                    point.copy(speed = null)
                } else point
            }
            .sortedBy { it.recordedAt }
            .toList()

        var distanceMeters = 0.0
        var movingDistanceMeters = 0.0
        var observedMillis = 0L
        var stoppedMillis = 0L
        var maxSpeedKmh = 0.0

        for (index in 1 until route.size) {
            val previous = route[index - 1]
            val current = route[index]
            if (!DiaryRules.connects(previous, current)) continue

            val intervalMillis = current.recordedAt - previous.recordedAt
            val interval = listOf(previous, current)
            val intervalDistance = DiaryRules.distanceMeters(interval)
            val intervalStoppedMillis = DiaryRules.stoppedDurationMillis(interval)
            distanceMeters += intervalDistance
            observedMillis += intervalMillis
            stoppedMillis += intervalStoppedMillis
            if (intervalStoppedMillis == 0L) movingDistanceMeters += intervalDistance
            maxSpeedKmh = maxOf(maxSpeedKmh, intervalDistance * 3_600.0 / intervalMillis)
        }

        val movingMillis = observedMillis - stoppedMillis
        return JourneyStats(
            distanceMeters = distanceMeters,
            elapsedMillis = elapsedMillis,
            observedMillis = observedMillis,
            movingMillis = movingMillis,
            stoppedMillis = stoppedMillis,
            unobservedMillis = (elapsedMillis - observedMillis).coerceAtLeast(0),
            averageSpeedKmh = if (observedMillis > 0 && elapsedMillis > 0) distanceMeters * 3_600.0 / elapsedMillis else null,
            movingAverageSpeedKmh = if (movingMillis > 0) movingDistanceMeters * 3_600.0 / movingMillis else null,
            maxSpeedKmh = if (observedMillis > 0) maxSpeedKmh else null,
        )
    }
}
