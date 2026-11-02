package org.iz.navigation.offline

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class OfflineLicenseReview { VERIFIED_SOURCE_NOTICES }
enum class OfflineCorpusReadiness { MATERIALIZED }
data class OfflineProfileProof(val fontBundleSha256: String, val noticesSha256: String,
    val acquisitionSha256: String, val corpusId: String,
    val licenseReview: OfflineLicenseReview = OfflineLicenseReview.VERIFIED_SOURCE_NOTICES,
    val corpusReadiness: OfflineCorpusReadiness = OfflineCorpusReadiness.MATERIALIZED,
    val nativeReadability: String = "NOT_RUN")

/** One actual labelled profile. No remote glyph fallback, raster or unresolved artwork. */
object OfflineLabelledProfile {
    const val ASSET_ROOT = "offline/labelled-latin-v1"
    const val FONT_SHA = "c19e8462bc293b89a780aa1a0d246f71d07fe30dd07e8e61abf4f2f6ce0aedc0"
    const val NOTICES_SHA = "d3cfb87cc1bace916dbf128158e3ae1fa8062904deea9c36009f184b0e42ac94"
    fun glyphBundle(read: (String) -> ByteArray): OfflineGlyphBundle =
        OfflineGlyphBundle.verify(read("BUNDLE-MANIFEST.json"), FONT_SHA, read)

    fun installNotices(directory: File, read: (String) -> ByteArray) {
        val manifest = read("notices/" + NOTICES_SHA + ".json")
        require(manifest.size <= 128_000 && OfflineStyleAudit.sha256(manifest) == NOTICES_SHA)
        val root = JSONObject(OfflineJsonDepth.requireSafe(manifest.toString(Charsets.UTF_8)))
        require(root.getString("schema") == "iz.offline.notices.v1" &&
            root.getString("profileId") == OfflineProfileNotices.PROFILE_ID && root.getString("fontBundleSha256") == FONT_SHA)
        val rows = root.getJSONArray("files"); require(rows.length() == 3)
        val expected = setOf("2f54d8afce21896c863cb48c6d8b1f0ac1b786b94e63e81b2cfa554c1630da48",
            "58402f82a7c332a700294988fe7554fbb0a63a8d27ccc1ee3bbc640311990a00",
            "127a5f8f8e10214b4cce882d921c24d183656fd2220d7ad4185136f3a0eadfd3")
        val seen = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val sha = row.getString("sha256")
            require(sha in expected && seen.add(sha) && row.getString("path") == "notices/" + sha + ".txt")
            val bytes = read(row.getString("path"))
            require(bytes.size == row.getInt("bytes") && bytes.size in 1..128_000 && OfflineStyleAudit.sha256(bytes) == sha)
            OfflineResourceCorpus.writeOwned(File(directory, "notices"), sha + ".txt", bytes, 128_000)
        }
        require(seen == expected)
        OfflineResourceCorpus.writeOwned(File(directory, "notices"), NOTICES_SHA + ".json", manifest, 128_000)
    }

    fun resolve(raw: ByteArray, fetch: (String) -> ByteArray): AuditedOfflineStyle {
        require(raw.size in 1..512_000)
        val style = JSONObject(OfflineJsonDepth.requireSafe(raw.toString(Charsets.UTF_8)))
        require(listOf("imports", "terrain", "models", "font-faces", "fontFaces").none(style::has))
        if (style.has("glyphs")) OfflineStyleAudit.resourceUrl(style.getString("glyphs"), setOf("fontstack", "range"))
        if (style.has("sprite")) OfflineStyleAudit.resourceUrl(style.getString("sprite"), emptySet())
        style.remove("sprite"); style.remove("glyphs")
        val sources = style.getJSONObject("sources"); val removed = mutableSetOf<String>()
        sources.keys().asSequence().toList().forEach { id ->
            val source = sources.getJSONObject(id)
            when (source.getString("type")) {
                "raster" -> {
                    require(!source.has("url"))
                    val tiles = source.getJSONArray("tiles")
                    require(tiles.length() == 1 && tiles.getString(0) ==
                        "https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png")
                    sources.remove(id); removed += id
                }
                "vector" -> require(id == "openmaptiles")
                else -> throw IllegalArgumentException("Unsupported offline source")
            }
        }
        require(sources.length() == 1 && sources.has("openmaptiles"))
        val original = style.getJSONArray("layers"); val layers = JSONArray()
        for (index in 0 until original.length()) {
            val layer = original.getJSONObject(index)
            if (layer.optString("source") in removed || layer.optString("type") == "raster") continue
            require(layer.getString("type") in setOf("background", "fill", "line", "circle", "symbol", "fill-extrusion", "heatmap"))
            for (section in listOf("layout", "paint")) layer.optJSONObject(section)?.let { properties ->
                properties.keys().asSequence().toList().filter { it.endsWith("-pattern") || it.startsWith("icon-") }
                    .forEach(properties::remove)
            }
            if (layer.getString("type") == "symbol") {
                val layout = layer.optJSONObject("layout") ?: continue
                if (!layout.has("text-field")) continue
                layout.put("text-font", JSONArray().put(OfflineGlyphBundle.NAME))
            }
            layers.put(layer)
        }
        require(setOf("transportation_name", "place").all { sourceLayer ->
            (0 until layers.length()).any {
                val layer = layers.getJSONObject(it)
                layer.optString("type") == "symbol" && layer.optString("source-layer") == sourceLayer
            }
        }) { "Readable road and locality label layers are required" }
        style.put("layers", layers)
        return OfflineStyleAudit.resolve(style.toString().toByteArray(Charsets.UTF_8), fetch)
    }

    fun materialize(resolved: AuditedOfflineStyle, bundle: OfflineGlyphBundle, directory: File,
        corpusDirectory: File, acquisitionSha: String,
        tiles: Map<String, OfflineLocalFileResource>): Pair<AuditedOfflineStyle, Map<String, OfflineLocalFileResource>> {
        require(bundle.sha256 == FONT_SHA && tiles.isNotEmpty() && tiles.size <= MAX_OFFLINE_TILES)
        OfflineProfileNotices.digest(acquisitionSha)
        val seed = listOf(OfflineProfileNotices.PROFILE_ID, resolved.closure.styleSha256, FONT_SHA, NOTICES_SHA, acquisitionSha)
            .joinToString("\n")
        val corpus = OfflineStyleAudit.sha256(seed.toByteArray(Charsets.UTF_8))
        val style = JSONObject(OfflineJsonDepth.requireSafe(resolved.json))
        val source = style.getJSONObject("sources").getJSONObject("openmaptiles")
        val base = OfflineLocalResources.ORIGIN + "/" + corpus
        source.remove("url")
        source.put("tiles", JSONArray().put(base + "/tiles/{z}/{x}/{y}.pbf"))
        style.put("glyphs", base + "/glyphs/{fontstack}/{range}.pbf")
        val metadata = style.optJSONObject("metadata") ?: JSONObject().also { style.put("metadata", it) }
        metadata.put(OfflineProfileNotices.METADATA_KEY, JSONObject()
            .put("id", OfflineProfileNotices.PROFILE_ID).put("fontBundleSha256", FONT_SHA)
            .put("noticesSha256", NOTICES_SHA).put("acquisitionSha256", acquisitionSha).put("corpusId", corpus)
            .put("sourceStyleSha256", resolved.closure.styleSha256).put("nativeReadability", "NOT_RUN"))
        val json = style.toString()
        val audit = parse(json.toByteArray(Charsets.UTF_8))
        require(OfflineProfileNotices.read(directory, json, audit.closure.styleSha256) != null)
        val mapping = tiles.toMutableMap()
        for (path in bundle.glyphPaths) {
            val bytes = requireNotNull(bundle.files[path]); val sha = OfflineStyleAudit.sha256(bytes)
            val file = OfflineResourceCorpus.writeOwned(corpusDirectory, sha + ".glyph", bytes, 512_000)
            val encoded = path.replace(OfflineGlyphBundle.NAME, "Iz%20Offline%20Latin%20Regular")
            mapping[encoded] = OfflineLocalFileResource(OfflineLocalRole.GLYPH, file, bytes.size, sha)
        }
        val styleFile = OfflineResourceCorpus.writeOwned(directory, audit.closure.styleSha256 + ".json",
            json.toByteArray(Charsets.UTF_8), 512_000)
        mapping["style.json"] = OfflineLocalFileResource(OfflineLocalRole.STYLE, styleFile,
            json.toByteArray(Charsets.UTF_8).size, audit.closure.styleSha256)
        val proof = OfflineProfileProof(FONT_SHA, NOTICES_SHA, acquisitionSha, corpus)
        return AuditedOfflineStyle(json, audit.closure.copy(complete = true), proof) to mapping
    }

    /** Parse an already pinned graph; this alone never grants complete/READY. */
    fun parse(bytes: ByteArray): AuditedOfflineStyle {
        require(bytes.size in 1..512_000)
        val style = JSONObject(OfflineJsonDepth.requireSafe(bytes.toString(Charsets.UTF_8))); require(style.getInt("version") == 8)
        val profile = style.getJSONObject("metadata").getJSONObject(OfflineProfileNotices.METADATA_KEY)
        require(profile.getString("id") == OfflineProfileNotices.PROFILE_ID &&
            profile.getString("fontBundleSha256") == FONT_SHA && profile.getString("noticesSha256") == NOTICES_SHA)
        val corpus = OfflineProfileNotices.digest(profile.getString("corpusId"))
        OfflineProfileNotices.digest(profile.getString("acquisitionSha256"))
        OfflineProfileNotices.digest(profile.getString("sourceStyleSha256"))
        require(!style.has("sprite") && listOf("imports", "terrain", "models", "font-faces", "fontFaces").none(style::has))
        val base = OfflineLocalResources.ORIGIN + "/" + corpus
        require(style.getString("glyphs") == base + "/glyphs/{fontstack}/{range}.pbf")
        val sources = style.getJSONObject("sources"); require(sources.length() == 1)
        val source = sources.getJSONObject("openmaptiles")
        require(source.getString("type") == "vector" && !source.has("url") && !source.has("data") &&
            source.getJSONArray("tiles").length() == 1 && source.getJSONArray("tiles").getString(0) == base + "/tiles/{z}/{x}/{y}.pbf")
        val layers = style.getJSONArray("layers"); require(layers.length() in 1..2048)
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            require(!layer.has("source") || layer.getString("source") == "openmaptiles")
            for (section in listOf("paint", "layout")) layer.optJSONObject(section)?.let { fields ->
                require(fields.keys().asSequence().none { it.endsWith("-pattern") || it.startsWith("icon-") })
            }
            if (layer.optString("type") == "symbol")
                require(layer.getJSONObject("layout").getJSONArray("text-font").toString() == JSONArray().put(OfflineGlyphBundle.NAME).toString())
        }
        val json = bytes.toString(Charsets.UTF_8)
        return AuditedOfflineStyle(json, OfflineResourceClosure(OfflineStyleAudit.sha256(bytes),
            setOf("https://tiles.openfreemap.org"), setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap",
                "MIT", "BSD-3-Clause", "CC-BY-3.0", "CC-BY-4.0", "OFL-1.1"), false))
    }
}
