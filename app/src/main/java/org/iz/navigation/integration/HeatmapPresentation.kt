package org.iz.navigation.integration

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.iz.navigation.power.AnalysisState
import org.iz.navigation.power.AnalysisPhase
import org.iz.navigation.power.PostJourneyAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.Journey
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
private data class CachedHeatmap(val revision: String, val filter: String, val render: HeatmapRender)

/** The worker owns geometry creation; UI selection only decodes a matching ready cache. */
@Composable
internal fun rememberHeatmapRender(analysis: AnalysisState, mode: Transport?): HeatmapRender? {
    val application = LocalContext.current.applicationContext
    val service = remember(application) { PostJourneyAnalysis.get(application) }
    val revision = analysis.cacheRevision.takeIf { !analysis.pending && analysis.phase != AnalysisPhase.WAITING_END }
    val filter = mode?.name ?: "ALL"
    val prepared by produceState<CachedHeatmap?>(null, revision, filter) {
        value = null
        if (revision.isNullOrEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            service.cache.heatmap(revision, filter)?.let { CachedHeatmap(revision, filter, it) }
        }
    }
    return prepared?.takeIf { it.revision == revision && it.filter == filter }?.render
}

/** Legacy callers remain cache-only; raw input never opens a second analysis path. */
@Composable
internal fun rememberHeatmapRender(
    journeys: List<Journey>, points: List<TrackPoint>, mode: Transport?, now: Long,
): HeatmapRender? {
    val application = LocalContext.current.applicationContext
    val service = remember(application) { PostJourneyAnalysis.get(application) }
    val analysis by service.state.collectAsStateWithLifecycle()
    return rememberHeatmapRender(analysis, mode)
}

@Composable
internal fun rememberHeatmapGeometry(data: HeatmapData, mode: Transport? = null): HeatmapRender? {
    val application = LocalContext.current.applicationContext
    val service = remember(application) { PostJourneyAnalysis.get(application) }
    val analysis by service.state.collectAsStateWithLifecycle()
    return rememberHeatmapRender(analysis, mode)
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
