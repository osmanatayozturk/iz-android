package org.iz.navigation.integration

import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.json.JSONObject
import java.util.Locale

internal fun osmEditorMatchKeys(catalog: OsmPresetCatalog): Set<String> =
    (catalog.search("", OsmPresetGeometry.POINT) + catalog.search("", OsmPresetGeometry.AREA))
        .flatMap { it.tags.keys }.filterNot { '*' in it }.toSortedSet()

internal fun osmEditorNearbyQuery(latitude: Double, longitude: Double, matchKeys: Set<String>): String {
    require(validCoordinate(latitude, longitude) && matchKeys.size in 1..256)
    val keys = matchKeys.sorted().joinToString("|") { key ->
        require(key.isNotEmpty() && key.length <= 255 && key.none { it.code <= 32 || it.code == 127 })
        key.map { if (it in "\\.^$|?*+()[]{}") "\\$it" else it.toString() }.joinToString("")
    }
    val lat = String.format(Locale.US, "%.4f", latitude)
    val lon = String.format(Locale.US, "%.4f", longitude)
    return ("[out:json][timeout:15][maxsize:16777216];nwr(around:100,$lat,$lon)" +
        "[~${JSONObject.quote("^($keys)$")}~\".\"];out center tags qt;").also {
        require(it.toByteArray(Charsets.UTF_8).size <= 16_384)
    }
}

internal fun parseOverpassPlaces(json: String): List<SelectedOsmPlace> = parseOverpassPlaces(json, null)

internal fun parseOverpassPlaces(json: String, catalog: OsmPresetCatalog?): List<SelectedOsmPlace> {
    val root = JSONObject(json)
    require(!root.has("remark")) { "OSM yer sorgusu tamamlanmadı." }
    val array = root.optJSONArray("elements") ?: return emptyList()
    require(array.length() <= 2000) { "Bu bölgede çok fazla OSM nesnesi var." }
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val ref = osmRef(item.optString("type"), item.optLong("id")) ?: return@mapNotNull null
        val location = if (ref.type == OsmType.NODE) item else item.optJSONObject("center") ?: return@mapNotNull null
        val latitude = location.optDouble("lat", Double.NaN)
        val longitude = location.optDouble("lon", Double.NaN)
        if (!validCoordinate(latitude, longitude)) return@mapNotNull null
        val jsonTags = item.optJSONObject("tags") ?: return@mapNotNull null
        val tags = jsonTags.keys().asSequence().associateWith { key ->
            requireNotNull(jsonTags.opt(key) as? String) { "OSM etiketi doğrulanamadı." }
        }
        val geometry = if (ref.type == OsmType.NODE) OsmPresetGeometry.POINT else OsmPresetGeometry.AREA
        val name = listOf("name:tr", "name", "brand").firstNotNullOfOrNull { tags[it]?.takeIf(String::isNotBlank) }
            ?: catalog?.identify(tags, geometry)?.firstOrNull()?.name
            ?: listOf("amenity", "shop", "tourism", "leisure", "office", "craft", "historic")
                .firstNotNullOfOrNull { tags[it]?.takeIf(String::isNotBlank) }
            ?: if (!tags["building"].isNullOrBlank()) "Bina" else "OSM ${ref.type.name.lowercase(Locale.ROOT)}/${ref.id}"
        SelectedOsmPlace(name.replace('_', ' '), latitude, longitude, ref)
    }.distinctBy { it.osmRef }
}

internal fun osmRef(type: String, id: Long): OsmRef? {
    if (id <= 0) return null
    val kind = when (type) { "node" -> OsmType.NODE; "way" -> OsmType.WAY; "relation" -> OsmType.RELATION; else -> return null }
    return OsmRef(kind, id)
}

internal fun validCoordinate(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
