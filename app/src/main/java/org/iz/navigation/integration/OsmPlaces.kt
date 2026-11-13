package org.iz.navigation.integration

import android.content.Context
import org.iz.navigation.BuildConfig
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.WeatherCoordinate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal val osmUserAgent: String get() = "Iz/${BuildConfig.VERSION_NAME} (org.iz.navigation)"

/** Called only by an explicit search submission or map tap, never by map movement. */
class OsmPlaces(context: Context) {
    private val app = context.applicationContext
    private val settings = OsmServiceSettings(app)
    private val preferences = app.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE)
    private val editorCatalog by lazy { OsmPresetCatalog.load(app.assets.open(OsmPresetCatalog.BUNDLED_ASSET)) }
    private val editorMatchKeys by lazy { osmEditorMatchKeys(editorCatalog) }
    private val cacheDirectory = File(app.cacheDir, "osm_place_queries")
    private val surveyCacheDirectory = File(app.cacheDir, "osm_survey_queries")
    private val overpassRead = OverpassRead(
        readBudget = { OsmReadBudget.fromJson(preferences.getString("overpass_budget", null)) },
        writeBudget = { preferences.edit().putString("overpass_budget", it.toJson()).commit() },
        execute = { client.newCall(it).execute() },
    )

    private val nominatimSearch = NominatimSearch(
        mutex = nominatimLock,
        endpoint = { settings.read().nominatim },
        readCached = { cachedJson(it, 86_400_000) },
        writeCache = ::storeCache,
        readLong = { preferences.getLong(it, 0) },
        writeLong = { key, value -> preferences.edit().putLong(key, value).commit() },
        execute = { client.newCall(it).execute() },
    )

    private val partialNameSearch = PartialNameOverpassSearch(
        mutex = overpassLock, endpoint = { settings.read().overpass },
        readCached = { cachedJson(it, 86_400_000) }, writeCache = ::storeCache,
        execute = { query, endpoint -> performOverpass(query, endpoint) },
    )
    private val directionsSearch = DirectionsPlaceSearch(nominatimSearch, partialNameSearch)
    private val surveySearch = OsmSurveySearch(
        mutex = overpassLock, endpoint = { settings.read().overpass },
        readCached = ::cachedSurveyJson,
        writeCache = { key, json -> storeCacheIn(surveyCacheDirectory, key, json) },
        execute = { query, endpoint -> performOverpass(query, endpoint) },
    )

    internal suspend fun cachedSurvey(accountKey: String?): OsmSurveySnapshot? = withContext(Dispatchers.IO) {
        surveySearch.cachedSurvey(accountKey)
    }

    internal suspend fun surveyNearby(
        origin: WeatherCoordinate,
        accountKey: String?,
        isCurrent: () -> Boolean,
    ): OsmSurveySnapshot = withContext(Dispatchers.IO) {
        surveySearch.surveyNearby(origin, accountKey, isCurrent)
    }

    suspend fun search(query: String): List<SelectedOsmPlace> = withContext(Dispatchers.IO) {
        nominatimSearch.search(query)
    }

    internal suspend fun searchForDirections(
        query: String,
        location: suspend () -> WeatherCoordinate?,
    ): List<SelectedOsmPlace> = directionsSearch.search(query, location)

    suspend fun nearby(latitude: Double, longitude: Double): List<SelectedOsmPlace> = withContext(Dispatchers.IO) {
        require(validCoordinate(latitude, longitude)) { "Geçersiz harita koordinatı." }
        overpassLock.withLock {
            val endpoint = settings.read().overpass
            // A ten metre cache cell prevents repeated taps on the same visible POI from re-querying.
            val lat = String.format(Locale.US, "%.4f", latitude)
            val lon = String.format(Locale.US, "%.4f", longitude)
            val key = "nearby:editor-v2:${OsmPresetCatalog.BUNDLED_SHA256}:$endpoint:$lat:$lon"
            cached(key, 600_000) { parseOverpassPlaces(it, editorCatalog) }?.let { return@withLock it }
            val query = osmEditorNearbyQuery(latitude, longitude, editorMatchKeys)
            val json = performOverpass(query)
            val result = parseOverpassPlaces(json, editorCatalog)
            storeCache(key, json)
            result
        }
    }

    /** Explicit user action only; never called by GPS polling or map movement.
     * Proximity is not a claim that a stop is accessible from this road or has a known detour.
     */
    suspend fun routeFuelAndRest(route: PlannedRoute, elapsedSeconds: Double): List<RouteCorridorPlace> =
        withContext(Dispatchers.IO) {
            val slice = RouteCorridorPlaces.slice(route, elapsedSeconds) ?: return@withContext emptyList()
            val query = RouteCorridorPlaces.query(slice)
            overpassLock.withLock {
                coroutineContext.ensureActive()
                val endpoint = settings.read().overpass
                val key = "route-stops:v1:$endpoint:$query"
                val file = File(cacheDirectory, cacheKey(key))
                val cached = runCatching {
                    if (!file.isFile || file.length() > 512_000 ||
                        System.currentTimeMillis() - file.lastModified() !in 0..600_000) null
                    else RouteCorridorPlaces.parseTyped(file.readText())
                }.getOrNull()
                val fresh = cached == null
                val json = if (fresh) performOverpass(query, endpoint) else null
                coroutineContext.ensureActive()
                if (settings.read().overpass != endpoint)
                    throw kotlinx.coroutines.CancellationException("Harita servisi değişti; eski yanıt iptal edildi.")
                val typed = cached ?: RouteCorridorPlaces.parseTyped(requireNotNull(json))
                val kinds = typed.associate { requireNotNull(it.place.osmRef) to it.kind }
                val result = RouteCorridorPlaces.project(slice, typed.map { it.place }, kinds)
                coroutineContext.ensureActive()
                if (fresh) storeCache(key, requireNotNull(json))
                result
            }
        }

    /** Roads and POIs use the same process lock, persisted query count and byte reservation. */
    internal suspend fun roadNetwork(query: String): String = withContext(Dispatchers.IO) {
        overpassLock.withLock { performOverpass(query) }
    }

    private suspend fun performOverpass(query: String, endpoint: String = settings.read().overpass): String =
        overpassRead.query(query, endpoint)
    private fun cachedJson(key: String, maxAge: Long): String? =
        readValidCachedJson(File(cacheDirectory, cacheKey(key)), System.currentTimeMillis(), maxAge)
    private fun cachedSurveyJson(key: String): String? = runCatching {
        val file = File(surveyCacheDirectory, cacheKey(key))
        if (!file.isFile || file.length() !in 1..MAX_SURVEY_CACHE_BYTES.toLong()) null else file.readText()
    }.getOrNull()
    private fun cached(key: String, maxAge: Long, parse: (String) -> List<SelectedOsmPlace>): List<SelectedOsmPlace>? =
        readValidCachedPlaces(File(cacheDirectory, cacheKey(key)), System.currentTimeMillis(), maxAge, parse)

    private fun storeCache(key: String, json: String) {
        runCatching { storeCacheIn(cacheDirectory, key, json) }
    }

    private fun storeCacheIn(directory: File, key: String, json: String) {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, cacheKey(key))
        val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(128)?.forEach { it.delete() }
    }

    companion object {
        // Shared by every map and search screen in the process; no automatic retries or endpoint rotation.
        private val nominatimLock = Mutex()
        private val overpassLock = Mutex()
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // OkHttp can replay 503 + Retry-After: 0 despite retryOnConnectionFailure(false).
                // A positive internal hint stops that follow-up before it escapes our rate/budget gate.
                if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L) {
                    response.newBuilder().header("Retry-After", "1").build()
                } else response
            }
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }
}

class OsmServiceException(message: String) : IOException(message)

internal data class OsmReadBudget(
    val day: Long = 0,
    val queries: Int = 0,
    val bytes: Long = 0,
    val cooldownUntil: Long = 0,
) {
    private fun forDay(now: Long): OsmReadBudget = if (now / 86_400_000 > day) copy(day = now / 86_400_000, queries = 0, bytes = 0) else this
    fun currentBytes(now: Long): Long = forDay(now).bytes
    fun canRequest(now: Long): Boolean = forDay(now).let { now >= it.cooldownUntil && it.queries < 90 && it.bytes < 9_000_000 }
    fun startRequest(now: Long, reservedBytes: Long = 0): OsmReadBudget {
        check(canRequest(now))
        return forDay(now).let {
            require(reservedBytes in 0..(9_000_000 - it.bytes))
            it.copy(queries = it.queries + 1, bytes = it.bytes + reservedBytes)
        }
    }
    fun finishResponse(reservedBytes: Long, receivedBytes: Long): OsmReadBudget {
        require(reservedBytes in 0..bytes && receivedBytes in 0..reservedBytes)
        return copy(bytes = bytes - reservedBytes + receivedBytes)
    }
    fun withCooldown(now: Long, retryAfterSeconds: Long): OsmReadBudget = copy(cooldownUntil = maxOf(cooldownUntil, now + retryAfterSeconds.coerceIn(30, 86_400) * 1_000))
    fun toJson(): String = JSONObject().put("day", day).put("queries", queries).put("bytes", bytes).put("cooldownUntil", cooldownUntil).toString()
    companion object {
        fun fromJson(json: String?): OsmReadBudget = runCatching {
            val value = JSONObject(json ?: "{}")
            OsmReadBudget(value.optLong("day"), value.optInt("queries"), value.optLong("bytes"), value.optLong("cooldownUntil"))
        }.getOrDefault(OsmReadBudget())
    }
}

internal fun parseNominatimPlaces(json: String): List<SelectedOsmPlace> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val latitude = item.optDouble("lat", Double.NaN)
        val longitude = item.optDouble("lon", Double.NaN)
        if (!validCoordinate(latitude, longitude)) return@mapNotNull null
        val name = item.optString("name").takeIf { it.isNotBlank() } ?: item.optString("display_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SelectedOsmPlace(
            name, latitude, longitude, osmRef(item.optString("osm_type"), item.optLong("osm_id")),
            displayName = (item.opt("display_name") as? String)?.takeIf { it.isNotBlank() },
        )
    }
}

/** Read the identity of the feature actually hit, even while a new source update is still rendering. */
internal fun parseRenderedOsmPlace(json: String): SelectedOsmPlace? = runCatching {
    val feature = JSONObject(json)
    val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
    val properties = feature.getJSONObject("properties")
    val latitude = coordinates.getDouble(1)
    val longitude = coordinates.getDouble(0)
    val ref = osmRef(properties.getString("osmType").lowercase(Locale.ROOT), properties.getLong("osmId"))
    val name = properties.getString("name")
    if (!validCoordinate(latitude, longitude) || ref == null || name.isBlank()) null
    else SelectedOsmPlace(name, latitude, longitude, ref)
}.getOrNull()

internal fun readValidCachedPlaces(
    file: File,
    now: Long,
    maxAge: Long,
    parse: (String) -> List<SelectedOsmPlace>,
): List<SelectedOsmPlace>? = runCatching {
    if (!file.isFile || file.length() > 512_000 || now - file.lastModified() !in 0..maxAge) null
    else parse(file.readText())
}.getOrNull()

internal fun readValidCachedJson(file: File, now: Long, maxAge: Long): String? = runCatching {
    if (!file.isFile || file.length() > 512_000 || now - file.lastModified() !in 0..maxAge) null else file.readText()
}.getOrNull()

private fun cacheKey(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun retryAfterMillis(response: Response): Long {
    val value = response.header("Retry-After")
    val seconds = value?.toLongOrNull()
    val dateDelay = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrNull()
    return (seconds?.let { it.coerceAtMost(86_400) * 1_000 } ?: dateDelay ?: 30_000).coerceIn(30_000, 86_400_000)
}

internal fun readLimited(response: Response, maxBytes: Long, onBytes: (Long) -> Unit): String {
    val body = response.body ?: throw OsmServiceException("Servis boş yanıt verdi.")
    val output = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(8_192)
        while (true) {
            val remaining = maxBytes - output.size()
            if (remaining <= 0) throw OsmServiceException("Servis yanıtı boyut sınırını aştı.")
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            onBytes(count.toLong())
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}
