package org.iz.navigation.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import java.time.Instant
import java.time.ZoneId
import org.iz.navigation.power.AnalysisCache

internal enum class HeatmapPeriod(val days: Long, val label: String) {
    ALL(0, "Tüm kayıtlar"), WEEK(7, "Son 7 gün"), MONTH(30, "Son 30 gün"),
}

internal data class HeatmapFilter(val mode: Transport? = null, val period: HeatmapPeriod = HeatmapPeriod.ALL)
internal data class HeatmapWindow(val period: HeatmapPeriod, val start: Long, val endExclusive: Long)

internal fun heatmapWindow(period: HeatmapPeriod, now: Long, zone: ZoneId = ZoneId.systemDefault()): HeatmapWindow? {
    if (period == HeatmapPeriod.ALL) return null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return HeatmapWindow(period, today.minusDays(period.days - 1).atStartOfDay(zone).toInstant().toEpochMilli(),
        today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
}

internal data class HeatmapSelection(val cacheKey: String, val journeys: List<Journey>)

/** Within one analysis revision, equal completed journey IDs imply identical source points. */
internal fun selectHeatmap(journeys: Collection<Journey>, mode: Transport?, window: HeatmapWindow): HeatmapSelection {
    val eligible = journeys.filter { it.endedAt != null && (mode == null || it.transport == mode) }
    val selected = eligible.filter { it.endedAt!! in window.start until window.endExclusive }
    val modeKey = mode?.name ?: "ALL"
    val key = when {
        selected.isEmpty() -> "EMPTY"
        selected.size == eligible.size -> modeKey
        else -> {
            val ids = selected.map { it.id }.sorted().joinToString("") { "${it.length}:$it" }
            "${window.period.name}_${modeKey}_${AnalysisCache.digest(ids)}"
        }
    }
    return HeatmapSelection(key, selected)
}

@Composable
internal fun rememberHeatmapWindow(period: HeatmapPeriod, fixedNow: Long?): HeatmapWindow? {
    if (fixedNow != null) return heatmapWindow(period, fixedNow)
    if (period == HeatmapPeriod.ALL) return null
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun current() = heatmapWindow(period, System.currentTimeMillis())
    var window by remember(period) { mutableStateOf(current()) }
    DisposableEffect(context, lifecycle, period) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { window = current() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) window = current()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return window
}

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
private data class CachedHeatmap(val revision: String, val mode: Transport?, val window: HeatmapWindow?, val render: HeatmapRender)

/** The worker owns geometry creation; UI selection only decodes a matching ready cache. */
@Composable
internal fun rememberHeatmapRender(analysis: AnalysisState, mode: Transport?, window: HeatmapWindow? = null): HeatmapRender? {
    val application = LocalContext.current.applicationContext
    val service = remember(application) { PostJourneyAnalysis.get(application) }
    val revision = analysis.cacheRevision.takeIf { !analysis.pending && analysis.phase != AnalysisPhase.WAITING_END }
    val prepared by produceState<CachedHeatmap?>(null, revision, mode, window, analysis.changed) {
        value = null
        if (revision.isNullOrEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            service.readHeatmap(analysis, mode, window)?.let { CachedHeatmap(revision, mode, window, it) }
        }
    }
    return prepared?.takeIf { it.revision == revision && it.mode == mode && it.window == window }?.render
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
