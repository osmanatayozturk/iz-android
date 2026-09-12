package org.iz.navigation.integration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteWeatherSample
import org.iz.navigation.weather.WeatherAssessment
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

private const val ROUTE_SOURCE = "weather-planned-route"
private const val STOP_SOURCE = "weather-planned-stops"
private const val SAMPLE_SOURCE = "weather-samples"
private const val SAMPLE_LAYER = "weather-samples-layer"
private const val RECORDED_SOURCE = "directions-recorded-route"
private const val FIX_SOURCE = "directions-current-fix"

data class RouteMapMember(val id: String, val name: String, val latitude: Double, val longitude: Double, val delayed: Boolean)

@Composable
fun WeatherRouteMap(
    route: PlannedRoute?,
    assessment: WeatherAssessment?,
    modifier: Modifier = Modifier,
    onSampleClick: (RouteWeatherSample) -> Unit = {},
    onMapLongClick: (GeoCoordinate) -> Unit = {},
    expandable: Boolean = true,
    stops: List<RouteStop> = route?.stops.orEmpty(),
    liveCoordinate: WeatherCoordinate? = null,
    recordedPoints: List<TrackPoint> = emptyList(),
    gpsStale: Boolean = false,
    title: String = "Yolculuk havası haritası",
    singleStopLabel: String = "A",
    cameraIdentity: String? = null,
    navigationLayout: Boolean = false,
    members: List<RouteMapMember> = emptyList(),
    onLocate: () -> Unit = {},
    attributionBottomInset: Dp = if (navigationLayout) 92.dp else 4.dp,
    cameraViewportInsets: PaddingValues = PaddingValues(72.dp),
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    ExpandableMap(modifier = modifier, title = title, expandable = expandable) { mapModifier ->
        Box(mapModifier) {
            WeatherRouteMapContent(route, assessment, Modifier.fillMaxSize(), onSampleClick,
                onMapLongClick, stops, liveCoordinate, recordedPoints, gpsStale, singleStopLabel, cameraIdentity, navigationLayout, members, onLocate, attributionBottomInset, cameraViewportInsets)
            overlay()
        }
    }
}

@Composable
private fun WeatherRouteMapContent(
    route: PlannedRoute?,
    assessment: WeatherAssessment?,
    modifier: Modifier,
    onSampleClick: (RouteWeatherSample) -> Unit,
    onMapLongClick: (GeoCoordinate) -> Unit,
    stops: List<RouteStop>,
    liveCoordinate: WeatherCoordinate?,
    recordedPoints: List<TrackPoint>,
    gpsStale: Boolean,
    singleStopLabel: String,
    cameraIdentity: String?,
    navigationLayout: Boolean,
    members: List<RouteMapMember>,
    onLocate: () -> Unit,
    attributionBottomInset: Dp,
    cameraViewportInsets: PaddingValues,
) {
    val context = LocalContext.current
    val tileUrl = remember(context) { OsmServiceSettings(context).read().tileUrl }
    val style = remember(tileUrl) {
        mapStyle(tileUrl, listOf(ROUTE_SOURCE, STOP_SOURCE, SAMPLE_SOURCE, RECORDED_SOURCE, FIX_SOURCE), """
            {"id":"weather-route-casing","type":"line","source":"$ROUTE_SOURCE","paint":{"line-color":"#FFFFFF","line-width":9,"line-opacity":0.9}},
            {"id":"weather-route-layer","type":"line","source":"$ROUTE_SOURCE","paint":{"line-color":"#28604D","line-width":5,"line-opacity":0.95}},
            {"id":"directions-recorded-layer","type":"line","source":"$RECORDED_SOURCE","layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":["to-color",["get","color"]],"line-width":4,"line-opacity":0.95}},
            {"id":"$SAMPLE_LAYER","type":"circle","source":"$SAMPLE_SOURCE","paint":{"circle-color":["case",["get","complete"],["case",["get","hazard"],"#AD5D3E","#28604D"],"#6E7C73"],"circle-stroke-color":"#F7F7F2","circle-stroke-width":2,"circle-radius":8}},
            {"id":"weather-stops-layer","type":"circle","source":"$STOP_SOURCE","paint":{"circle-color":"#FFFFFF","circle-stroke-color":"#66746D","circle-stroke-width":1,"circle-radius":4}},
            {"id":"directions-fix-halo","type":"circle","source":"$FIX_SOURCE","paint":{"circle-color":"#1976D2","circle-opacity":0.15,"circle-radius":15}},
            {"id":"directions-fix-layer","type":"circle","source":"$FIX_SOURCE","paint":{"circle-color":["case",["get","stale"],"#78828A","#1976D2"],"circle-stroke-color":"#FFFFFF","circle-stroke-width":3,"circle-radius":7}}
        """.trimIndent())
    }
    var showRoute by remember { mutableStateOf(true) }
    var showTrail by remember { mutableStateOf(true) }
    var showMembers by remember { mutableStateOf(true) }
    var layersOpen by remember { mutableStateOf(false) }
    var projectedMembers by remember { mutableStateOf(emptyList<Offset>()) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val cameraPolicy = remember(map) { RouteMapCameraPolicy() }
    var following by remember(map) { mutableStateOf(false) }
    // Ready/camera callbacks must keep the same state cell when null map becomes the ready map.
    var projectedStops by remember { mutableStateOf(emptyList<Offset>()) }
    val density = LocalDensity.current
    val badgeSize = with(density) { 28.dp.roundToPx() }
    val badgeGap = with(density) { 12.dp.roundToPx() }
    val layoutDirection = LocalLayoutDirection.current
    val cameraPadding = with(density) {
        listOf(cameraViewportInsets.calculateLeftPadding(layoutDirection).roundToPx(),
            cameraViewportInsets.calculateTopPadding().roundToPx(),
            cameraViewportInsets.calculateRightPadding(layoutDirection).roundToPx(),
            cameraViewportInsets.calculateBottomPadding().roundToPx())
    }
    val routeCoordinates = remember(route?.vertices, stops) {
        route?.vertices.orEmpty().map { GeoCoordinate(it.coordinate.latitude, it.coordinate.longitude) } +
            stops.map { GeoCoordinate(it.coordinate.latitude, it.coordinate.longitude) }
    }
    val recorded = rememberRecordedTrail(recordedPoints)
    val fix = liveCoordinate?.let { GeoCoordinate(it.latitude, it.longitude) }
    val coordinates = if (routeCoordinates.isNotEmpty()) routeCoordinates else
        recorded.trail.paths.flatten() + listOfNotNull(fix)
    // A live session supplies stable identity: rerouting moves its origin but must keep user pan.
    // Explicit previews use route id so newly requested geometry is framed again.
    val dataset = routeMapCameraIdentity(cameraIdentity ?: route?.id, stops)

    // Explicit lambda captures keep native callbacks current under Compose strong skipping.
    // A local function reference can hide its changing data from callback memoization.
    val update by rememberUpdatedState<(MapLibreMap) -> Unit> { target ->
        target.updateGeoJson(ROUTE_SOURCE, routeGeoJson(route.takeIf { showRoute }))
        target.updateGeoJson(STOP_SOURCE, routeStopsGeoJson(stops, singleStopLabel))
        target.updateGeoJson(SAMPLE_SOURCE, sampleGeoJson(assessment?.samples.orEmpty()))
        target.updateGeoJson(FIX_SOURCE, featureCollection(listOfNotNull(fix?.let {
            pointFeature(it.latitude, it.longitude, JSONObject().put("stale", gpsStale))
        })))
    }
    val updateTrail by rememberUpdatedState<(MapLibreMap) -> Unit> { target ->
        target.updateGeoJson(RECORDED_SOURCE, if (showTrail) recorded.json else "{\"type\":\"FeatureCollection\",\"features\":[]}")
    }
    val frameRoute by rememberUpdatedState<(MapLibreMap) -> Unit> { target ->
        frameCoordinates(target, coordinates, cameraPadding, 15.0)
    }
    val projectStops by rememberUpdatedState<(MapLibreMap) -> Unit> { target ->
        projectedMembers = members.map { member ->
            val point = target.projection.toScreenLocation(GeoCoordinate(member.latitude, member.longitude).mapCoordinate())
            Offset(point.x, point.y)
        }
        projectedStops = stops.map { stop ->
            val point = target.projection.toScreenLocation(GeoCoordinate(stop.coordinate.latitude, stop.coordinate.longitude).mapCoordinate())
            Offset(point.x, point.y)
        }
    }
    Box(modifier.clipToBounds()) {
        OsmMapView(styleJson = style, modifier = Modifier.fillMaxSize(),
            onReady = { ready ->
                map = ready
                update(ready)
                updateTrail(ready)
                projectStops(ready)
            },
            onClick = { target, coordinate ->
                val point = target.projection.toScreenLocation(coordinate.mapCoordinate())
                val index = target.queryRenderedFeatures(point, SAMPLE_LAYER).firstOrNull()?.getNumberProperty("sampleIndex")?.toInt()
                val sample = index?.let { assessment?.samples?.getOrNull(it) }
                if (sample != null) { onSampleClick(sample); true } else false
            },
            onLongClick = onMapLongClick,
            attributionBottomInset = attributionBottomInset, attributionAtStart = navigationLayout,
        )
        // Sprite images can be registered/queryable yet absent from the native GPU atlas.
        // Geographic Compose badges keep endpoint labels visible and accessible above the live fix.
        stops.forEachIndexed { index, stop ->
            projectedStops.getOrNull(index)?.let { point ->
                val label = routeStopMarkerLabel(index, stops.size, singleStopLabel)
                Box(Modifier.offset { IntOffset(point.x.roundToInt() - badgeSize / 2, point.y.roundToInt() - badgeSize - badgeGap) }
                    .size(28.dp).background(if (label == "B") Color(0xFF853E2B) else Color(0xFF28604D), CircleShape)
                    .border(2.dp, Color.White, CircleShape).testTag("directions-map-marker-$label")
                    .semantics { contentDescription = "$label · ${stop.label}" }, contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (showMembers) members.forEachIndexed { index, member ->
            projectedMembers.getOrNull(index)?.let { point ->
                Surface(Modifier.offset { IntOffset(point.x.roundToInt() - badgeSize / 2, point.y.roundToInt() - badgeSize) }
                    .widthIn(max = 140.dp).testTag("group-map-member-${member.id}"),
                    color = if (member.delayed) Color(0xFF6E7C73) else Color(0xFF65509A), shape = MaterialTheme.shapes.medium) {
                    Text(member.name.take(24) + if (member.delayed) " · gecikmeli" else "", color = Color.White,
                        modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (navigationLayout) Column(Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, shadowElevation = 3.dp) {
                IconButton(onClick = {
                    cameraPolicy.followCurrent(); following = true
                    if (fix == null) onLocate()
                    else map?.moveCamera(CameraUpdateFactory.newLatLngZoom(fix.mapCoordinate(), 16.0))
                }, modifier = Modifier.size(48.dp).testTag("directions-recenter")) { Icon(Icons.Outlined.GpsFixed, "Konumum") }
            }
            Surface(shape = CircleShape, shadowElevation = 3.dp) {
                IconButton(onClick = { layersOpen = true }, modifier = Modifier.size(48.dp).testTag("map-layers")) {
                    Icon(Icons.Outlined.Layers, "Katmanlar")
                }
                DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                    if (route != null) DropdownMenuItem(text = { Text("Rotayı sığdır") }, onClick = {
                        layersOpen = false; cameraPolicy.userGesture(); following = false
                        map?.let { frameRoute(it) }
                    }, modifier = Modifier.testTag("directions-fit-route"))
                    DropdownMenuItem(text = { Text(if (showRoute) "✓ Planlanan rota" else "Planlanan rota") }, onClick = { showRoute = !showRoute })
                    DropdownMenuItem(text = { Text(if (showTrail) "✓ Yolculuk kaydı" else "Yolculuk kaydı") }, onClick = { showTrail = !showTrail })
                    DropdownMenuItem(text = { Text(if (showMembers) "✓ Grup üyeleri" else "Grup üyeleri") }, onClick = { showMembers = !showMembers })
                }
            }
        } else Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                Row {
                    TextButton(onClick = {
                        cameraPolicy.userGesture(); following = false
                        map?.let { frameRoute(it) }
                    }, modifier = Modifier.testTag("directions-fit-route")) { Text("Rotayı sığdır") }
                    if (fix != null) TextButton(onClick = {
                        cameraPolicy.followCurrent(); following = true
                        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(fix.mapCoordinate(), 16.0))
                    }, modifier = Modifier.testTag("directions-recenter")) { Text(if (following) "Konum izleniyor" else "Konumum") }
                }
            }
            if (showTrail) RecordedSpeedLegend(recorded.trail)
        }
        if (navigationLayout && showTrail) RecordedSpeedLegend(recorded.trail,
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = attributionBottomInset + 28.dp))
    }
    DisposableEffect(map, stops, members) {
        val target = map
        val listener = MapLibreMap.OnCameraMoveListener { target?.let { projectStops(it) } }
        val idleListener = MapLibreMap.OnCameraIdleListener { target?.let { projectStops(it) } }
        target?.addOnCameraMoveListener(listener)
        target?.addOnCameraIdleListener(idleListener)
        onDispose {
            target?.removeOnCameraMoveListener(listener)
            target?.removeOnCameraIdleListener(idleListener)
        }
    }
    LaunchedEffect(map, stops, members) { map?.let { projectStops(it) } }
    DisposableEffect(map) {
        val target = map
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                cameraPolicy.userGesture(); following = false
            }
        }
        target?.addOnCameraMoveStartedListener(listener)
        onDispose { target?.removeOnCameraMoveStartedListener(listener) }
    }
    LaunchedEffect(map, recorded.json, showTrail) { map?.let { updateTrail(it) } }
    LaunchedEffect(route, stops, assessment, liveCoordinate, gpsStale, singleStopLabel, showRoute, map) { map?.let { update(it) } }
    LaunchedEffect(map, dataset, routeCoordinates, coordinates.isNotEmpty(), cameraPadding) {
        map?.let { target ->
            if (cameraPolicy.shouldFrame(dataset, coordinates.isNotEmpty(), cameraPadding)) {
                following = false
                if (navigationLayout && cameraIdentity?.startsWith("session:") == true && fix != null) {
                    cameraPolicy.followCurrent(); following = true
                    target.moveCamera(CameraUpdateFactory.newLatLngZoom(fix.mapCoordinate(), 16.0))
                } else frameRoute(target)
            }
        }
    }
    LaunchedEffect(map, liveCoordinate, following) {
        if (following && fix != null) map?.let { target ->
            target.moveCamera(CameraUpdateFactory.newLatLngZoom(fix.mapCoordinate(), target.cameraPosition.zoom.coerceAtLeast(15.0)))
        }
    }
}

/** Pan disables follow; data refresh never recenters an already framed endpoint pair. */
internal class RouteMapCameraPolicy {
    var following = false
        private set
    private var framedDataset: String? = null
    private var framedViewport: List<Int> = emptyList()
    private var userPanned = false
    fun shouldFrame(dataset: String, hasCoordinates: Boolean, viewport: List<Int> = emptyList()): Boolean {
        if (!hasCoordinates) return false
        if (framedDataset == dataset && (framedViewport == viewport || userPanned)) return false
        if (framedDataset != dataset) userPanned = false
        framedDataset = dataset
        framedViewport = viewport
        following = false
        return true
    }
    fun userGesture() { following = false; userPanned = true }
    fun followCurrent() { following = true }
}

internal fun routeMapCameraIdentity(sessionIdentity: String?, stops: List<RouteStop>): String =
    sessionIdentity ?: stops.joinToString { "${it.coordinate.latitude},${it.coordinate.longitude}" }

private fun routeStopMarkerLabel(index: Int, count: Int, singleStopLabel: String) = when {
    count == 1 -> singleStopLabel
    index == 0 -> "A"
    index == count - 1 -> "B"
    else -> index.toString()
}

internal fun routeStopsGeoJson(stops: List<RouteStop>, singleStopLabel: String = "A"): String = featureCollection(
    stops.mapIndexed { index, stop -> pointFeature(stop.coordinate.latitude, stop.coordinate.longitude,
        JSONObject().put("marker", routeStopMarkerLabel(index, stops.size, singleStopLabel))) },
)

internal fun recordedRouteGeoJson(points: List<TrackPoint>): String = recordedSpeedTrail(points).geoJson()

internal fun routeGeoJson(route: PlannedRoute?): String {
    val coordinates = JSONArray()
    route?.vertices?.forEach { vertex -> coordinates.put(JSONArray().put(vertex.coordinate.longitude).put(vertex.coordinate.latitude)) }
    val features = if (coordinates.length() >= 2) listOf(JSONObject().put("type", "Feature").put("properties", JSONObject())
        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates))) else emptyList()
    return featureCollection(features)
}

internal fun sampleGeoJson(samples: List<RouteWeatherSample>): String = featureCollection(
    samples.mapIndexed { index, sample -> pointFeature(sample.coordinate.latitude, sample.coordinate.longitude,
        JSONObject().put("sampleIndex", index).put("complete", sample.complete).put("hazard", sample.hazards.isNotEmpty())) },
)

