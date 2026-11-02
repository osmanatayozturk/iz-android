package org.iz.navigation.offline

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Exact pinned JSON is reused by native preparation and the renderer; overlays have no remote resources. */
class AuditedOfflineStyle internal constructor(val json: String, val closure: OfflineResourceClosure,
    val profileProof: OfflineProfileProof? = null) {
    fun withLocalOverlays(overlay: String): String {
        require(overlay.toByteArray(Charsets.UTF_8).size <= 2_000_000)
        val output = JSONObject(OfflineJsonDepth.requireSafe(json))
        val sources = output.getJSONObject("sources")
        val layers = output.getJSONArray("layers")
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }.toMutableSet()
        val local = JSONObject(OfflineJsonDepth.requireSafe(overlay))
        val additions = local.optJSONObject("sources") ?: JSONObject()
        val allowed = mutableSetOf<String>()
        additions.keys().forEach { id ->
            if (id != "osm-tiles") {
                require(!sources.has(id))
                val source = additions.getJSONObject(id)
                require(source.getString("type") == "geojson" && source.opt("data") is JSONObject && !source.has("url"))
                sources.put(id, source); allowed += id
            }
        }
        val extra = local.optJSONArray("layers") ?: JSONArray()
        require(extra.length() <= 512)
        for (index in 0 until extra.length()) {
            val layer = extra.getJSONObject(index)
            if (layer.optString("source") == "osm-tiles" || layer.optString("id") == "osm-raster") continue
            require(layer.getString("source") in allowed && ids.add(layer.getString("id")))
            require(layer.getString("type") in setOf("line", "circle", "fill", "symbol"))
            if (profileProof != null) {
                for (section in listOf("layout", "paint")) layer.optJSONObject(section)?.let { fields ->
                    require(fields.keys().asSequence().none { it.endsWith("-pattern") || it.startsWith("icon-") })
                }
                if (layer.getString("type") == "symbol") {
                    val layout = layer.optJSONObject("layout") ?: JSONObject().also { layer.put("layout", it) }
                    require(layout.has("text-field"))
                    layout.put("text-font", JSONArray().put(OfflineGlyphBundle.NAME))
                }
            }
            layers.put(layer)
        }
        return OfflineJsonDepth.requireSafe(output.toString())
    }
}
object OfflineStyleAudit {
    /**
     * fetch must use the active job's bounded Call.Factory. This method itself owns no network
     * client, credentials or SDK. Source URL resolution is a single bounded level, not recursion.
     */
    fun resolve(styleBytes: ByteArray, fetch: (String) -> ByteArray): AuditedOfflineStyle {
        require(styleBytes.size in 1..512_000)
        val style = JSONObject(OfflineJsonDepth.requireSafe(styleBytes.toString(Charsets.UTF_8)))
        require(style.getInt("version") == 8)
        val sources = style.getJSONObject("sources")
        require(sources.length() in 1..8)
        // Reject foreign resource origins BEFORE querying any source metadata.
        if (style.has("glyphs")) resourceUrl(style.getString("glyphs"), setOf("fontstack", "range"))
        if (style.has("sprite")) resourceUrl(style.getString("sprite"), emptySet())
        require(listOf("imports", "terrain", "models", "font-faces", "fontFaces").none(style::has))
        sources.keys().forEach { id ->
            val source = sources.getJSONObject(id)
            require(source.getString("type") in setOf("vector", "raster"))
            require(!source.has("data") && !source.has("urls"))
            if (source.has("url")) resourceUrl(source.getString("url"), emptySet())
            source.optJSONArray("tiles")?.let(::tileTemplates)
        }
        sources.keys().forEach { id ->
            val source = sources.getJSONObject(id)
            if (source.has("url")) {
                val bytes = fetch(source.getString("url"))
                require(bytes.size in 1..512_000)
                val metadata = JSONObject(OfflineJsonDepth.requireSafe(bytes.toString(Charsets.UTF_8)))
                val tiles = metadata.getJSONArray("tiles")
                tileTemplates(tiles)
                source.remove("url")
                source.put("tiles", tiles)
                for (key in listOf("minzoom", "maxzoom", "attribution", "scheme", "tileSize"))
                    if (metadata.has(key)) source.put(key, metadata.get(key))
            }
            tileTemplates(source.getJSONArray("tiles"))
            val minimum = source.optInt("minzoom", 0)
            val maximum = source.optInt("maxzoom", 22)
            require(minimum in 0..22 && maximum in minimum..22)
            require(source.optString("scheme", "xyz") == "xyz")
        }
        val layers = style.getJSONArray("layers")
        require(layers.length() in 1..2048)
        val ids = mutableSetOf<String>()
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            require(ids.add(layer.getString("id")))
            if (layer.has("source")) require(sources.has(layer.getString("source")))
            require(layer.optString("type") !in setOf("custom", "model"))
        }
        val normalized = style.toString()
        require(normalized.toByteArray(Charsets.UTF_8).size <= 512_000)
        OfflineJsonDepth.requireSafe(normalized)
        return AuditedOfflineStyle(normalized, OfflineResourceClosure(sha256(normalized.toByteArray(Charsets.UTF_8)),
            setOf("https://tiles.openfreemap.org"), setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap"), false))
    }
    private fun tileTemplates(values: JSONArray) {
        require(values.length() in 1..8)
        for (index in 0 until values.length()) {
            val value = values.getString(index)
            require(Regex("https://tiles\\.openfreemap\\.org/planet/[A-Za-z0-9_-]{1,80}/\\{z}/\\{x}/\\{y}\\.pbf").matches(value))
            resourceUrl(value, setOf("z", "x", "y"))
        }
    }
    internal fun resourceUrl(value: String, tokens: Set<String>) {
        require(value.length in 1..2000 && !value.contains('\\'))
        val names = Regex("\\{([^}]+)}").findAll(value).map { it.groupValues[1] }.toSet()
        require(names.all { it in tokens })
        val expanded = value.replace(Regex("\\{[^}]+}"), "1")
        require(!expanded.contains('{') && !expanded.contains('}'))
        val url = expanded.toHttpUrl()
        require(url.scheme == "https" && url.host == "tiles.openfreemap.org" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        val path = url.encodedPath
        val tile = Regex("/planet/([A-Za-z0-9_-]{1,80})/([0-9]+)/([0-9]+)/([0-9]+)\\.pbf").matchEntire(path)
        val earth = Regex("/natural_earth/ne2sr/([0-9]+)/([0-9]+)/([0-9]+)\\.png").matchEntire(path)
        val font = Regex("/fonts/Noto%20Sans%20(?:Regular|Bold|Italic)/([0-9]+)-([0-9]+)\\.pbf").matchEntire(path)
        val source = path == "/planet" || path == "/styles/liberty"
        val sprite = path in setOf("/sprites/ofm_f384/ofm", "/sprites/ofm_f384/ofm.json",
            "/sprites/ofm_f384/ofm.png", "/sprites/ofm_f384/ofm@2x.json", "/sprites/ofm_f384/ofm@2x.png")
        val glyphTemplate = value == "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf" &&
            names == setOf("fontstack", "range")
        fun xyz(zText: String, xText: String, yText: String, max: Int): Boolean {
            val z = zText.toIntOrNull() ?: return false
            val x = xText.toIntOrNull() ?: return false
            val y = yText.toIntOrNull() ?: return false
            return z in 0..max && x in 0 until (1 shl z) && y in 0 until (1 shl z) &&
                zText == z.toString() && xText == x.toString() && yText == y.toString()
        }
        val vector = tile != null && xyz(tile.groupValues[2], tile.groupValues[3], tile.groupValues[4], 22)
        val raster = earth != null && xyz(earth.groupValues[1], earth.groupValues[2], earth.groupValues[3], 6)
        val glyph = font != null && run {
            val a = font.groupValues[1].toIntOrNull(); val b = font.groupValues[2].toIntOrNull()
            a != null && b != null && a in 0..65280 && a % 256 == 0 && b == a + 255 &&
                a.toString() == font.groupValues[1] && b.toString() == font.groupValues[2]
        }
        require(source || sprite || glyphTemplate || vector || raster || glyph)
        // Percent-encoded separators/dot traversal are never part of an admitted family.
        require(!value.contains('%') || glyph)
        if (names.isNotEmpty() && names != setOf("fontstack", "range")) {
            require(names == setOf("z", "x", "y") &&
                Regex("https://tiles\\.openfreemap\\.org/planet/[A-Za-z0-9_-]{1,80}/\\{z}/\\{x}/\\{y}\\.pbf").matches(value))
        }
    }
    internal fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
