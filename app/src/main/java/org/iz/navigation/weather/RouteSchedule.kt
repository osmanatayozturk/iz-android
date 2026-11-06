package org.iz.navigation.weather

/** Wall-clock planning is separate from the provider's driving-time geometry. */
data class RouteScheduleContext(
    val referenceAt: Long,
    val drivingElapsedSeconds: Double = 0.0,
    /** Null uses all future planned breaks; a map is the exact remaining session allocation. */
    val breakSecondsByStop: Map<Int, Double>? = null,
) {
    init {
        require(referenceAt >= 0 && drivingElapsedSeconds.isFinite() && drivingElapsedSeconds >= 0)
        require(breakSecondsByStop?.all { (index, seconds) -> index >= 1 && seconds.isFinite() && seconds in 0.0..14_400.0 } != false)
    }
}

data class RouteTripTiming(
    val drivingSeconds: Double,
    val breakSeconds: Double,
    val totalSeconds: Double,
    val arrivalAt: Long,
    val referenceAt: Long,
)

data class RouteStopTiming(val stopIndex: Int, val arrivalAt: Long, val departureAt: Long, val plannedBreakSeconds: Int)

object RouteSchedule {
    fun planned(route: PlannedRoute, departureAt: Long): RouteTripTiming = remaining(route, RouteScheduleContext(departureAt))

    fun remaining(route: PlannedRoute, context: RouteScheduleContext): RouteTripTiming {
        require(context.drivingElapsedSeconds <= route.durationSeconds + .001)
        val driving = (route.durationSeconds - context.drivingElapsedSeconds).coerceAtLeast(0.0)
        val breaks = remainingBreakSeconds(route, context).values.sum()
        return RouteTripTiming(driving, breaks, driving + breaks, at(context.referenceAt, driving + breaks), context.referenceAt)
    }

    fun stops(route: PlannedRoute, departureAt: Long): List<RouteStopTiming> {
        val times = stopTimes(route)
        var preceding = 0.0
        return route.stops.mapIndexed { index, stop ->
            val seconds = if (index in 1 until route.stops.lastIndex) stop.plannedBreakSeconds else 0
            val arrival = at(departureAt, times[index] + preceding)
            preceding += seconds
            RouteStopTiming(index, arrival, at(departureAt, times[index] + preceding), seconds)
        }
    }

    /** At an intermediate stop this is arrival, before its own break. The destination includes every break. */
    fun arrivalAt(route: PlannedRoute, context: RouteScheduleContext, drivingElapsedSeconds: Double): Long {
        require(drivingElapsedSeconds.isFinite() && drivingElapsedSeconds in 0.0..route.durationSeconds)
        val breaks = remainingBreakSeconds(route, context)
        val times = if (breaks.isEmpty()) emptyList() else stopTimes(route)
        val before = breaks.filterKeys { index ->
            times[index] < drivingElapsedSeconds || drivingElapsedSeconds == route.durationSeconds
        }.values.sum()
        return at(context.referenceAt, (drivingElapsedSeconds - context.drivingElapsedSeconds).coerceAtLeast(0.0) + before)
    }

    fun remainingBreakSeconds(route: PlannedRoute, context: RouteScheduleContext): Map<Int, Double> {
        val supplied = context.breakSecondsByStop
        if (supplied != null) {
            require(supplied.keys.all { it in 1 until route.stops.lastIndex })
            if (supplied.values.any { it > 0 }) stopTimes(route)
            return supplied.filterValues { it > 0 }.toMap()
        }
        val planned = route.stops.indices.filter { it in 1 until route.stops.lastIndex && route.stops[it].plannedBreakSeconds > 0 }
        if (planned.isEmpty()) return emptyMap()
        val times = stopTimes(route)
        return planned.filter { times[it] + .001 >= context.drivingElapsedSeconds }
            .associateWith { route.stops[it].plannedBreakSeconds.toDouble() }
    }

    private fun stopTimes(route: PlannedRoute): List<Double> {
        if (route.stopElapsedSeconds.size == route.stops.size) return route.stopElapsedSeconds
        if (route.stops.size == 2) return listOf(0.0, route.durationSeconds)
        require(route.stops.drop(1).dropLast(1).none { it.plannedBreakSeconds > 0 }) { "Mola için durak zamanları doğrulanamadı." }
        // Without a provider boundary, intermediate stop times must not be invented.
        throw IllegalArgumentException("Ara durak zamanları doğrulanamadı.")
    }

    private fun at(referenceAt: Long, seconds: Double): Long = RouteTravelTime.plannedArrival(referenceAt, seconds)
}
