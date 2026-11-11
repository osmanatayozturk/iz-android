package org.iz.navigation.car

import org.iz.navigation.weather.RouteStopLimits
import org.iz.navigation.weather.WeatherCoordinate
import java.net.URI
import java.net.URLDecoder

data class SharedMapStop(val name: String, val coordinate: WeatherCoordinate? = null, val currentLocation: Boolean = false)

sealed interface SharedMapInput {
    data class Single(val target: SharedMapStop, val originalUrl: String? = null) : SharedMapInput
    data class Itinerary(val origin: SharedMapStop?, val via: List<SharedMapStop>, val destination: SharedMapStop,
        val originalUrl: String? = null) : SharedMapInput
    data class ShortLink(val url: String) : SharedMapInput
    data class Unsupported(val message: String, val originalUrl: String? = null) : SharedMapInput
}

/** No geometry or network is imported. Vendor camera framing is never a destination.
 * https://developers.google.com/maps/documentation/urls/get-started
 * https://developer.apple.com/documentation/mapkit/unified-map-urls
 */
object SharedMapLinks {
    private val link = Regex("(?:https?://|geo:|google\\.navigation:)[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val pair = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)\\s*,\\s*[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
    private val hosts = setOf("google.com", "www.google.com", "maps.google.com", "maps.app.goo.gl", "goo.gl", "maps.apple", "maps.apple.com")
    internal fun allowedUrl(raw: String): URI? = runCatching {
        val u = URI(raw)
        val host = u.host?.lowercase() ?: return null
        if (u.scheme?.lowercase() != "https" || host !in hosts || u.rawUserInfo != null || u.port !in listOf(-1, 443)) return null
        val path = u.path.orEmpty()
        if (path.split('/').any { it == "." || it == ".." } || '\\' in path || u.rawFragment?.any { it.code < 32 } == true) return null
        val allowedPath = when (host) {
            "google.com", "www.google.com" -> path == "/maps" || path.startsWith("/maps/")
            "maps.google.com" -> path.isEmpty() || path == "/" || path == "/maps" || path.startsWith("/maps/")
            "goo.gl" -> path.startsWith("/maps/")
            "maps.app.goo.gl" -> path.matches(Regex("/[A-Za-z0-9_-]+/?"))
            else -> path in setOf("", "/", "/place", "/search", "/directions", "/frame") || path.startsWith("/p/") ||
                host == "maps.apple" && path.matches(Regex("/[A-Za-z0-9_-]+/?"))
        }
        u.takeIf { allowedPath && raw.none { it.code < 32 } }
    }.getOrNull()

    fun parse(raw: String?): SharedMapInput? {
        if (raw.isNullOrBlank() || raw.length > 16_384 || raw.any { it.code < 32 && it !in "\n\r\t" }) return null
        val text = raw.trim()
        val found = link.find(text)?.value?.trimEnd('.', ';')
        if (found == null) {
            if (text.contains("://") || text.length > 300 || text.any { it.code < 32 }) return unsupported()
            return SharedMapInput.Single(SharedMapStop(text))
        }
        if (found.startsWith("geo:", true) || found.startsWith("google.navigation:", true)) {
            val target = NavigationIntents.parseLegacy(found) ?: return null
            return SharedMapInput.Single(SharedMapStop(target.query, target.coordinate))
        }
        val uri = allowedUrl(found) ?: return unsupported()
        return try { parseUrl(uri, found) } catch (_: IllegalArgumentException) { unsupported(found) }
    }

    private fun parseUrl(uri: URI, original: String): SharedMapInput {
        val host = uri.host.lowercase()
        if (host in setOf("maps.app.goo.gl", "goo.gl", "maps.apple") || uri.path.startsWith("/p/")) return SharedMapInput.ShortLink(original)
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        fun first(vararg keys: String) = keys.firstNotNullOfOrNull { key -> params.firstOrNull { it.first == key }?.second?.takeIf(String::isNotBlank) }
        val apple = host == "maps.apple.com"
        val destinationValues = params.filter { it.first in if (apple) setOf("destination", "daddr") else setOf("destination", "daddr") }.map { it.second }
        val viaValues = params.filter { it.first in setOf("waypoints", "waypoint") }.flatMap { it.second.split('|') }
        val explicitOrigin = first("origin", "source", "saddr")?.let { stop(it, origin = true) }
        if (destinationValues.isNotEmpty()) {
            val destination = stop(destinationValues.last()) ?: return unsupported(original)
            val via = (viaValues + destinationValues.dropLast(1)).map { stop(it) ?: return unsupported(original) }
            return itinerary(explicitOrigin, via, destination, original)
        }
        val path = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val routePath = uri.rawPath.orEmpty().split('/')
        val dirIndex = routePath.indexOf("dir")
        if (!apple && dirIndex >= 0) {
            val segments = routePath.drop(dirIndex + 1).takeWhile { !it.startsWith('@') && !it.startsWith("data=") }
                .dropLastWhile { it.isEmpty() }
            val dynamic = segments.firstOrNull()?.isEmpty() == true
            val named = if (dynamic) segments.drop(1) else segments
            if (named.any { it.isBlank() }) return unsupported(original)
            val stops = named.map {
                stop(decodePath(it)) ?: return unsupported(original)
            }
            if (dynamic && stops.isNotEmpty()) return itinerary(null, stops.dropLast(1), stops.last(), original)
            if (stops.size >= 2) return itinerary(stops.first(), stops.drop(1).dropLast(1), stops.last(), original)
            if (stops.size == 1) return SharedMapInput.Single(stops.single(), original)
        }
        val name = first("name", "query", "q", "address")
        val explicitCoordinate = if (apple && uri.path == "/place") first("coordinate") else null
        if (explicitCoordinate != null) {
            val target = stop(explicitCoordinate) ?: return unsupported(original)
            if (target.coordinate == null) return unsupported(original)
            return SharedMapInput.Single(target.copy(name = name?.take(300) ?: target.name), original)
        }
        // A /place label is useful search text; @lat,lon remains framing only.
        val placeIndex = path.indexOf("place")
        val pathLabel = if (!apple && placeIndex >= 0) path.getOrNull(placeIndex + 1)?.takeUnless { it.startsWith('@') || it.startsWith("data=") }?.let(::decodePath) else null
        val data = decodePath(uri.rawPath.orEmpty())
        val pins = Regex("!3d([-+\\d.]+)!4d([-+\\d.]+)").findAll(data).toList()
        if (!apple && placeIndex >= 0 && pins.size == 1) {
            val pin = pins.single()
            val target = stop("${pin.groupValues[1]},${pin.groupValues[2]}") ?: return unsupported(original)
            return SharedMapInput.Single(target.copy(name = name ?: pathLabel ?: target.name), original)
        }
        val usable = name ?: pathLabel
        if (usable != null) return stop(usable)?.let { SharedMapInput.Single(it, original) } ?: unsupported(original)
        return unsupported(original)
    }

    private fun itinerary(origin: SharedMapStop?, via: List<SharedMapStop>, destination: SharedMapStop, original: String): SharedMapInput {
        if (via.size + 2 > RouteStopLimits.MAX_PLANNED_STOPS) return SharedMapInput.Unsupported("Rota en fazla 20 durak içerebilir. Hiçbir durak aktarılmadı.", original)
        return if (origin == null && via.isEmpty()) SharedMapInput.Single(destination, original)
        else SharedMapInput.Itinerary(origin, via, destination, original)
    }
    private fun stop(raw: String, origin: Boolean = false): SharedMapStop? {
        val text = raw.trim()
        if (text.isBlank() || text.length > 300 || text.any { it.code < 32 }) return null
        if (origin && text.lowercase() in setOf("here", "my location", "current location", "my-location", "konumum")) return SharedMapStop("Konumum", currentLocation = true)
        if (text.startsWith("place_id:", true) || text.startsWith("ChIJ") || text.matches(Regex("0x[0-9a-fA-F]+:0x[0-9a-fA-F]+"))) return null
        val coordinateText = text.removePrefix("loc:").substringBefore('(').trim()
        if (pair.matches(coordinateText)) {
            val values = coordinateText.split(',')
            val coordinate = WeatherCoordinate(values[0].trim().toDouble(), values[1].trim().toDouble())
            return SharedMapStop(text.substringAfter('(', "").removeSuffix(")").ifBlank { coordinateText }, coordinate)
        }
        if (coordinateText.contains(',') && coordinateText.split(',').all { it.trim().toDoubleOrNull() != null }) return null
        return SharedMapStop(text)
    }
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    private fun decodePath(value: String) = decode(value.replace("+", "%20"))
    private fun unsupported(original: String? = null) = SharedMapInput.Unsupported("Bu bağlantıda kullanılabilir hedef bulunamadı. Harita uygulamasında açabilir veya yer adıyla arayabilirsin.", original)
}
