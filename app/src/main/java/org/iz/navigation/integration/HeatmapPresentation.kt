package org.iz.navigation.integration

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.json.JSONArray
import org.json.JSONObject

internal data class HeatmapFrequencyBand(val minimum: Int, val label: String, val color: String)
internal val heatmapFrequencyBands = listOf(
    HeatmapFrequencyBand(1, "1", "#3989C9"),
    HeatmapFrequencyBand(2, "2–3", "#54BEAC"),
    HeatmapFrequencyBand(4, "4–7", "#F0C65D"),
    HeatmapFrequencyBand(8, "8+", "#D8613F"),
)

internal data class HeatmapRender(val data: HeatmapData, val json: String, val bounds: List<GeoCoordinate>) {
    val hasGeometry: Boolean get() = bounds.isNotEmpty()
}
private class HeatmapInput(
    val journeys: List<Journey>, val points: List<TrackPoint>, val mode: Transport?, val filterEpoch: Any,
)
private class PreparedHeatmap(val request: Any, val render: HeatmapRender)

/** Wall-clock ticks only invalidate expensive work when journey eligibility changes. */
@Composable
internal fun rememberHeatmapRender(
    journeys: List<Journey>, points: List<TrackPoint>, mode: Transport?, now: Long,
): HeatmapRender? {
    val eligible = journeys.filter {
        it.status == JourneyStatus.CONFIRMED && (it.expiresAt == null || it.expiresAt > now) &&
            (mode == null || it.transport == mode)
    }
    val filterEpoch = remember(mode) { Any() }
    val request = remember(eligible, points, filterEpoch) { HeatmapInput(eligible, points, mode, filterEpoch) }
    val prepared by produceState<PreparedHeatmap?>(null, request) {
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val data = buildHeatmapData(request.journeys, request.points, request.mode, now) { context.ensureActive() }
            PreparedHeatmap(request, prepareHeatmapRender(data) { context.ensureActive() })
        }
    }
    // Keep the ready map/legend during routine updates. A new filter selection immediately clears
    // the old display, even when switching A -> loading B -> A before B has finished. Cancelled
    // workers cannot publish; a completed empty result replaces retained geometry normally.
    return prepared?.takeIf { (it.request as? HeatmapInput)?.filterEpoch === filterEpoch }?.render
}

@Composable
internal fun rememberHeatmapGeometry(data: HeatmapData): HeatmapRender? {
    val request = remember(data) { Any() }
    val prepared by produceState<PreparedHeatmap?>(null, request) {
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            PreparedHeatmap(request, prepareHeatmapRender(data) { context.ensureActive() })
        }
    }
    return prepared?.takeIf { it.request === request }?.render
}

/** Builds geometry, properties and camera bounds off the main thread, without SDK calls. */
internal fun prepareHeatmapRender(data: HeatmapData, checkActive: () -> Unit = {}): HeatmapRender {
    checkActive()
    val features = JSONArray()
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    var processed = 0
    fun coordinate(value: GeoCoordinate): JSONArray {
        if (++processed % 256 == 0) checkActive()
        minLat = minOf(minLat, value.latitude); maxLat = maxOf(maxLat, value.latitude)
        minLon = minOf(minLon, value.longitude); maxLon = maxOf(maxLon, value.longitude)
        return JSONArray().put(value.longitude).put(value.latitude)
    }
    fun properties(count: Int): JSONObject = JSONObject().put("journeyCount", count)
        .put("color", heatmapFrequencyBands.last { count >= it.minimum }.color)
    data.lines.forEach { line ->
        val coordinates = JSONArray()
        line.coordinates.forEach { coordinates.put(coordinate(it)) }
        features.put(JSONObject().put("type", "Feature").put("properties", properties(line.journeyCount))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates)))
    }
    data.isolatedPoints.forEach { point ->
        features.put(JSONObject().put("type", "Feature").put("properties", properties(point.journeyCount))
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", coordinate(point.coordinate))))
    }
    checkActive()
    val bounds = if (minLat.isFinite()) listOf(GeoCoordinate(minLat, minLon), GeoCoordinate(maxLat, maxLon)).distinct()
        else emptyList()
    val json = JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    checkActive()
    return HeatmapRender(data, json, bounds)
}
