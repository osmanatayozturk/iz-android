package org.iz.navigation.weather

import org.iz.navigation.data.Transport

/** A bounded provider exclusion area, not a promise to identify one directed road edge. */
data class RouteAvoidArea(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(listOf(south, west, north, east).all { it.isFinite() })
        require(south in -80.0..80.0 && north in -80.0..80.0 && south < north)
        require(west in -180.0..180.0 && east in -180.0..180.0 && west < east)
        require(north - south <= 0.1 && east - west <= 0.2)
    }
}
data class RouteModificationRequest(
    val stops: List<RouteStop>, val departureAt: Long, val transport: Transport,
    val travelSpeedKmh: Double?, val preferences: RoutePreferences,
    val avoidAreas: List<RouteAvoidArea> = emptyList(),
) {
    init {
        RouteStopLimits.requireEffective(stops.size)
        require(departureAt >= 0 && avoidAreas.size <= 3)
        routeTravelSpeedKmh(transport, travelSpeedKmh)
    }
}
/** Implementations must preserve exclusions through fallback or reject the request. */
interface RouteModificationPlanner {
    suspend fun planModified(request: RouteModificationRequest): PlannedRoute
}
sealed interface RouteEditAction {
    data class AvoidManeuver(val index: Int) : RouteEditAction
    data class SkipStop(val index: Int) : RouteEditAction
    data class AddStop(val stop: RouteStop) : RouteEditAction
}
object RouteEditPolicy {
    fun prepare(route: PlannedRoute, elapsedSeconds: Double, current: WeatherCoordinate,
        departureAt: Long, action: RouteEditAction, remainingStopIndices: List<Int>? = null): RouteModificationRequest {
        require(elapsedSeconds.isFinite() && elapsedSeconds in 0.0..route.durationSeconds)
        val last = route.stops.lastIndex
        val remaining = if (remainingStopIndices != null) {
            require(remainingStopIndices == remainingStopIndices.distinct().sorted() &&
                remainingStopIndices.all { it in 1..last } && remainingStopIndices.lastOrNull() == last)
            remainingStopIndices.toMutableList()
        } else {
            require(last == 1 || route.stopElapsedSeconds.size == route.stops.size &&
                route.stopElapsedSeconds.zipWithNext().all { (a, b) -> a < b }) {
                "Ara durak ilerlemesi doğrulanamadı. Mevcut rota korundu."
            }
            (1..last).filter { it == last || route.stopElapsedSeconds[it] > elapsedSeconds + 0.001 }.toMutableList()
        }
        val areas = route.avoidAreas.toMutableList()
        var added: RouteStop? = null
        when (action) {
            is RouteEditAction.SkipStop -> {
                require(action.index in 1 until last && action.index in remaining) { "Bu ara durak atlanamaz." }
                remaining.remove(action.index)
            }
            is RouteEditAction.AddStop -> {
                RouteStopLimits.requireEffective(remaining.size + 2)
                added = action.stop
            }
            is RouteEditAction.AvoidManeuver -> {
                val maneuver = route.maneuvers.getOrNull(action.index) ?: throw IllegalArgumentException("Rota bölümü bulunamadı.")
                require(maneuver.endElapsedSeconds > elapsedSeconds &&
                    maneuver.beginShapeIndex in route.vertices.indices && maneuver.endShapeIndex in route.vertices.indices &&
                    maneuver.beginShapeIndex < maneuver.endShapeIndex) { "Bu rota bölümü artık seçilemez." }
                val selected = mutableListOf<WeatherCoordinate>()
                var length = 0.0
                for (index in maneuver.beginShapeIndex..maneuver.endShapeIndex) {
                    val vertex = route.vertices[index]
                    if (vertex.elapsedSeconds <= elapsedSeconds || WeatherEngine.distanceMeters(current, vertex.coordinate) < 80) continue
                    selected.lastOrNull()?.let { length += WeatherEngine.distanceMeters(it, vertex.coordinate) }
                    selected += vertex.coordinate
                    if (length >= 1000) break
                }
                require(selected.size >= 2) { "Hariç tutulacak yeterli ileri rota bölümü yok." }
                val latitude = selected.map { it.latitude }.average()
                val padLat = 25.0 / 111_195.0
                val padLon = padLat / kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(0.1)
                val area = RouteAvoidArea(selected.minOf { it.latitude } - padLat,
                    selected.minOf { it.longitude } - padLon, selected.maxOf { it.latitude } + padLat,
                    selected.maxOf { it.longitude } + padLon)
                if (area !in areas) { require(areas.size < 3) { "En fazla üç bölüm hariç tutulabilir." }; areas += area }
            }
        }
        val stops = listOf(RouteStop("Mevcut konum", current)) + listOfNotNull(added) + remaining.map { route.stops[it] }
        return RouteModificationRequest(stops, departureAt, route.transport, route.travelSpeedKmh, route.preferences, areas)
    }
}
