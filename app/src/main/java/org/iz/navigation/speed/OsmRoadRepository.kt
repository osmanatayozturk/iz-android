package org.iz.navigation.speed

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.integration.OsmPlaces
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.*

internal data class RoadRegion(val center: WeatherCoordinate, val roads: List<OsmRoad>, val fetchedAt: Long) {
    fun contains(point: WeatherCoordinate, now: Long): Boolean = now - fetchedAt in 0..86_400_000 && distance(center, point) <= 850
}

/** App-private OSM cache only. TomTom data never enters this repository. */
internal class OsmRoadRepository(
    private val directory: File,
    private val request: suspend (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "osm_road_queries"), OsmPlaces(context)::roadNetwork)

    suspend fun nearby(point: WeatherCoordinate): RoadRegion = withContext(Dispatchers.IO) {
        // Approximately one kilometre grid; the surrounding 1.5 km query includes every nearby road.
        val lat = (round(point.latitude / .008) * .008).coerceIn(-89.98, 89.98)
        val lonStep = (.008 / cos(Math.toRadians(lat)).coerceAtLeast(.05)).coerceAtMost(.16)
        val lon = (round(point.longitude / lonStep) * lonStep).coerceIn(-179.98, 179.98)
        val center = WeatherCoordinate(lat, lon)
        val key = String.format(Locale.US, "%.5f_%.5f.json", lat, lon)
        val target = File(directory, key)
        val now = clock()
        val cached = runCatching {
            if (target.isFile && target.length() <= 512_000 && now - target.lastModified() in 0..86_400_000)
                RoadRegion(center, parseOsmRoads(target.readText()), target.lastModified()) else null
        }.getOrNull()
        if (cached != null) return@withContext cached
        val dLat = 1_500.0 / 111_320
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(.05)
        require(lat - dLat >= -90 && lat + dLat <= 90 && lon - dLon >= -180 && lon + dLon <= 180)
        val bounds = listOf(lat - dLat, lon - dLon, lat + dLat, lon + dLon).joinToString(",") { String.format(Locale.US, "%.6f", it) }
        // Include ways without maxspeed; filtering them out would falsely remove parallel-road ambiguity.
        val query = "[out:json][timeout:15][maxsize:16777216];way[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|track|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"]($bounds);out body geom;"
        val json = request(query)
        require(json.toByteArray(Charsets.UTF_8).size <= 512_000)
        val result = RoadRegion(center, parseOsmRoads(json), clock())
        runCatching {
            directory.mkdirs()
            val temporary = File(directory, "$key.tmp")
            try {
                temporary.writeText(json, Charsets.UTF_8)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                target.setLastModified(result.fetchedAt)
            } finally { temporary.delete() }
            var bytes = 0L
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
                bytes += file.length()
                if (bytes > 16_000_000 || clock() - file.lastModified() !in 0..86_400_000) file.delete()
            }
        }
        result
    }
}

internal fun parseOsmRoads(json: String): List<OsmRoad> {
    val root = JSONObject(json)
    require(root.optString("remark").isBlank()) { "Incomplete road network" }
    val elements = root.getJSONArray("elements")
    require(elements.length() <= 10_000)
    var totalPoints = 0
    return (0 until elements.length()).mapNotNull { index ->
        val way = elements.getJSONObject(index)
        if (way.optString("type") != "way") return@mapNotNull null
        val geometry = way.getJSONArray("geometry")
        val nodes = way.getJSONArray("nodes")
        require(geometry.length() == nodes.length() && geometry.length() >= 2)
        totalPoints += geometry.length()
        require(totalPoints <= 40_000)
        val points = (0 until geometry.length()).map { geometry.getJSONObject(it).let { value ->
            WeatherCoordinate(value.getDouble("lat"), value.getDouble("lon"))
        } }
        val tags = way.getJSONObject("tags")
        OsmRoad(way.getLong("id"), points, (0 until nodes.length()).map { nodes.getLong(it) },
            tags.keys().asSequence().associateWith { tags.getString(it) })
    }
}
