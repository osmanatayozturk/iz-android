package org.iz.navigation.weather

/** Occurrence indexes preserve equal coordinates, equal values and each occurrence's planned break. */
internal fun heuristicStopOrder(stops: List<RouteStop>, checkActive: () -> Unit = {}): List<Int> {
    RouteStopLimits.requirePlanned(stops.size)
    val distances = Array(stops.size) { from -> DoubleArray(stops.size) { to ->
        checkActive()
        WeatherEngine.distanceMeters(stops[from].coordinate, stops[to].coordinate)
    } }
    val remaining = (1 until stops.lastIndex).toMutableList()
    val order = mutableListOf(0)
    while (remaining.isNotEmpty()) {
        checkActive()
        val last = order.last()
        val next = remaining.minWith(compareBy<Int> { distances[last][it] }.thenBy { it })
        order += next
        remaining.remove(next)
    }
    order += stops.lastIndex
    repeat(20) {
        var improved = false
        for (from in 1 until order.lastIndex) {
            for (until in from + 1 until order.lastIndex) {
                checkActive()
                val before = distances[order[from - 1]][order[from]] + distances[order[until]][order[until + 1]]
                val after = distances[order[from - 1]][order[until]] + distances[order[from]][order[until + 1]]
                if (after + 0.000001 < before) {
                    order.subList(from, until + 1).reverse()
                    improved = true
                }
            }
        }
        if (!improved) return order
    }
    return order
}
