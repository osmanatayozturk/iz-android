package org.iz.navigation.integration

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.gpx.*
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

private const val GPX_ALL = "gpx-segments"
private const val GPX_SELECTED = "gpx-selected"
private const val GPX_START = "gpx-start"
private const val GPX_END = "gpx-end"
private const val GPX_FIX = "gpx-fix"

/** Geometry belongs to GPX, with no PlannedRoute or recorded diary point adapter. */
@Composable
internal fun GpxTrackMap(track: ImportedTrack, selection: TrackFollowSelection,
    fix: NavigationFix?, gpsStale: Boolean, enabled: Boolean,
    onChooseStart: (WeatherCoordinate) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val framePadding = with(LocalDensity.current) { 32.dp.roundToPx() }
    val tileUrl = remember(context) { OsmServiceSettings(context).read().tileUrl }
    val style = remember(tileUrl) {
        mapStyle(tileUrl, listOf(GPX_ALL, GPX_SELECTED, GPX_START, GPX_END, GPX_FIX), """
            {"id":"gpx-all-line","type":"line","source":"$GPX_ALL","paint":{"line-color":"#788278","line-width":3,"line-opacity":0.65}},
            {"id":"gpx-selection-casing","type":"line","source":"$GPX_SELECTED","paint":{"line-color":"#FFFFFF","line-width":9}},
            {"id":"gpx-selection-line","type":"line","source":"$GPX_SELECTED","paint":{"line-color":"#28604D","line-width":5}},
            {"id":"gpx-end-point","type":"circle","source":"$GPX_END","paint":{"circle-color":"#AD5D3E","circle-radius":7,"circle-stroke-color":"#FFFFFF","circle-stroke-width":2}},
            {"id":"gpx-start-point","type":"circle","source":"$GPX_START","paint":{"circle-color":"#28604D","circle-radius":9,"circle-stroke-color":"#FFFFFF","circle-stroke-width":3}},
            {"id":"gpx-location-point","type":"circle","source":"$GPX_FIX","paint":{"circle-color":["case",["get","stale"],"#78828A","#1976D2"],"circle-radius":6,"circle-stroke-color":"#FFFFFF","circle-stroke-width":2}}
        """.trimIndent())
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frameRequest by remember { mutableIntStateOf(0) }
    val geometry by produceState<GpxMapGeometry?>(null, track, selection) {
        value = null
        value = withContext(Dispatchers.Default) { prepareGpxMap(track, selection) }
    }
    val latestGeometry by rememberUpdatedState(geometry)
    LaunchedEffect(map, geometry) {
        val target = map ?: return@LaunchedEffect
        val data = geometry ?: return@LaunchedEffect
        target.updateGeoJson(GPX_ALL, data.all)
        target.updateGeoJson(GPX_SELECTED, data.selected)
        target.updateGeoJson(GPX_START, data.start)
        target.updateGeoJson(GPX_END, data.end)
    }
    LaunchedEffect(map, fix, gpsStale) {
        map?.updateGeoJson(GPX_FIX, featureCollection(listOfNotNull(fix?.coordinate?.let {
            pointFeature(it.latitude, it.longitude, JSONObject().put("stale", gpsStale))
        })))
    }
    // Location updates never reset a user's camera. A different section or explicit fit may.
    val frame = geometry?.frame
    LaunchedEffect(map, track.id, selection.segmentIndex, frame, size, frameRequest, framePadding) {
        val target = map ?: return@LaunchedEffect
        if (size.width > 0 && size.height > 0) latestGeometry?.frame?.let { fitGpxFrame(target, it, framePadding) }
    }
    Column(modifier) {
        OsmMapView(style, Modifier.fillMaxWidth().height(280.dp).testTag("gpx-map").onSizeChanged { size = it },
            onReady = { map = it },
            onLongClick = { if (enabled) onChooseStart(WeatherCoordinate(it.latitude, it.longitude)) })
        Text("Yeşil: başlangıç → kahverengi: bölüm sonu", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp))
        Text("Başlangıcı seçmek için çizginin istediğin yerine uzun bas.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { frameRequest++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("gpx-fit-map")) {
            Text("Bölümü haritaya sığdır")
        }
    }
}

private data class GpxMapFrame(val north: Double, val east: Double, val south: Double, val west: Double)
private data class GpxMapGeometry(val all: String, val selected: String, val start: String, val end: String, val frame: GpxMapFrame?)

private fun prepareGpxMap(track: ImportedTrack, selection: TrackFollowSelection): GpxMapGeometry {
    val selected = runCatching { selectedTrackPoints(track, selection) }.getOrDefault(emptyList())
    fun lines(values: List<List<WeatherCoordinate>>) = featureCollection(values.flatMap(::gpxDisplayLines).map { points ->
        JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray().apply {
                points.forEach { put(JSONArray().put(it.longitude).put(it.latitude)) }
            }))
    })
    fun marker(point: WeatherCoordinate?) = featureCollection(listOfNotNull(point?.let { pointFeature(it.latitude, it.longitude) }))
    val fullSegment = track.segments.getOrNull(selection.segmentIndex)?.points.orEmpty()
    return GpxMapGeometry(lines(track.segments.map { it.points }), lines(listOf(selected)),
        marker(selected.firstOrNull()), marker(selected.lastOrNull()), gpxFrame(fullSegment))
}

private fun gpxFrame(points: List<WeatherCoordinate>): GpxMapFrame? {
    if (points.isEmpty()) return null
    val longitudes = points.map { ((it.longitude % 360) + 360) % 360 }.sorted()
    var gap = -1.0; var afterGap = 0
    for (i in longitudes.indices) {
        val next = if (i == longitudes.lastIndex) longitudes[0] + 360 else longitudes[i + 1]
        if (next - longitudes[i] > gap) { gap = next - longitudes[i]; afterGap = (i + 1) % longitudes.size }
    }
    val span = (360 - gap).coerceAtLeast(0.0)
    val west = ((longitudes[afterGap] + 540) % 360) - 180
    return GpxMapFrame(points.maxOf { it.latitude }.coerceIn(-85.05112878, 85.05112878), west + span,
        points.minOf { it.latitude }.coerceIn(-85.05112878, 85.05112878), west)
}

private fun fitGpxFrame(map: MapLibreMap, frame: GpxMapFrame, padding: Int) {
    // LatLngBounds intentionally accepts unwrapped longitudes, so 179..181 is a narrow box.
    val bounds = LatLngBounds.from(frame.north, frame.east, frame.south, frame.west)
    map.getCameraForLatLngBounds(bounds, intArrayOf(padding, padding, padding, padding), 0.0, 0.0)?.let {
        map.moveCamera(CameraUpdateFactory.newCameraPosition(it))
    }
}
