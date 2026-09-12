package org.iz.navigation.speed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.*
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class PostedSpeed(val kmh: Double, val position: WeatherCoordinate)

/** Memory-only observations; endpoint and HTTP exceptions never escape with a credential-bearing URL. */
internal class TomTomReverseSpeedClient(
    private val credentials: () -> TrafficCredentials,
    endpoint: String = "https://api.tomtom.com/search/2/reverseGeocode/",
    client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val gate: TrafficRequestGate = sharedTrafficRequestGate,
) {
    private val endpoint = endpoint.toHttpUrl().also {
        require(it.toString() == "https://api.tomtom.com/search/2/reverseGeocode/" || it.host in setOf("localhost", "127.0.0.1", "::1"))
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val client = singleAttemptHttpClient(client.newBuilder().apply {
        cache(null); interceptors().clear(); networkInterceptors().clear(); eventListener(EventListener.NONE)
        connectTimeout(5, TimeUnit.SECONDS); readTimeout(8, TimeUnit.SECONDS); callTimeout(9, TimeUnit.SECONDS)
    }.build())
    private var blockedRevision: String? = null
    private var lastAttempt = Long.MIN_VALUE

    suspend fun lookup(fix: NavigationFix, road: RoadMatch, revision: String, stillCurrent: () -> Boolean): PostedSpeed? = withContext(Dispatchers.IO) {
        val settings = credentials()
        if (!settings.speedFallbackEnabled || !settings.freeAccountVerified || settings.apiKey.isNullOrBlank() ||
            revision != settings.revision || blockedRevision == revision ||
            lastAttempt != Long.MIN_VALUE && clock() - lastAttempt < 10_000) return@withContext null
        val heading = fix.bearingDegrees?.takeIf { it.isFinite() && it in 0f..360f }?.toDouble()
            ?: bearing(road.road.points[road.segment], road.road.points[road.segment + 1]).let { if (road.forward) it else (it + 180) % 360 }
        fun authorize() {
            val current = credentials()
            check(current.revision == revision && current.speedFallbackEnabled && current.freeAccountVerified &&
                current.apiKey == settings.apiKey && stillCurrent() && speedFixFresh(fix, clock()))
        }
        try {
            gate.request(clock, ::authorize) {
                authorize()
                lastAttempt = clock()
                val url = endpoint.newBuilder().addPathSegment("${fix.coordinate.latitude},${fix.coordinate.longitude}.json")
                    .addQueryParameter("key", settings.apiKey)
                    .addQueryParameter("returnSpeedLimit", "true")
                    .addQueryParameter("returnMatchType", "true")
                    .addQueryParameter("heading", String.format(Locale.US, "%.1f", heading))
                    .addQueryParameter("radius", "20")
                    .addQueryParameter("language", "tr-TR").build()
                authorize()
                val response = executeBounded(client, weatherRequest(url.toString()).header("Cache-Control", "no-store").build(), 64_000)
                if (response.code == 403) blockedRevision = revision
                if (response.code == 429 || response.code == 503) gate.coolDown(clock(), response.retryAfter)
                if (!response.successful || !stillCurrent() || credentials().revision != revision) null
                else parsePostedSpeed(response.body, fix, road)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

internal fun parsePostedSpeed(json: String, fix: NavigationFix, road: RoadMatch): PostedSpeed? = runCatching {
    val addresses = JSONObject(json).getJSONArray("addresses")
    if (addresses.length() != 1) return null
    val result = addresses.getJSONObject(0)
    val address = result.getJSONObject("address")
    if (result.optString("matchType", address.optString("matchType")) != "Street") return null
    val coordinates = result.getString("position").split(',').map { it.trim().toDouble() }
    if (coordinates.size != 2) return null
    val position = WeatherCoordinate(coordinates[0], coordinates[1])
    if (distance(position, fix.coordinate) > 25) return null
    if (road.road.points.zipWithNext().minOf { (a, b) -> project(position, a, b, 0).distance } > 12) return null
    fun normalize(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    val roadNames = listOfNotNull(road.road.tags["name"], road.road.tags["name:tr"]).map(::normalize).filter(String::isNotEmpty)
    val street = normalize(address.optString("streetName"))
    val roadRefs = road.road.tags["ref"].orEmpty().split(';').map(::normalize).filter(String::isNotEmpty)
    val numbers = address.optJSONArray("routeNumbers")
    val refs = if (numbers == null) emptyList() else (0 until numbers.length()).map { normalize(numbers.getString(it)) }
    if (!(street.isNotEmpty() && street in roadNames || roadRefs.any { it in refs })) return null
    val raw = address.optString("speedLimit", result.optString("speedLimit")).trim().lowercase(Locale.ROOT)
    val parsed = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(kph|km/h|kmh|mph)$").matchEntire(raw) ?: return null
    val kmh = parsed.groupValues[1].toDouble() * if (parsed.groupValues[2] == "mph") 1.609344 else 1.0
    if (!kmh.isFinite() || kmh <= 0 || kmh > 400) return null
    PostedSpeed(kmh, position)
}.getOrNull()
