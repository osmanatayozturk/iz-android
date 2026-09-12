package org.iz.navigation.speed

import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import kotlin.math.roundToInt

enum class RoadSpeedSource { OSM, TOMTOM_ROUTE, TOMTOM_POSTED }

data class RoadSpeedLimit(
    val valueKmh: Double?,
    val source: RoadSpeedSource,
    val genericPosted: Boolean,
    val observedAt: Long,
    val coordinate: WeatherCoordinate,
    val transport: Transport,
    val roadIdentity: String,
)

data class RoadSpeedState(val ownKmh: Double? = null, val limit: RoadSpeedLimit? = null, val visible: Boolean = false)
data class RoadSpeedPresentation(val ownText: String, val limitText: String, val sourceText: String)

fun ownSpeedKmh(fix: NavigationFix?, now: Long): Double? = fix?.takeIf { speedFixFresh(it, now) }
    ?.speedMps?.takeIf { it.isFinite() && it in 0f..100f }?.toDouble()?.times(3.6)

internal fun speedFixFresh(fix: NavigationFix, now: Long): Boolean =
    now - fix.recordedAt in 0..5_000 && fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..50f

internal fun speedMode(transport: Transport?): Boolean = transport == Transport.CAR || transport == Transport.MOTORCYCLE

fun roadSpeedPresentation(state: RoadSpeedState): RoadSpeedPresentation = RoadSpeedPresentation(
    state.ownKmh?.roundToInt()?.toString() ?: "—",
    state.limit?.let { it.valueKmh?.roundToInt()?.toString() ?: "∞" } ?: "—",
    state.limit?.let {
        val provider = if (it.source == RoadSpeedSource.OSM) "OSM" else "TomTom"
        if (it.genericPosted) "Genel yol sınırı · $provider" else provider
    } ?: "Sınır bilinmiyor",
)

internal sealed interface OsmSpeedValue {
    data class Known(val kmh: Double?, val genericPosted: Boolean = false) : OsmSpeedValue
    data object Missing : OsmSpeedValue
    data object Unknown : OsmSpeedValue
}

/** Unsupported restrictions are different from an absent tag: they must never trigger generic fallback. */
internal fun osmSpeedValue(tags: Map<String, String>, transport: Transport, forward: Boolean?): OsmSpeedValue {
    if (!speedMode(transport)) return OsmSpeedValue.Unknown
    val vehicle = if (transport == Transport.MOTORCYCLE) "motorcycle" else "motorcar"
    val bases = listOf("maxspeed", "maxspeed:vehicle", "maxspeed:motor_vehicle", "maxspeed:$vehicle")
    val direction = forward?.let { if (it) "forward" else "backward" }
    if (direction == null && tags.keys.any { key -> bases.any { key == "$it:forward" || key == "$it:backward" } }) return OsmSpeedValue.Unknown
    val relevant = bases.flatMap { listOf(it, "$it:$direction") }
    val restrictions = setOf("conditional", "lanes", "variable", "wet", "snow", "night")
    if (tags.any { (key, value) -> value.isNotBlank() && relevant.any {
            key.startsWith("$it:") && key.substringAfter("$it:").split(':').let { parts ->
                parts.all { part -> part in restrictions || part == "forward" || part == "backward" } &&
                    parts.any { part -> part in restrictions }
            } &&
                (direction == null || !key.split(':').contains(if (direction == "forward") "backward" else "forward"))
        } }) return OsmSpeedValue.Unknown
    val selected = bases.asReversed().firstNotNullOfOrNull { base ->
        listOfNotNull(direction?.let { "$base:$it" }, base).firstOrNull { tags[it]?.isNotBlank() == true }
    } ?: return OsmSpeedValue.Missing
    val value = tags.getValue(selected).trim().lowercase(java.util.Locale.ROOT)
    val generic = transport == Transport.MOTORCYCLE && !selected.startsWith("maxspeed:motorcycle")
    if (value == "none") return OsmSpeedValue.Known(null, generic)
    val match = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(km/h|kmh|kph|mph)?$").matchEntire(value) ?: return OsmSpeedValue.Unknown
    val numeric = match.groupValues[1].toDoubleOrNull() ?: return OsmSpeedValue.Unknown
    val kmh = numeric * if (match.groupValues[2] == "mph") 1.609344 else 1.0
    return if (kmh.isFinite() && kmh > 0 && kmh <= 400) OsmSpeedValue.Known(kmh, generic) else OsmSpeedValue.Unknown
}

internal data class OsmRoad(val id: Long, val points: List<WeatherCoordinate>, val nodeIds: List<Long>, val tags: Map<String, String>)
internal data class RoadMatch(val road: OsmRoad, val forward: Boolean, val segment: Int, val distanceMeters: Double) {
    val identity: String get() = "${road.id}:${if (forward) "f" else "b"}"
}
