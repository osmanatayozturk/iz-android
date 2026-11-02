package org.iz.navigation.integration

import java.util.Locale
import kotlin.math.*
import org.json.JSONObject
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine

enum class RouteCorridorKind { UNKNOWN, FUEL, REST }
data class RouteCorridorPlace(val place: SelectedOsmPlace, val offsetMeters: Double, val forwardMeters: Double,
    val kind: RouteCorridorKind = RouteCorridorKind.UNKNOWN)
internal data class ParsedRouteCorridorPlace(val place: SelectedOsmPlace, val kind: RouteCorridorKind)
internal data class RouteCorridorSlice(val routeId: String, val points: List<WeatherCoordinate>,
    val original: List<WeatherCoordinate>, val context: List<WeatherCoordinate>,
    val startMeters: Double, val contextStartMeters: Double, val distanceMeters: Double)

/** Explicit forward search only. Offset is geodesic proximity, never road-access/detour proof. */
internal object RouteCorridorPlaces {
    fun slice(route: PlannedRoute, elapsedSeconds: Double): RouteCorridorSlice? {
        require(elapsedSeconds.isFinite() && elapsedSeconds in 0.0..route.durationSeconds)
        if (elapsedSeconds == route.durationSeconds || route.vertices.size < 2) return null
        require(route.vertices.size <= 100_000)
        val vertices = route.vertices.toList()
        require(vertices.all { it.coordinate.latitude in -80.0..80.0 })
        require(vertices.zipWithNext().all { abs(it.first.coordinate.longitude - it.second.coordinate.longitude) < 180 }) {
            "Bu meridyen geçişinde yakın duraklar sorgulanamıyor."
        }
        val distances = DoubleArray(vertices.size)
        for (index in 1 until vertices.size) distances[index] = distances[index - 1] +
            WeatherEngine.distanceMeters(vertices[index - 1].coordinate, vertices[index].coordinate)
        val equal = vertices.withIndex().filter { abs(it.value.elapsedSeconds - elapsedSeconds) < 1e-9 }
        require(equal.size < 2 || equal.all { it.value.coordinate == equal.first().value.coordinate }) {
            "Rota ilerlemesi doğrulanamadı."
        }
        val end = vertices.indexOfFirst { it.elapsedSeconds >= elapsedSeconds }
        if (end < 0) return null
        val cursor = if (end == 0 || vertices[end].elapsedSeconds == elapsedSeconds) distances[end] else {
            val before = vertices[end - 1]
            val duration = vertices[end].elapsedSeconds - before.elapsedSeconds
            require(duration > 0)
            distances[end - 1] + (distances[end] - distances[end - 1]) *
                ((elapsedSeconds - before.elapsedSeconds) / duration).coerceIn(0.0, 1.0)
        }
        val finish = minOf(cursor + 10_000, distances.last())
        val length = finish - cursor
        if (length < 1) return null
        fun at(distance: Double): WeatherCoordinate {
            if (distance <= 0) return vertices.first().coordinate
            if (distance >= distances.last()) return vertices.last().coordinate
            var low = 1; var high = distances.lastIndex
            while (low < high) { val middle = (low + high) / 2; if (distances[middle] < distance) low = middle + 1 else high = middle }
            val a = vertices[low - 1].coordinate; val b = vertices[low].coordinate
            val span = distances[low] - distances[low - 1]
            val ratio = if (span <= 0) 0.0 else ((distance - distances[low - 1]) / span).coerceIn(0.0, 1.0)
            return WeatherCoordinate(a.latitude + (b.latitude - a.latitude) * ratio, a.longitude + (b.longitude - a.longitude) * ratio)
        }
        fun distinctNeighbours(points: List<WeatherCoordinate>) =
            points.filterIndexed { index, point -> index == 0 || point != points[index - 1] }
        fun cut(start: Double, stop: Double): List<WeatherCoordinate> = distinctNeighbours(buildList {
            add(at(start))
            vertices.forEachIndexed { index, vertex -> if (distances[index] > start && distances[index] < stop) add(vertex.coordinate) }
            add(at(stop))
        })
        // 31 equal arclength intervals keep any original point within <=162m of an endpoint.
        // Query radius750m includes a500m real-route corridor; projection below removes extras.
        val points = distinctNeighbours((0 until 32).map { at(cursor + length * it / 31.0) })
        require(points.size >= 2) { "Rota ilerlemesi doğrulanamadı." }
        val contextStart = maxOf(0.0, cursor - 1000)
        return RouteCorridorSlice(route.id, points, cut(cursor, finish),
            cut(contextStart, minOf(distances.last(), finish + 1000)), cursor, contextStart, length)
    }

    fun query(slice: RouteCorridorSlice): String {
        require(slice.points.size in 2..32 && slice.distanceMeters in 1.0..10_000.01)
        val line = slice.points.joinToString(",") { String.format(Locale.US, "%.6f,%.6f", it.latitude, it.longitude) }
        return "[out:json][timeout:15][maxsize:16777216];(" +
            "nwr[amenity=fuel](around:750,$line);" +
            "nwr[highway~\"^(services|rest_area)$\"](around:750,$line);" +
            ");out body center qt 200;"
    }

    fun project(slice: RouteCorridorSlice, places: List<SelectedOsmPlace>,
        kinds: Map<OsmRef, RouteCorridorKind> = emptyMap()): List<RouteCorridorPlace> {
        if (slice.context.size < 2) return emptyList()
        require(places.size <= 200)
        val distances = DoubleArray(slice.context.size)
        for (index in 1 until distances.size) distances[index] = distances[index - 1] +
            WeatherEngine.distanceMeters(slice.context[index - 1], slice.context[index])
        return places.distinctBy { it.osmRef }.mapNotNull { place ->
            val ref = place.osmRef ?: return@mapNotNull null
            if (ref.id <= 0 || !place.latitude.isFinite() || !place.longitude.isFinite() ||
                place.latitude !in -90.0..90.0 || place.longitude !in -180.0..180.0) return@mapNotNull null
            val point = WeatherCoordinate(place.latitude, place.longitude)
            var bestDistance = Double.POSITIVE_INFINITY
            var bestAlong = 0.0
            for (index in 0 until slice.context.lastIndex) {
                val a = slice.context[index]; val b = slice.context[index + 1]
                val longitudeScale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(0.01)
                val dx = (b.longitude - a.longitude) * longitudeScale
                val dy = b.latitude - a.latitude
                val px = (point.longitude - a.longitude) * longitudeScale
                val py = point.latitude - a.latitude
                val squared = dx * dx + dy * dy
                val fraction = if (squared <= 1e-20) 0.0 else ((px * dx + py * dy) / squared).coerceIn(0.0, 1.0)
                val projected = WeatherCoordinate(a.latitude + (b.latitude - a.latitude) * fraction,
                    a.longitude + (b.longitude - a.longitude) * fraction)
                val offset = WeatherEngine.distanceMeters(point, projected)
                val along = slice.contextStartMeters + distances[index] +
                    (distances[index + 1] - distances[index]) * fraction - slice.startMeters
                if (offset < bestDistance - 0.01 || abs(offset - bestDistance) <= 0.01 && along < bestAlong) {
                    bestDistance = offset; bestAlong = along
                }
            }
            if (bestDistance > 500 || bestAlong < -0.01 || bestAlong > slice.distanceMeters + 0.01) null
            else RouteCorridorPlace(place, bestDistance, bestAlong.coerceIn(0.0, slice.distanceMeters), kinds[ref] ?: RouteCorridorKind.UNKNOWN)
        }.sortedWith(compareBy<RouteCorridorPlace> { it.forwardMeters }.thenBy { it.offsetMeters }).take(100)
    }

    fun parse(json: String): List<SelectedOsmPlace> = parseTyped(json).map { it.place }

    fun parseTyped(json: String): List<ParsedRouteCorridorPlace> {
        require(json.toByteArray(Charsets.UTF_8).size <= 512_000)
        val root = JSONObject(json)
        require(root.optString("remark").isBlank()) { "Harita sorgusu tamamlanamadı." }
        val array = requireNotNull(root.optJSONArray("elements")) { "Harita yanıtı doğrulanamadı." }
        require(array.length() <= 200)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val type = when (item.optString("type")) { "node" -> OsmType.NODE; "way" -> OsmType.WAY; "relation" -> OsmType.RELATION; else -> return@mapNotNull null }
            val id = item.optLong("id", 0); if (id <= 0) return@mapNotNull null
            val tags = item.optJSONObject("tags") ?: return@mapNotNull null
            val kind = when {
                tags.optString("amenity") == "fuel" -> RouteCorridorKind.FUEL
                tags.optString("highway") in setOf("services", "rest_area") -> RouteCorridorKind.REST
                else -> return@mapNotNull null
            }
            val location = if (type == OsmType.NODE) item else item.optJSONObject("center") ?: return@mapNotNull null
            val lat = location.optDouble("lat", Double.NaN); val lon = location.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            fun tag(key: String) = tags.optString(key).filter { it.code >= 32 && it.code != 127 }.trim().take(300).takeIf { it.isNotBlank() }
            val name = listOf("name:tr", "name", "brand", "operator").firstNotNullOfOrNull(::tag)
                ?: (if (kind == RouteCorridorKind.FUEL) "fuel" else tags.optString("highway")).replace('_', ' ')
            val street = listOfNotNull(tag("addr:street"), tag("addr:housenumber")).joinToString(" ")
            val address = tag("addr:full") ?: listOfNotNull(street.takeIf { it.isNotBlank() },
                tag("addr:city"), tag("addr:province")).joinToString(", ").takeIf { it.isNotBlank() }
            ParsedRouteCorridorPlace(SelectedOsmPlace(name, lat, lon, OsmRef(type, id), address), kind)
        }.distinctBy { it.place.osmRef }
    }
}
