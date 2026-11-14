package org.iz.navigation.integration

import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.abs
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Display-only geometry; this is never passed to the OSM editing/write path. */
internal data class MapPlaceOutline(val points: List<GeoCoordinate>)
internal data class MapOutlineReadResult(val outline: MapPlaceOutline?, val message: String? = null)
internal const val MAP_OUTLINE_QUERY_REVISION = "simple-area-v1"
internal const val MAP_OUTLINE_SOURCE = "map-selected-place-outline"

internal fun mapPlaceOutlineStyle(style: String): String = JSONObject(style).apply {
    getJSONObject("sources").put(MAP_OUTLINE_SOURCE, JSONObject().put("type", "geojson")
        .put("data", JSONObject().put("type", "FeatureCollection").put("features", JSONArray())))
    val layers = getJSONArray("layers")
    val insertion = (0 until layers.length()).lastOrNull { layers.getJSONObject(it).optString("type") in setOf("background", "raster") }?.plus(1) ?: 0
    val fill = JSONObject().put("id", "selected-place-area-fill").put("type", "fill").put("source", MAP_OUTLINE_SOURCE)
        .put("paint", JSONObject().put("fill-color", "#236BA6").put("fill-opacity", .16))
    val line = JSONObject().put("id", "selected-place-area-line").put("type", "line").put("source", MAP_OUTLINE_SOURCE)
        .put("paint", JSONObject().put("line-color", "#236BA6").put("line-width", 3))
    put("layers", JSONArray().also { target ->
        for (index in 0..layers.length()) {
            if (index == insertion) { target.put(fill); target.put(line) }
            if (index < layers.length()) target.put(layers.getJSONObject(index))
        }
    })
}.toString()

internal fun mapPlaceOutlineJson(candidate: MapPlaceCandidate?): String {
    val features = JSONArray()
    val outline = candidate?.outline
    val ref = candidate?.place?.osmRef
    if (outline != null && ref?.type == OsmType.WAY) {
        val ring = JSONArray().also { coordinates -> outline.points.forEach { coordinates.put(JSONArray().put(it.longitude).put(it.latitude)) } }
        features.put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("osmId", ref.id).put("osmType", "way"))
            .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/** Uses the existing process mutex, budgeted transport and bounded cache; invalid/error bodies are not cached. */
internal class MapPlaceOutlineReader(private val mutex: Mutex, private val endpoint: () -> String,
    private val readCached: (String, Long) -> String?, private val writeCache: (String, String) -> Unit,
    private val execute: suspend (String, String) -> String, private val catalog: () -> OsmPresetCatalog) {
    suspend fun read(ref: OsmRef): MapOutlineReadResult = mutex.withLock {
        val query = mapPlaceOutlineQuery(ref)
        val expectedEndpoint = endpoint()
        val key = "map-outline:$MAP_OUTLINE_QUERY_REVISION:${OsmPresetCatalog.BUNDLED_SHA256}:$expectedEndpoint:${ref.type}/${ref.id}"
        val cached = readCached(key, 600_000)?.let { body -> runCatching { parseMapPlaceOutline(body, ref, catalog()) }.getOrNull() }
        coroutineContext.ensureActive()
        if (endpoint() != expectedEndpoint) throw kotlinx.coroutines.CancellationException("Harita servisi değişti.")
        if (cached?.outline != null) return@withLock cached
        val json = execute(query, expectedEndpoint)
        coroutineContext.ensureActive()
        if (endpoint() != expectedEndpoint) throw kotlinx.coroutines.CancellationException("Harita servisi değişti.")
        val result = parseMapPlaceOutline(json, ref, catalog())
        coroutineContext.ensureActive()
        if (endpoint() != expectedEndpoint) throw kotlinx.coroutines.CancellationException("Harita servisi değişti.")
        if (result.outline != null) writeCache(key, json)
        result
    }
}

internal fun mapPlaceOutlineQuery(ref: OsmRef): String {
    require(ref.type == OsmType.WAY && ref.id > 0)
    return "[out:json][timeout:15][maxsize:16777216];way(${ref.id});out body geom;"
}

internal fun parseMapPlaceOutline(json: String, ref: OsmRef, catalog: OsmPresetCatalog): MapOutlineReadResult {
    require(json.toByteArray(Charsets.UTF_8).size <= 512_000)
    val root = JSONObject(json)
    require(!root.has("remark")) { "Sınır sorgusu tamamlanmadı." }
    fun fallback() = MapOutlineReadResult(null, "Bu nesnenin basit alan sınırı doğrulanamadı; konumu nokta olarak işaretlendi.")
    if (ref.type != OsmType.WAY || ref.id <= 0) return fallback()
    val elements = root.optJSONArray("elements") ?: return fallback()
    if (elements.length() != 1) return fallback()
    val way = elements.optJSONObject(0) ?: return fallback()
    if (way.optString("type") != "way" || way.optLong("id") != ref.id || way.has("members")) return fallback()
    val rawTags = way.optJSONObject("tags") ?: return fallback()
    val tags = rawTags.keys().asSequence().associateWith { rawTags.opt(it) as? String ?: return fallback() }
    if (tags["area"] == "no") return fallback()
    val areaEvidence = catalog.identify(tags, OsmPresetGeometry.AREA).any { record ->
        OsmPresetRuleSnapshot(catalog.commit, catalog.sha256, record.id, OsmPresetGeometry.AREA, record.ruleJson)
            .providesAreaEvidence(tags)
    }
    if (!areaEvidence) return fallback()
    val nodes = way.optJSONArray("nodes") ?: return fallback()
    val geometry = way.optJSONArray("geometry") ?: return fallback()
    if (nodes.length() !in 4..2000 || nodes.length() != geometry.length()) return fallback()
    val refs = (0 until nodes.length()).map { nodes.optLong(it) }
    if (refs.any { it <= 0 } || refs.first() != refs.last() || refs.dropLast(1).toSet().size != refs.size - 1) return fallback()
    val points = (0 until geometry.length()).map { index ->
        val point = geometry.optJSONObject(index) ?: return fallback()
        val lat = point.optDouble("lat", Double.NaN)
        val lon = point.optDouble("lon", Double.NaN)
        if (!validCoordinate(lat, lon)) return fallback()
        GeoCoordinate(lat, lon)
    }
    if (points.first() != points.last() || points.dropLast(1).toSet().size != points.size - 1 ||
        points.maxOf { it.longitude } - points.minOf { it.longitude } > 180) return fallback()
    val area = points.zipWithNext().sumOf { (a, b) -> a.longitude * b.latitude - b.longitude * a.latitude }
    if (abs(area) <= 1e-12) return fallback()
    fun cross(a: GeoCoordinate, b: GeoCoordinate, c: GeoCoordinate) =
        (b.longitude - a.longitude) * (c.latitude - a.latitude) - (b.latitude - a.latitude) * (c.longitude - a.longitude)
    fun onSegment(a: GeoCoordinate, b: GeoCoordinate, p: GeoCoordinate) = abs(cross(a, b, p)) <= 1e-12 &&
        p.longitude in minOf(a.longitude, b.longitude)..maxOf(a.longitude, b.longitude) &&
        p.latitude in minOf(a.latitude, b.latitude)..maxOf(a.latitude, b.latitude)
    fun intersects(a: GeoCoordinate, b: GeoCoordinate, c: GeoCoordinate, d: GeoCoordinate): Boolean {
        val abC = cross(a, b, c); val abD = cross(a, b, d)
        val cdA = cross(c, d, a); val cdB = cross(c, d, b)
        return (abC * abD < 0 && cdA * cdB < 0) || onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b)
    }
    val segments = points.size - 1
    for (i in 0 until segments) for (j in i + 1 until segments) {
        if (j == i + 1 || i == 0 && j == segments - 1) continue
        if (intersects(points[i], points[i + 1], points[j], points[j + 1])) return fallback()
    }
    return MapOutlineReadResult(MapPlaceOutline(points))
}
