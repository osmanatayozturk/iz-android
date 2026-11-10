package org.iz.navigation.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal val partialNameTags = listOf("name", "name:tr", "short_name", "alt_name", "official_name")

/** Literal tokens only. Server filtering is a bounded superset; the phone checks every token again. */
internal class PartialNameMatcher(query: String) {
    private val words = tokens(normalizeNominatimQuery(query))
    val canSearchNearby = query.count(Char::isLetterOrDigit) >= 3 && words.isNotEmpty()

    fun matches(name: String): Boolean {
        val candidate = tokens(name)
        var next = 0
        for ((index, word) in words.withIndex()) {
            if (index == words.lastIndex && isInitials(word)) {
                for (start in next until candidate.size) {
                    if (candidate[start].startsWith(word)) return true
                    val initials = candidate.drop(start).filterNot { it in stopWords }.take(word.length)
                    if (initials.size == word.length && initials.map { it.first() }.joinToString("") == word) return true
                }
                return false
            }
            val found = (next until candidate.size).firstOrNull { candidate[it].startsWith(word) } ?: return false
            next = found + 1
        }
        return words.isNotEmpty()
    }

    fun overpassQuery(box: DirectionsViewbox): String {
        check(canSearchNearby)
        // At most six tokens and eight wildcards. POSIX regex.h may operate on UTF-8 bytes:
        // use literal alternatives, never a multibyte bracket class or Unicode escape.
        // Deliberately admit substrings here; token boundaries and initials are checked on phone.
        val selected = words.take(6)
        val patterns = selected.mapIndexed { index, word ->
            val literal = word.map(::letterPattern).joinToString("")
            if (index == words.lastIndex && isInitials(word)) {
                val initials = word.map(::letterPattern).joinToString(".*")
                "($literal|$initials)"
            } else literal
        }
        val regex = patterns.joinToString(".*")
        val escaped = regex.replace("\\", "\\\\").replace("\"", "\\\"")
        return "[out:json][timeout:12];nwr(${box.overpassValue})[~\"^(name|name:tr|short_name|alt_name|official_name)$\"~\"$escaped\",i];out center tags 50;"
    }

    private fun isInitials(word: String) = word.length in 2..4 && word.all(Char::isLetter)
    private fun letterPattern(letter: Char): String = when (letter) {
        'a' -> "(a|A|â|Â)"; 'c' -> "(c|C|ç|Ç)"; 'g' -> "(g|G|ğ|Ğ)"; 'i' -> "(i|I|ı|İ|î|Î)"
        'o' -> "(o|O|ö|Ö)"; 's' -> "(s|S|ş|Ş)"; 'u' -> "(u|U|ü|Ü|û|Û)"; else -> letter.toString()
    }

    companion object {
        private val stopWords = setOf("ve", "ile")
        private fun tokens(value: String): List<String> {
            val folded = Normalizer.normalize(value.lowercase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return Regex("[\\p{L}\\p{N}]+").findAll(folded).map { it.value }.toList()
        }
    }
}

/** Shares the existing Overpass gate and transport. No provider, retry or independent budget. */
internal class PartialNameOverpassSearch(
    private val mutex: Mutex,
    private val endpoint: () -> String,
    private val readCached: (String) -> String?,
    private val writeCache: (String, String) -> Unit,
    private val execute: suspend (query: String, endpoint: String) -> String,
) {
    suspend fun search(query: String, box: DirectionsViewbox, origin: WeatherCoordinate?): List<SelectedOsmPlace> = mutex.withLock {
        val context = coroutineContext
        val matcher = PartialNameMatcher(query)
        if (!matcher.canSearchNearby) return@withLock emptyList()
        val configured = endpoint()
        fun checkCurrent() {
            context.ensureActive()
            if (endpoint() != configured) throw CancellationException("Harita servisi değişti; eski yanıt iptal edildi.")
        }
        checkCurrent()
        val key = "directions-partial:v1:$configured:${normalizeNominatimQuery(query)}:${box.value}"
        val cached = readCached(key)?.let { json -> runCatching { parsePartialNamePlaces(json, matcher) }.getOrNull() }
        checkCurrent()
        val json = if (cached == null) execute(matcher.overpassQuery(box), configured) else null
        checkCurrent()
        val places = cached ?: parsePartialNamePlaces(requireNotNull(json), matcher)
        val result = rankDirectionsPlaces(places.distinctBy { it.osmRef }, origin).take(20)
        checkCurrent()
        if (json != null) writeCache(key, json)
        checkCurrent()
        result
    }
}

internal fun parsePartialNamePlaces(json: String, matcher: PartialNameMatcher): List<SelectedOsmPlace> {
    require(json.toByteArray(Charsets.UTF_8).size <= 512_000)
    val root = JSONObject(json)
    require(!root.has("remark") || root.opt("remark") == "") { "Harita sorgusu tamamlanamadı." }
    require(!root.has("error") && !root.has("errors")) { "Harita sorgusu tamamlanamadı." }
    val elements = requireNotNull(root.optJSONArray("elements")) { "Harita yanıtı eksik." }
    require(elements.length() <= 50) { "Harita yanıtı aday sınırını aştı." }
    return (0 until elements.length()).mapNotNull { index ->
        val item = elements.getJSONObject(index)
        val type = when (item.getString("type")) {
            "node" -> OsmType.NODE; "way" -> OsmType.WAY; "relation" -> OsmType.RELATION
            else -> throw IllegalArgumentException("Geçersiz harita nesnesi.")
        }
        val rawId = item.get("id")
        require(rawId is Number && rawId.toDouble().isFinite() && rawId.toDouble() == rawId.toLong().toDouble())
        val id = rawId.toLong().also { require(it > 0) }
        val position = if (type == OsmType.NODE) item else item.getJSONObject("center")
        require(position.opt("lat") is Number && position.opt("lon") is Number)
        val lat = position.getDouble("lat"); val lon = position.getDouble("lon")
        require(validCoordinate(lat, lon)) { "Geçersiz harita koordinatı." }
        val tags = item.getJSONObject("tags")
        require(tags.keys().asSequence().all { tags.get(it) is String })
        fun tag(key: String) = (tags.opt(key) as? String)?.takeIf { it.isNotBlank() }
        val names = partialNameTags.flatMap { tag(it)?.split(';').orEmpty() }
        require(names.isNotEmpty()) { "Harita nesnesinin adı eksik." }
        if (names.none(matcher::matches)) return@mapNotNull null
        val name = tag("name:tr") ?: tag("name") ?: names.first()
        val street = listOfNotNull(tag("addr:street"), tag("addr:housenumber")).joinToString(" ")
        val address = tag("addr:full") ?: listOfNotNull(street.takeIf(String::isNotBlank), tag("addr:district"),
            tag("addr:city"), tag("addr:province")).joinToString(", ").takeIf(String::isNotBlank)
        SelectedOsmPlace(name, lat, lon, OsmRef(type, id), address)
    }
}
