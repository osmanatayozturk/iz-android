package org.iz.navigation.weather

import android.content.Context
import org.iz.navigation.data.Transport
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ValhallaRoutePlanner(
    private val endpoint: String,
    private val storageDirectory: File,
    client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : RoutePlanner {
    private val client = singleAttemptHttpClient(client)

    constructor(context: Context, endpoint: String = DEFAULT_ROUTE_ENDPOINT) : this(
        endpoint = endpoint,
        storageDirectory = weatherStorage(context, "route-weather-http"),
        client = defaultWeatherHttpClient(),
    )

    override suspend fun plan(
        stops: List<RouteStop>,
        departureAt: Long,
        transport: Transport,
        travelSpeedKmh: Double?,
    ): PlannedRoute = withContext(Dispatchers.IO) {
        require(stops.size in 2..6) { "Rota 2 ile 6 durak içermelidir." }
        val effectiveSpeed = routeTravelSpeedKmh(transport, travelSpeedKmh)
        val costing = when (transport) {
            Transport.CAR, Transport.PASSENGER -> "auto"
            Transport.MOTORCYCLE -> "motorcycle"
            Transport.BICYCLE -> "bicycle"
            Transport.WALK, Transport.RUN -> "pedestrian"
            Transport.UNKNOWN -> throw IllegalArgumentException("Rota için bir yolculuk türü seç.")
        }
        val options = JSONObject().apply {
            when (transport) {
                Transport.MOTORCYCLE -> put("use_trails", 0)
                Transport.BICYCLE -> put("cycling_speed", effectiveSpeed)
                Transport.WALK, Transport.RUN -> put("walking_speed", effectiveSpeed)
                else -> Unit
            }
        }
        val bodyJson = JSONObject()
            .put("locations", JSONArray().apply {
                stops.forEach { stop -> put(JSONObject().put("lat", stop.coordinate.latitude).put("lon", stop.coordinate.longitude)) }
            })
            .put("costing", costing)
            .put("costing_options", JSONObject().put(costing, options))
            .put("units", "kilometers")
            .put("language", "tr-TR")
            .put("directions_type", "instructions")
        val request = weatherRequest(endpoint.toHttpUrl().toString())
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            return@withContext WeatherHttpGate.route(storageDirectory, clock) {
                val response = executeBounded(client, request, MAX_ROUTE_RESPONSE_BYTES)
                if (response.code == 429 || response.code == 503) {
                    WeatherHttpGate.setCooldown(storageDirectory, "route-http-state.json", clock(), response.retryAfter)
                    throw RouteServiceException("Rota servisi beklememizi istiyor. Biraz sonra yeniden deneyin.")
                }
                if (!response.successful) throw RouteServiceException("Rota servisi şu an kullanılamıyor (${response.code}).")
                parseRoute(response.body, stops, clock(), bodyJson.toString(), transport, effectiveSpeed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RouteServiceException) {
            throw error
        } catch (error: BoundedResponseException) {
            throw RouteServiceException("Rota servisi yanıtı çok büyük.", error)
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw RouteServiceException("Rota servisine ulaşılamadı.", error)
        } catch (error: JSONException) {
            throw RouteServiceException("Rota servisi geçersiz yanıt verdi.", error)
        } catch (error: IllegalArgumentException) {
            throw RouteServiceException("Rota servisi geçersiz rota döndürdü.", error)
        }
    }

    private fun parseRoute(
        json: String,
        stops: List<RouteStop>,
        createdAt: Long,
        requestJson: String,
        transport: Transport,
        travelSpeedKmh: Double?,
    ): PlannedRoute {
        val trip = JSONObject(json).getJSONObject("trip")
        val legsJson = trip.getJSONArray("legs")
        require(legsJson.length() == stops.size - 1) { "Eksik rota ayağı." }
        val allVertices = mutableListOf<RouteVertex>()
        val allManeuvers = mutableListOf<RouteManeuver>()
        val stopTimes = mutableListOf(0.0)
        var elapsedOffset = 0.0
        var legDistanceMeters = 0.0
        for (legIndex in 0 until legsJson.length()) {
            val leg = legsJson.getJSONObject(legIndex)
            val coordinates = decodePolyline6(leg.getString("shape"))
            val timeline = maneuverTimeline(coordinates, leg.getJSONArray("maneuvers"))
            val timed = timeline.vertices
            val sharedBoundary = allVertices.lastOrNull() == timed.first().copy(elapsedSeconds = timed.first().elapsedSeconds + elapsedOffset)
            val shapeOffset = allVertices.size - if (sharedBoundary) 1 else 0
            timed.forEachIndexed { index, vertex ->
                val shifted = vertex.copy(elapsedSeconds = vertex.elapsedSeconds + elapsedOffset)
                if (index != 0 || allVertices.isEmpty() || allVertices.last() != shifted) allVertices += shifted
            }
            allManeuvers += timeline.maneuvers.map { maneuver -> maneuver.copy(
                beginShapeIndex = maneuver.beginShapeIndex + shapeOffset,
                endShapeIndex = maneuver.endShapeIndex + shapeOffset,
                beginElapsedSeconds = maneuver.beginElapsedSeconds + elapsedOffset,
                endElapsedSeconds = maneuver.endElapsedSeconds + elapsedOffset,
            ) }
            require(allVertices.size <= 100_000 && allManeuvers.size <= 10_000) { "Rota çok uzun." }
            val legDuration = timed.last().elapsedSeconds
            elapsedOffset += legDuration
            stopTimes += elapsedOffset
            legDistanceMeters += leg.optJSONObject("summary")?.optDouble("length", 0.0)?.times(1_000.0) ?: 0.0
        }
        val summary = trip.getJSONObject("summary")
        val summaryDistance = summary.optDouble("length", Double.NaN).takeIf { it.isFinite() && it >= 0.0 }
        val distanceMeters = summaryDistance?.times(1_000.0) ?: legDistanceMeters
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        val digest = MessageDigest.getInstance("SHA-256").digest("${transport.name}:$requestJson:$createdAt".toByteArray())
        val id = digest.take(12).joinToString("") { "%02x".format(it) }
        return PlannedRoute(id, stops, allVertices, distanceMeters, elapsedOffset, createdAt, stopTimes, transport, travelSpeedKmh, allManeuvers)
    }

    companion object {
        private const val MAX_ROUTE_RESPONSE_BYTES = 2_000_000
    }
}

internal fun decodePolyline6(encoded: String): List<WeatherCoordinate> {
    require(encoded.isNotEmpty() && encoded.length <= 2_000_000) { "Geçersiz rota geometrisi." }
    var index = 0
    var latitude = 0L
    var longitude = 0L

    fun component(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(index < encoded.length && shift <= 60) { "Eksik polyline bileşeni." }
            val value = encoded[index++].code - 63
            require(value in 0..63) { "Geçersiz polyline karakteri." }
            result = result or ((value and 0x1f).toLong() shl shift)
            if (value < 0x20) break
            shift += 5
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }

    val result = ArrayList<WeatherCoordinate>()
    while (index < encoded.length) {
        latitude = Math.addExact(latitude, component())
        longitude = Math.addExact(longitude, component())
        result += WeatherCoordinate(latitude / 1_000_000.0, longitude / 1_000_000.0)
        require(result.size <= 100_000) { "Rota geometrisi çok uzun." }
    }
    require(result.isNotEmpty()) { "Rota geometrisi boş." }
    return result
}

private data class ManeuverTimeline(val vertices: List<RouteVertex>, val maneuvers: List<RouteManeuver>)

private fun maneuverTimeline(coordinates: List<WeatherCoordinate>, maneuvers: JSONArray): ManeuverTimeline {
    if (coordinates.size == 1 && maneuvers.length() == 0) return ManeuverTimeline(listOf(RouteVertex(coordinates.single(), 0.0)), emptyList())
    require(maneuvers.length() > 0) { "Rota zaman çizelgesi boş." }
    val result = mutableListOf<RouteVertex>()
    val instructions = mutableListOf<RouteManeuver>()
    var cumulative = 0.0
    var previousEnd = 0
    for (maneuverIndex in 0 until maneuvers.length()) {
        val maneuver = maneuvers.getJSONObject(maneuverIndex)
        val begin = maneuver.getInt("begin_shape_index")
        val end = maneuver.getInt("end_shape_index")
        val duration = maneuver.getDouble("time")
        require(begin == previousEnd && begin in coordinates.indices && end in begin until coordinates.size)
        require(duration.isFinite() && duration >= 0.0)
        val segmentDistances = (begin until end).map { index -> WeatherEngine.distanceMeters(coordinates[index], coordinates[index + 1]) }
        val totalDistance = segmentDistances.sum()
        val first = RouteVertex(coordinates[begin], cumulative)
        if (result.lastOrNull() != first) result += first
        val mappedBegin = result.lastIndex
        var traversed = 0.0
        for (shapeIndex in begin..end) {
            val ratio = when {
                duration == 0.0 -> 0.0
                totalDistance > 0.0 -> traversed / totalDistance
                end > begin -> (shapeIndex - begin).toDouble() / (end - begin)
                else -> 0.0
            }
            // Preserve every original shape vertex, including repeated coordinates. Only the
            // shared start of adjacent maneuvers is represented by the existing boundary.
            if (shapeIndex != begin) result += RouteVertex(coordinates[shapeIndex], cumulative + duration * ratio)
            if (shapeIndex < end) traversed += segmentDistances[shapeIndex - begin]
        }
        if (begin == end && duration > 0.0) result += RouteVertex(coordinates[begin], cumulative + duration)
        val streets = maneuver.optJSONArray("street_names")
        instructions += RouteManeuver(
            type = maneuver.optInt("type", 0),
            instruction = maneuver.optString("instruction", "").take(2000),
            verbalInstruction = maneuver.optString("verbal_pre_transition_instruction", "")
                .ifBlank { maneuver.optString("verbal_transition_alert_instruction", "") }.take(2000),
            streetNames = if (streets == null) emptyList() else (0 until streets.length()).take(30).map { streets.getString(it).take(300) },
            beginShapeIndex = mappedBegin,
            endShapeIndex = result.lastIndex,
            beginElapsedSeconds = cumulative,
            endElapsedSeconds = cumulative + duration,
            roundaboutExit = maneuver.optInt("roundabout_exit_count", 0).takeIf { it in 1..100 },
        )
        cumulative += duration
        previousEnd = end
    }
    require(previousEnd == coordinates.lastIndex) { "Rota zaman çizelgesi geometriyi kapsamıyor." }
    require(result.first().elapsedSeconds == 0.0)
    require(abs(result.last().elapsedSeconds - cumulative) <= 0.001)
    return ManeuverTimeline(result, instructions)
}
