package com.atay.iz.integration

import android.content.Context
import com.atay.iz.BuildConfig
import com.atay.iz.data.OsmRef
import com.atay.iz.data.OsmType
import com.atay.iz.data.SelectedOsmPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
import kotlin.coroutines.coroutineContext

internal val osmUserAgent: String get() = "Iz/${BuildConfig.VERSION_NAME} (com.atay.iz)"

/** Called only by an explicit search submission or map tap, never by map movement. */
class OsmPlaces(context: Context) {
    private val app = context.applicationContext
    private val settings = OsmServiceSettings(app)
    private val preferences = app.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE)
    private val cacheDirectory = File(app.cacheDir, "osm_place_queries")

    suspend fun search(query: String): List<SelectedOsmPlace> = withContext(Dispatchers.IO) {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 2) return@withContext emptyList()
        require(normalized.length <= 200) { "Arama en fazla 200 karakter olabilir." }
        nominatimLock.withLock {
            val endpoint = settings.read().nominatim
            val key = "search:$endpoint:${normalized.lowercase(Locale.ROOT)}"
            cached(key, 86_400_000, ::parseNominatimPlaces)?.let { return@withLock it }
            var now = System.currentTimeMillis()
            if (now < preferences.getLong("nominatim_cooldown", 0)) throw OsmServiceException("Yer arama servisi beklememizi istiyor. Biraz sonra yeniden ara.")
            val wait = (preferences.getLong("nominatim_last_request", 0) + 1_000 - now).coerceIn(0, 1_000)
            if (wait > 0) delay(wait)
            coroutineContext.ensureActive()
            now = System.currentTimeMillis()
            check(preferences.edit().putLong("nominatim_last_request", now).commit()) { "Arama sınırı kaydedilemedi." }
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("q", normalized).addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", "10").addQueryParameter("addressdetails", "0").build()
            val request = readRequest(url.toString()).get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 429 || response.code == 406) {
                    preferences.edit().putLong("nominatim_cooldown", System.currentTimeMillis() + retryAfterMillis(response)).commit()
                    throw OsmServiceException("Yer arama servisi beklememizi istiyor. Biraz sonra yeniden ara.")
                }
                if (!response.isSuccessful) throw OsmServiceException("Yer araması şu an kullanılamıyor (${response.code}).")
                val json = readLimited(response, 512_000) {}
                val result = parseNominatimPlaces(json)
                storeCache(key, json)
                result
            }
        }
    }

    suspend fun nearby(latitude: Double, longitude: Double): List<SelectedOsmPlace> = withContext(Dispatchers.IO) {
        require(validCoordinate(latitude, longitude)) { "Geçersiz harita koordinatı." }
        overpassLock.withLock {
            val endpoint = settings.read().overpass
            // A ten metre cache cell prevents repeated taps on the same visible POI from re-querying.
            val lat = String.format(Locale.US, "%.4f", latitude)
            val lon = String.format(Locale.US, "%.4f", longitude)
            val key = "nearby:$endpoint:$lat:$lon"
            cached(key, 600_000, ::parseOverpassPlaces)?.let { return@withLock it }
            val now = System.currentTimeMillis()
            var budget = OsmReadBudget.fromJson(preferences.getString("overpass_budget", null))
            if (!budget.canRequest(now)) {
                throw OsmServiceException(if (now < budget.cooldownUntil) "Harita servisi beklememizi istiyor. Biraz sonra yeniden dokun." else "Bugünkü yakın yer arama sınırına ulaşıldı. Kayıtlı yerlerini kullanabilir veya uzun basarak yer ekleyebilirsin.")
            }
            val maxResponseBytes = minOf(512_000, 9_000_000 - budget.currentBytes(now))
            budget = budget.startRequest(now, reservedBytes = maxResponseBytes)
            check(preferences.edit().putString("overpass_budget", budget.toJson()).commit()) { "Harita sorgu sınırı kaydedilemedi." }
            val query = "[out:json][timeout:15][maxsize:16777216];nwr(around:100,$lat,$lon)[~\"^(amenity|shop|tourism|leisure|office|craft|historic)$\"~\".\"];out center tags qt;"
            val request = readRequest(endpoint).post(FormBody.Builder().add("data", query).build()).build()
            var downloaded = 0L
            try {
                client.newCall(request).execute().use { response ->
                    val coolingDown = response.code == 429 || response.code == 406
                    if (coolingDown) {
                        budget = budget.withCooldown(System.currentTimeMillis(), retryAfterMillis(response) / 1_000)
                        check(preferences.edit().putString("overpass_budget", budget.toJson()).commit()) { "Harita bekleme süresi kaydedilemedi." }
                    }
                    val json = readLimited(response, maxResponseBytes) { downloaded += it }
                    if (coolingDown) throw OsmServiceException("Harita servisi beklememizi istiyor. Biraz sonra yeniden dokun.")
                    if (!response.isSuccessful) throw OsmServiceException("Yakındaki yerler şu an alınamıyor (${response.code}).")
                    val result = parseOverpassPlaces(json)
                    storeCache(key, json)
                    result
                }
            } finally {
                // A killed process retains the reservation; completed attempts release only unused bytes.
                budget = budget.finishResponse(maxResponseBytes, downloaded)
                preferences.edit().putString("overpass_budget", budget.toJson()).commit()
            }
        }
    }

    private fun cached(key: String, maxAge: Long, parse: (String) -> List<SelectedOsmPlace>): List<SelectedOsmPlace>? =
        readValidCachedPlaces(File(cacheDirectory, cacheKey(key)), System.currentTimeMillis(), maxAge, parse)

    private fun storeCache(key: String, json: String) {
        runCatching {
            cacheDirectory.mkdirs()
            val target = File(cacheDirectory, cacheKey(key))
            val temporary = File(cacheDirectory, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                temporary.writeText(json)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
            cacheDirectory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(128)?.forEach { it.delete() }
        }
    }

    private fun readRequest(url: String) = Request.Builder().url(url)
        .header("User-Agent", osmUserAgent).header("Accept", "application/json").header("Accept-Language", "tr")

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
        SelectedOsmPlace(name, latitude, longitude, osmRef(item.optString("osm_type"), item.optLong("osm_id")))
    }
}

internal fun parseOverpassPlaces(json: String): List<SelectedOsmPlace> {
    val array = JSONObject(json).optJSONArray("elements") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val ref = osmRef(item.optString("type"), item.optLong("id")) ?: return@mapNotNull null
        val location = if (ref.type == OsmType.NODE) item else item.optJSONObject("center") ?: return@mapNotNull null
        val latitude = location.optDouble("lat", Double.NaN)
        val longitude = location.optDouble("lon", Double.NaN)
        if (!validCoordinate(latitude, longitude)) return@mapNotNull null
        val tags = item.optJSONObject("tags") ?: return@mapNotNull null
        val name = listOf("name:tr", "name", "brand", "amenity", "shop", "tourism", "leisure", "office", "craft", "historic")
            .firstNotNullOfOrNull { tags.optString(it).takeIf(String::isNotBlank) } ?: return@mapNotNull null
        SelectedOsmPlace(name.replace('_', ' '), latitude, longitude, ref)
    }.distinctBy { it.osmRef }
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

private fun osmRef(type: String, id: Long): OsmRef? {
    if (id <= 0) return null
    val kind = when (type) { "node" -> OsmType.NODE; "way" -> OsmType.WAY; "relation" -> OsmType.RELATION; else -> return null }
    return OsmRef(kind, id)
}

internal fun validCoordinate(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun cacheKey(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

private fun retryAfterMillis(response: Response): Long {
    val value = response.header("Retry-After")
    val seconds = value?.toLongOrNull()
    val dateDelay = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrNull()
    return (seconds?.let { it.coerceAtMost(86_400) * 1_000 } ?: dateDelay ?: 30_000).coerceIn(30_000, 86_400_000)
}

private fun readLimited(response: Response, maxBytes: Long, onBytes: (Long) -> Unit): String {
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
