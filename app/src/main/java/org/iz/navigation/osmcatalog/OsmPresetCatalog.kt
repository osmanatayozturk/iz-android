package org.iz.navigation.osmcatalog

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class OsmPresetGeometry(val token: String) { POINT("point"), AREA("area") }

/** Read-only definition from one pinned catalogue; fields and geometry editing are not inferred. */
class OsmPresetDefinition internal constructor(
    val id: String, val name: String, val sourceName: String, val turkishName: String?,
    val turkishAliases: List<String>, val sourceAliases: List<String>, val terms: List<String>,
    val geometry: List<OsmPresetGeometry>, val sourceGeometry: List<String>,
    val tags: Map<String, String>, val addTags: Map<String, String>, val removeTags: Map<String, String>,
    val searchable: Boolean, val selectable: Boolean, val matchScore: Double,
    val locationSetJson: String?, val requiresLocationValidation: Boolean,
    internal val normalizedSearch: String, internal val ruleJson: String,
) {
    internal fun matches(existing: Map<String, String>, kind: OsmPresetGeometry): Boolean =
        kind in geometry && tags.all { (key, value) ->
            when {
                key.endsWith(":*") -> existing.any { (candidate, v) -> candidate.startsWith(key.dropLast(1)) && v.isNotEmpty() && v != "*" }
                value == "*" -> existing[key]?.let { it.isNotEmpty() && it != "*" } == true
                else -> existing[key] == value
            }
        }
}

/** Persist this exact value with the draft; never re-resolve it through a later catalogue asset. */
data class OsmPresetRuleSnapshot(
    val catalogCommit: String, val catalogueSha256: String, val presetId: String,
    val geometry: OsmPresetGeometry, val ruleJson: String, val locationValidated: Boolean = false,
) {
    init {
        require(catalogCommit.matches(Regex("[0-9a-f]{40}")) && catalogueSha256.matches(Regex("[0-9a-f]{64}")))
        val definition = definition(checkedJson(ruleJson, 256 * 1024))
        require(definition.id == presetId && geometry in definition.geometry)
    }
    fun toJson(): String = canonical(JSONObject().put("format", "iz.osm-preset-snapshot").put("schemaVersion", 1)
        .put("catalogCommit", catalogCommit).put("catalogueSha256", catalogueSha256).put("presetId", presetId)
        .put("geometry", geometry.token).put("rule", JSONObject(ruleJson)).put("locationValidated", locationValidated))

    fun providesAreaEvidence(existing: Map<String, String>): Boolean {
        val rule = definition(checkedJson(ruleJson, 256 * 1024))
        return geometry == OsmPresetGeometry.AREA && rule.matches(existing, geometry) &&
            rule.tags.keys.any { it != "area" && it != "name" && !it.startsWith("addr:") } &&
            !("building" in rule.tags && existing["building"] == "no") &&
            ("line" !in rule.sourceGeometry || existing["area"] == "yes")
    }

    companion object {
        fun fromJson(raw: String): OsmPresetRuleSnapshot {
            val json = checkedJson(raw, 512 * 1024)
            require(json.getString("format") == "iz.osm-preset-snapshot" && json.getInt("schemaVersion") == 1)
            return OsmPresetRuleSnapshot(json.getString("catalogCommit"), json.getString("catalogueSha256"),
                json.getString("presetId"), OsmPresetGeometry.entries.single { it.token == json.getString("geometry") },
                canonical(json.getJSONObject("rule")), json.getBoolean("locationValidated"))
        }
    }
}

class OsmPresetCatalog private constructor(
    val commit: String, val sha256: String, private val definitions: Map<String, OsmPresetDefinition>,
) {
    fun resolve(id: String): OsmPresetDefinition? = definitions[id]

    fun search(query: String, geometry: OsmPresetGeometry, favoriteIds: Set<String> = emptySet(),
        locationAllowed: (String) -> Boolean = { false }): List<OsmPresetDefinition> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        return definitions.values.filter { record ->
            record.selectable && geometry in record.geometry &&
                (!record.requiresLocationValidation || record.locationSetJson?.let(locationAllowed) == true) &&
                words.all(record.normalizedSearch::contains)
        }.sortedWith(compareByDescending<OsmPresetDefinition> { it.id in favoriteIds }
            .thenBy { normalize(it.name) }.thenBy { it.id })
    }

    /** Candidates, not an automatic type-change decision or a claim of exact iD match-score parity. */
    fun identify(tags: Map<String, String>, geometry: OsmPresetGeometry): List<OsmPresetDefinition> =
        definitions.values.filter { it.matches(tags, geometry) }
            .sortedWith(compareByDescending<OsmPresetDefinition> { it.tags.values.count { value -> value != "*" } }
                .thenByDescending { it.tags.size }.thenByDescending { it.matchScore }.thenBy { it.id })

    fun snapshot(id: String, geometry: OsmPresetGeometry, locationValidated: Boolean = false): OsmPresetRuleSnapshot {
        val record = requireNotNull(resolve(id)) { "Catalogue ID is unavailable" }
        require(record.selectable && geometry in record.geometry) { "Preset is not selectable for this geometry" }
        require(!record.requiresLocationValidation || locationValidated) { "Local location validation required" }
        return OsmPresetRuleSnapshot(commit, sha256, id, geometry, record.ruleJson, locationValidated)
    }

    companion object {
        const val BUNDLED_ASSET = "osm-light-catalogue-v1.json"
        const val BUNDLED_COMMIT = "f4cd7759d2ef509ae0360662988208d16a21374c"
        const val BUNDLED_SHA256 = "7759ee8364771334e4c24b9817ee6c7b354fc670338c92eecca6696cf74aeb56"
        private const val MAX_BYTES = 8 * 1024 * 1024

        /** Caller supplies assets.open(BUNDLED_ASSET); no context/global/network dependency. Closes input. */
        fun load(input: InputStream): OsmPresetCatalog {
            val bytes = input.use { source ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES) { "Catalogue exceeds byte bound" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(hash(bytes) == BUNDLED_SHA256) { "Bundled catalogue hash mismatch" }
            return fromJson(bytes.toString(Charsets.UTF_8)).also { require(it.commit == BUNDLED_COMMIT) }
        }

        /** Pure parser for explicit fixtures/import review. Production asset loading uses load's fixed hash. */
        fun fromJson(raw: String): OsmPresetCatalog {
            val root = checkedJson(raw, MAX_BYTES)
            require(root.getString("format") == "iz.osm-light-catalogue" && root.getInt("schemaVersion") == 1)
            val commit = root.getJSONObject("upstream").getString("commit")
            require(commit.matches(Regex("[0-9a-f]{40}")))
            val array = root.getJSONArray("presets")
            require(array.length() in 1..10_000)
            val rows = LinkedHashMap<String, OsmPresetDefinition>()
            for (index in 0 until array.length()) {
                val row = definition(array.getJSONObject(index))
                require(rows.put(row.id, row) == null) { "Duplicate catalogue ID" }
            }
            return OsmPresetCatalog(commit, hash(raw.toByteArray(Charsets.UTF_8)), immutableMap(rows))
        }
    }
}

/** Exact before/after tag-map helper only; caller retains account/version/geometry review and write guards. */
object OsmPresetTagTransition {
    fun apply(existing: Map<String, String>, old: OsmPresetRuleSnapshot?, next: OsmPresetRuleSnapshot): Map<String, String> {
        val to = definition(checkedJson(next.ruleJson, 256 * 1024))
        require(to.selectable && next.geometry in to.geometry)
        require(!to.requiresLocationValidation || next.locationValidated)
        val result = LinkedHashMap(existing)
        if (old != null) {
            require(old.geometry == next.geometry) { "Geometry cannot change" }
            val from = definition(checkedJson(old.ruleJson, 256 * 1024))
            require(from.matches(existing, old.geometry)) { "Old type no longer matches" }
            from.removeTags.forEach { (key, value) ->
                if (value == "*" || result[key] == value) result.remove(key)
            }
        }
        to.addTags.forEach { (key, value) ->
            if (value != "*") result[key] = value
            else if (result[key].isNullOrEmpty() || result[key] == "*") result[key] = "yes"
        }
        if (next.geometry == OsmPresetGeometry.AREA && "line" in to.sourceGeometry && "area" !in to.addTags) result["area"] = "yes"
        require(result.none { (key, value) -> '*' in key || value == "*" }) { "Literal wildcard cannot be uploaded" }
        return immutableMap(result.toSortedMap())
    }
}

private fun definition(json: JSONObject): OsmPresetDefinition {
    val id = json.getString("id").also { require(it.isNotBlank() && !it.startsWith("@") && it.length <= 500) }
    val name = json.getString("name").also { require(it.isNotBlank() && !it.startsWith('{')) }
    val geometry = strings(json.getJSONArray("geometry")).map { token -> OsmPresetGeometry.entries.single { it.token == token } }
    require(geometry.isNotEmpty() && geometry.distinct().size == geometry.size)
    val sourceGeometry = strings(json.getJSONArray("sourceGeometry"))
    require(geometry.all { it.token in sourceGeometry })
    val tags = tags(json.getJSONObject("tags"), match = true)
    val addTags = tags(json.getJSONObject("addTags"))
    val removeTags = tags(json.getJSONObject("removeTags"))
    val score = json.getDouble("matchScore").also { require(it.isFinite() && it >= 0) }
    val location = json.optJSONObject("locationSet")?.let(::canonical)
    val aliases = json.getJSONObject("aliases")
    val terms = json.getJSONObject("terms")
    val selectable = json.getBoolean("selectable") && json.getBoolean("searchable") && addTags.isNotEmpty()
    return OsmPresetDefinition(id, name, json.getString("sourceName"),
        if (json.isNull("trName")) null else json.getString("trName"),
        strings(aliases.getJSONArray("tr")), strings(aliases.getJSONArray("en")),
        immutableList(strings(terms.getJSONArray("tr")) + strings(terms.getJSONArray("en"))),
        immutableList(geometry), sourceGeometry, tags, addTags, removeTags,
        json.getBoolean("searchable"), selectable, score, location, json.getBoolean("requiresLocationValidation"),
        json.getString("normalizedSearch"), canonical(json))
}

private fun tags(json: JSONObject, match: Boolean = false): Map<String, String> {
    require(json.length() <= 256)
    val result = LinkedHashMap<String, String>()
    for (key in json.keys().asSequence().toList().sorted()) {
        val value = json.get(key)
        require(value is String && value.isNotEmpty() && value.codePointCount(0, value.length) <= 255 &&
            value.none { it.code < 32 || it.code == 127 } && (value == "*" || '*' !in value))
        require(key.isNotEmpty() && key.codePointCount(0, key.length) <= 255 && key.none { it.code <= 32 || it.code == 127 })
        require('*' !in key || match && key.matches(Regex("[^*]+:\\*")) && value == "*")
        result[key] = value
    }
    return immutableMap(result)
}

private fun strings(array: JSONArray): List<String> {
    require(array.length() <= 1000)
    return immutableList((0 until array.length()).map { array.getString(it).also { value -> require(value.length <= 16_384) } })
}

private fun checkedJson(raw: String, maxBytes: Int): JSONObject {
    require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes)
    var depth = 0; var quoted = false; var escaped = false
    for (character in raw) {
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 24) }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
    }
    require(!quoted && depth == 0)
    return JSONObject(raw)
}

private fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
private fun normalize(value: String): String = Normalizer.normalize(value.replace('İ', 'i').replace('I', 'ı').lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "").replace('ı', 'i').replace(Regex("\\s+"), " ").trim()
private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
