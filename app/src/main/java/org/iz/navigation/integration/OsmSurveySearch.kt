package org.iz.navigation.integration

import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.data.MapPlacePreset
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SurveyVenue
import org.iz.navigation.integration.osm.OsmUnsupportedCorrection
import org.iz.navigation.integration.osm.osmCorrectionPreset
import org.iz.navigation.offline.OfflineJsonDepth
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import org.json.JSONArray
import org.json.JSONObject

data class OsmSurveySnapshot(
    val origin: WeatherCoordinate,
    val venues: List<SurveyVenue>,
    val refreshedAt: Long,
    val endpoint: String,
    val capped: Boolean,
)

/** Query geometry is a conservative superset. Exact distance and eligibility are checked locally. */
internal fun osmSurveyQuery(origin: WeatherCoordinate): String {
    val filters = MapPlacePreset.entries.groupBy { it.key }.flatMap { (key, presets) ->
        val values = presets.joinToString("|") { it.value }
        listOf("node[\"$key\"~\"^($values)$\"];", "way[\"$key\"~\"^($values)$\"];")
    }.joinToString("")
    // https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL#Print_(out)
    return "[out:json][timeout:12][maxsize:16777216][bbox:${surveyBox(origin)}];($filters);out meta center 100;"
}

private fun surveyBox(origin: WeatherCoordinate): String {
    val angular = 1_000.0 / 6_371_000.0
    val latitudeDelta = Math.toDegrees(angular)
    val south = floor((origin.latitude - latitudeDelta).coerceAtLeast(-90.0) * 100) / 100
    val north = ceil((origin.latitude + latitudeDelta).coerceAtMost(90.0) * 100) / 100
    val reachesPole = origin.latitude - latitudeDelta <= -90 || origin.latitude + latitudeDelta >= 90
    val longitudeDelta = if (reachesPole) 180.0 else
        Math.toDegrees(asin((sin(angular) / cos(Math.toRadians(origin.latitude))).coerceIn(-1.0, 1.0)))
    val roundedWest = floor((origin.longitude - longitudeDelta) * 100) / 100
    val roundedEast = ceil((origin.longitude + longitudeDelta) * 100) / 100
    // Overpass treats west > east as a box crossing the date line.
    val west = if (reachesPole) -180.0 else if (roundedWest < -180) roundedWest + 360 else roundedWest
    val east = if (reachesPole) 180.0 else if (roundedEast > 180) roundedEast - 360 else roundedEast
    return String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", south, west, north, east)
}

/** Only explicit caller-owned scans enter the existing process mutex and budgeted transport. */
internal class OsmSurveySearch(
    private val mutex: Mutex,
    private val endpoint: () -> String,
    private val readCached: (String) -> String?,
    private val writeCache: (String, String) -> Unit,
    private val execute: suspend (query: String, endpoint: String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun cachedSurvey(accountKey: String?): OsmSurveySnapshot? = mutex.withLock {
        val context = coroutineContext
        val configured = endpoint()
        fun checkCurrent() {
            context.ensureActive()
            if (endpoint() != configured) throw CancellationException("Harita servisi değişti.")
        }
        checkCurrent()
        val snapshot = try {
            val box = readCached(cacheKey(accountKey, configured, null)) ?: return@withLock null
            val json = readCached(cacheKey(accountKey, configured, box)) ?: return@withLock null
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_SURVEY_CACHE_BYTES)
            val saved = JSONObject(OfflineJsonDepth.requireSafe(json))
            require(saved.integral("schema") == 1L && saved.getString("endpoint") == configured)
            require(saved.opt("accountKey") == (accountKey ?: JSONObject.NULL))
            val origin = WeatherCoordinate(saved.number("latitude"), saved.number("longitude"))
            require(surveyBox(origin) == box && saved.getString("bbox") == box)
            val refreshedAt = saved.integral("refreshedAt")
            require(refreshedAt in 0..now())
            parseSurveySnapshot(saved.getString("data"), origin, refreshedAt, configured)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        checkCurrent()
        snapshot
    }

    suspend fun surveyNearby(
        origin: WeatherCoordinate,
        accountKey: String?,
        isCurrent: () -> Boolean,
    ): OsmSurveySnapshot = mutex.withLock {
        val context = coroutineContext
        val configured = endpoint()
        fun checkCurrent() {
            context.ensureActive()
            if (!isCurrent() || endpoint() != configured) throw CancellationException("Saha isteği değişti.")
        }
        checkCurrent()
        val json = execute(osmSurveyQuery(origin), configured)
        checkCurrent()
        val refreshedAt = now()
        val snapshot = parseSurveySnapshot(json, origin, refreshedAt, configured)
        val box = surveyBox(origin)
        val saved = JSONObject().put("schema", 1).put("endpoint", configured)
            .put("accountKey", accountKey ?: JSONObject.NULL)
            .put("latitude", origin.latitude).put("longitude", origin.longitude)
            .put("bbox", box).put("refreshedAt", refreshedAt).put("data", json).toString()
        require(saved.toByteArray(Charsets.UTF_8).size <= MAX_SURVEY_CACHE_BYTES)
        checkCurrent()
        writeCache(cacheKey(accountKey, configured, box), saved)
        checkCurrent()
        writeCache(cacheKey(accountKey, configured, null), box)
        checkCurrent()
        snapshot
    }

    private fun cacheKey(accountKey: String?, configured: String, box: String?): String =
        "osm-survey:v1:" + JSONArray().put(accountKey ?: JSONObject.NULL).put(configured).put(box ?: JSONObject.NULL)
}

// Raw response remains capped at 512000 bytes; JSON string escaping and metadata need extra disk space.
internal const val MAX_SURVEY_CACHE_BYTES = 1_040_384

private fun parseSurveySnapshot(
    json: String,
    origin: WeatherCoordinate,
    refreshedAt: Long,
    endpoint: String,
): OsmSurveySnapshot {
    require(json.toByteArray(Charsets.UTF_8).size <= 512_000)
    val root = JSONObject(OfflineJsonDepth.requireSafe(json))
    require(!root.has("remark") || root.opt("remark") == "")
    require(!root.has("error") && !root.has("errors"))
    val elements = root.getJSONArray("elements")
    require(elements.length() <= 100)
    val refs = HashSet<OsmRef>()
    val venues = (0 until elements.length()).mapNotNull { index ->
        val element = elements.getJSONObject(index)
        val type = when (element.getString("type")) {
            "node" -> OsmType.NODE
            "way" -> OsmType.WAY
            "relation" -> return@mapNotNull null
            else -> error("Geçersiz OSM nesnesi.")
        }
        val id = element.integral("id")
        val version = element.integral("version")
        require(id > 0 && version in 1 until Long.MAX_VALUE)
        val ref = OsmRef(type, id)
        require(refs.add(ref))
        val position = if (type == OsmType.NODE) element else element.getJSONObject("center")
        val latitude = position.number("lat")
        val longitude = position.number("lon")
        require(validCoordinate(latitude, longitude))
        val tagsJson = element.getJSONObject("tags")
        val tags = tagsJson.keys().asSequence().associateWith { key ->
            (tagsJson.get(key) as? String) ?: error("Geçersiz OSM etiketi.")
        }
        val nodes = if (type == OsmType.NODE) emptyList() else element.getJSONArray("nodes").let { values ->
            (0 until values.length()).map { i -> integral(values.get(i)).also { require(it > 0) } }
        }
        val preset = try {
            osmCorrectionPreset(type, nodes, tags)
        } catch (_: OsmUnsupportedCorrection) {
            return@mapNotNull null
        }
        val name = listOf("name:tr", "name", "brand").firstNotNullOfOrNull { tags[it]?.takeIf(String::isNotBlank) }
            ?: preset.label
        SurveyVenue(ref, version, name, latitude, longitude, tags)
    }.map { venue ->
        venue to WeatherEngine.distanceMeters(origin, WeatherCoordinate(venue.latitude, venue.longitude))
    }.filter { it.second <= 1_000.0 }.sortedBy { it.second }.map { it.first }
    return OsmSurveySnapshot(origin, venues, refreshedAt, endpoint, elements.length() == 100)
}

private fun JSONObject.number(key: String): Double = (get(key) as? Number)?.toDouble()?.also {
    require(it.isFinite())
} ?: error("Geçersiz koordinat.")

private fun JSONObject.integral(key: String): Long = integral(get(key))
private fun integral(value: Any): Long = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("Geçersiz OSM kimliği veya sürümü.")
}
