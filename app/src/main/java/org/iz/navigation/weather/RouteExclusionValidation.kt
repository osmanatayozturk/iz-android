package org.iz.navigation.weather

/**
 * Checks the returned polyline, including closed area boundaries, before its owner publishes it.
 * Edges use linear latitude/longitude interpolation with the shortest longitude displacement;
 * an exactly 180-degree tie retains the provider's signed displacement. This checks geometry,
 * not road-edge identity or the accuracy of the provider's coordinates.
 *
 * The original route is returned unchanged. Geometry rejection and owner cancellation propagate
 * to the caller, which must keep its previous route until validation succeeds.
 */
fun requireAvoidedAreas(
    route: PlannedRoute,
    areas: List<RouteAvoidArea>,
    checkActive: () -> Unit = {},
): PlannedRoute {
    checkActive()
    if (areas.isEmpty()) return route

    // The first degenerate edge also checks a provider returning just one geometry vertex.
    var previous = route.vertices.first().coordinate
    for (vertex in route.vertices) {
        checkActive()
        val current = vertex.coordinate
        val rawDelta = current.longitude - previous.longitude
        val delta = when {
            rawDelta > 180.0 -> rawDelta - 360.0
            rawDelta < -180.0 -> rawDelta + 360.0
            else -> rawDelta
        }
        for (area in areas) {
            checkActive()
            // Periodic copies make +180/-180 the same closed boundary. With bounded input
            // longitudes and a shortest edge, these three copies cover every possible contact.
            for (copy in -1..1) {
                require(!intersectsClosedArea(previous, delta, current.latitude, area, copy * 360.0)) {
                    "Returned route touches an excluded area."
                }
            }
        }
        previous = current
    }
    checkActive()
    return route
}

/** Intersects the segment's [0,1] parameter interval with each inclusive coordinate slab. */
private fun intersectsClosedArea(
    start: WeatherCoordinate,
    longitudeDelta: Double,
    endLatitude: Double,
    area: RouteAvoidArea,
    longitudeShift: Double,
): Boolean {
    var enter = 0.0
    var leave = 1.0
    val west = area.west + longitudeShift
    val east = area.east + longitudeShift
    if (longitudeDelta == 0.0) {
        if (start.longitude < west || start.longitude > east) return false
    } else {
        val westTime = (west - start.longitude) / longitudeDelta
        val eastTime = (east - start.longitude) / longitudeDelta
        enter = maxOf(enter, minOf(westTime, eastTime))
        leave = minOf(leave, maxOf(westTime, eastTime))
        if (enter > leave) return false
    }

    val latitudeDelta = endLatitude - start.latitude
    if (latitudeDelta == 0.0) {
        if (start.latitude < area.south || start.latitude > area.north) return false
    } else {
        val southTime = (area.south - start.latitude) / latitudeDelta
        val northTime = (area.north - start.latitude) / latitudeDelta
        enter = maxOf(enter, minOf(southTime, northTime))
        leave = minOf(leave, maxOf(southTime, northTime))
        if (enter > leave) return false
    }
    return true
}
