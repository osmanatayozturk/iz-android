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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.iz.navigation.navigation.DriveCameraInput
import org.iz.navigation.navigation.DriveCameraMode
import org.iz.navigation.navigation.DriveCameraPolicy
import org.iz.navigation.navigation.DriveCameraViewport
import org.iz.navigation.navigation.hasSafeAdaptiveArea
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
import org.iz.navigation.power.PowerDiagnostics
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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.ShoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

private const val ROUTE_SOURCE = "weather-planned-route"
private const val STOP_SOURCE = "weather-planned-stops"
private const val SAMPLE_SOURCE = "weather-samples"
private const val SAMPLE_LAYER = "weather-samples-layer"
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
    navigationControlsTopInset: Dp = 96.dp,
    navigationControlsHorizontal: Boolean = false,
    drivingCamera: DriveCameraInput? = null,
    cameraViewportReady: Boolean = true,
    navigationControlsReady: Boolean = true,
    preparedRoutes: org.iz.navigation.navigation.PreparedRouteManager? = null,
    navigationHeader: (@Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    ExpandableMap(modifier = modifier, title = title, expandable = expandable) { mapModifier ->
        Box(mapModifier) {
            Column(Modifier.fillMaxSize()) {
                WeatherRouteMapContent(route, assessment, onSampleClick, onMapLongClick, stops,
                    liveCoordinate, recordedPoints, gpsStale, singleStopLabel, cameraIdentity, navigationLayout,
                    members, onLocate, attributionBottomInset, cameraViewportInsets, navigationControlsTopInset,
                    navigationControlsHorizontal, drivingCamera, cameraViewportReady, navigationControlsReady,
                    preparedRoutes, navigationHeader)
            }
            overlay()
        }
    }
}

@Composable
private fun ColumnScope.WeatherRouteMapContent(
    route: PlannedRoute?,
    assessment: WeatherAssessment?,
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
    navigationControlsTopInset: Dp,
    navigationControlsHorizontal: Boolean,
    drivingCamera: DriveCameraInput?,
    cameraViewportReady: Boolean,
    navigationControlsReady: Boolean,
    preparedRoutes: org.iz.navigation.navigation.PreparedRouteManager?,
    navigationHeader: (@Composable (@Composable () -> Unit, @Composable () -> Unit) -> Unit)?,
) {
    val context = LocalContext.current
    val tileUrl = remember(context) { OsmServiceSettings(context).read().tileUrl }
    val style = remember(tileUrl) {
        mapStyle(tileUrl, listOf(ROUTE_SOURCE, STOP_SOURCE, SAMPLE_SOURCE, FIX_SOURCE), """
            {"id":"weather-route-casing","type":"line","source":"$ROUTE_SOURCE","paint":{"line-color":"#FFFFFF","line-width":9,"line-opacity":0.9}},
            {"id":"weather-route-layer","type":"line","source":"$ROUTE_SOURCE","paint":{"line-color":"#28604D","line-width":5,"line-opacity":0.95}},
            {"id":"$SAMPLE_LAYER","type":"circle","source":"$SAMPLE_SOURCE","paint":{"circle-color":["case",["get","complete"],["case",["get","hazard"],"#AD5D3E","#28604D"],"#6E7C73"],"circle-stroke-color":"#F7F7F2","circle-stroke-width":2,"circle-radius":8}},
            {"id":"weather-stops-layer","type":"circle","source":"$STOP_SOURCE","paint":{"circle-color":"#FFFFFF","circle-stroke-color":"#66746D","circle-stroke-width":1,"circle-radius":4}},
            {"id":"directions-fix-halo","type":"circle","source":"$FIX_SOURCE","paint":{"circle-color":"#1976D2","circle-opacity":0.15,"circle-radius":15}},
            {"id":"directions-fix-layer","type":"circle","source":"$FIX_SOURCE","paint":{"circle-color":["case",["get","stale"],"#78828A","#1976D2"],"circle-stroke-color":"#FFFFFF","circle-stroke-width":3,"circle-radius":7}}
        """.trimIndent())
    }
    val preparedState = preparedRoutes?.state?.collectAsState()?.value
    val preparedDisplay = preparedRoutes != null
    val preparedStyle = remember(style, preparedState?.fingerprint, preparedState?.pack, route?.id) {
        if (preparedRoutes != null && preparedState?.route?.id == route?.id && preparedRoutes.mapReady())
            runCatching { preparedRoutes.maps.renderStyle(style) }.getOrNull() else null
    }
    // No standard OSM raster URL is retained in a prepared-route fallback.
    val displayStyle = remember(style, preparedDisplay, preparedStyle) {
        preparedStyle ?: if (!preparedDisplay) style else JSONObject(style).apply {
            getJSONObject("sources").remove("osm-tiles")
            val localLayers = getJSONArray("layers")
            put("layers", JSONArray().apply { for (i in 0 until localLayers.length()) {
                val layer = localLayers.getJSONObject(i)
                if (layer.optString("source") != "osm-tiles") put(layer)
            } })
        }.toString()
    }
    var preparedCenterCovered by remember { mutableStateOf(false) }
    var showOfflineNotices by remember(preparedRoutes) { mutableStateOf(false) }
    if (showOfflineNotices && preparedRoutes != null)
        org.iz.navigation.ui.OfflineMapNoticesDialog(preparedRoutes.maps) { showOfflineNotices = false }
    var showRoute by remember { mutableStateOf(true) }
    var showMembers by remember { mutableStateOf(true) }
    var layersOpen by remember { mutableStateOf(false) }
    var projectedMembers by remember { mutableStateOf(emptyList<Offset>()) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRevision by remember { mutableIntStateOf(0) }
    val cameraPolicy = remember(map) { RouteMapCameraPolicy() }
    val drivePolicy = remember(map) { DriveCameraPolicy() }
    val driveAnimator = remember(map) { map?.let(::DriveCameraAnimator) }
    fun cancelDriveAnimation() {
        if (driveAnimator?.cancel() == true) drivePolicy.requestRefresh()
    }
    val gestureRemovals = remember { mutableMapOf<MapLibreMap, () -> Unit>() }
    val driveFollow = remember { DrivingMapFollowPolicy() }
    var driveViewportBlocked by remember(map) { mutableStateOf(false) }
    var mapTouchActive by remember(map) { mutableStateOf(false) }
    var previousDriveViewport by remember(map) { mutableStateOf<DriveCameraViewport?>(null) }
    var driveRefreshNeeded by remember(map) { mutableStateOf(false) }
    var driveMode by remember { mutableStateOf(DriveCameraMode.ADAPTIVE) }
    var driveRecenter by remember { mutableIntStateOf(0) }
    var appliedRecenter by remember(map) { mutableIntStateOf(0) }
    var wasDriving by remember(map) { mutableStateOf(false) }
    var drivingDataset by remember(map) { mutableStateOf<String?>(null) }
    var pendingFitDataset by remember { mutableStateOf<String?>(null) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val driveEnabled = drivingCamera?.enabled == true
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
    val driveViewport = DriveCameraViewport(mapSize.width, mapSize.height, cameraPadding[0], cameraPadding[1],
        cameraPadding[2], cameraPadding[3], mapSize.width > 0 && mapSize.height > 0)
    DisposableEffect(driveAnimator, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                cancelDriveAnimation()
                mapTouchActive = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            cancelDriveAnimation()
        }
    }
    val routeCoordinates = remember(route?.vertices, stops) {
        route?.vertices.orEmpty().map { GeoCoordinate(it.coordinate.latitude, it.coordinate.longitude) } +
            stops.map { GeoCoordinate(it.coordinate.latitude, it.coordinate.longitude) }
    }
    DisposableEffect(map, preparedRoutes, preparedState?.pack) {
        val target = map
        fun updateCoverage() {
            val camera = target?.cameraPosition
            val point = camera?.target
            preparedCenterCovered = point != null && preparedRoutes?.maps?.covers(
                org.iz.navigation.offline.OfflinePoint(point.latitude, point.longitude), camera?.zoom ?: Double.NaN) == true
        }
        val listener = MapLibreMap.OnCameraIdleListener { updateCoverage() }
        target?.addOnCameraIdleListener(listener)
        updateCoverage()
        onDispose { target?.removeOnCameraIdleListener(listener) }
    }
    val fix = liveCoordinate?.let { GeoCoordinate(it.latitude, it.longitude) }
    val coordinates = if (routeCoordinates.isNotEmpty()) routeCoordinates else listOfNotNull(fix)
    // A live session supplies stable identity: rerouting moves its origin but must keep user pan.
    // Explicit previews use route id so newly requested geometry is framed again.
    val dataset = routeMapCameraIdentity(cameraIdentity ?: route?.id, stops)

    // Explicit lambda captures keep native callbacks current under Compose strong skipping.
    // A local function reference can hide its changing data from callback memoization.
    val routeJson = remember(route, showRoute) { routeGeoJson(route.takeIf { showRoute }) }
    val stopJson = remember(stops, singleStopLabel) { routeStopsGeoJson(stops, singleStopLabel) }
    val samples = assessment?.samples.orEmpty()
    val samplesJson = remember(samples) { sampleGeoJson(samples) }
    val fixJson = remember(fix, gpsStale) { featureCollection(listOfNotNull(fix?.let {
        pointFeature(it.latitude, it.longitude, JSONObject().put("stale", gpsStale))
    })) }
    val frameRoute by rememberUpdatedState<(MapLibreMap) -> Unit> { target ->
        if (navigationLayout && coordinates.isNotEmpty() && coordinates.all { it == coordinates.first() }) {
            focusRouteCoordinate(target, coordinates.first(), 15.0, cameraPadding)
        } else {
            // Current-fix following uses persistent camera padding; bounds fitting supplies its own.
            if (navigationLayout) target.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
            frameCoordinates(target, coordinates, cameraPadding, 15.0)
        }
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
    val navigationControls: @Composable () -> Unit = {
        Surface(shape = CircleShape, shadowElevation = 3.dp) {
            IconButton(onClick = {
                cancelDriveAnimation()
                pendingFitDataset = null; driveFollow.recenter(); cameraPolicy.followCurrent(); following = true
                if (fix == null) onLocate()
                else if (driveEnabled) driveRecenter++
                else if (cameraViewportReady) map?.let { focusRouteCoordinate(it, fix, 16.0, cameraPadding) }
            }, modifier = Modifier.size(48.dp).testTag("directions-recenter")) { Icon(Icons.Outlined.GpsFixed, "Konumum") }
        }
        Surface(shape = CircleShape, shadowElevation = 3.dp) {
            IconButton(onClick = { layersOpen = true }, modifier = Modifier.size(48.dp).testTag("map-layers")) {
                Icon(Icons.Outlined.Layers, "Katmanlar")
            }
            DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                if (route != null) DropdownMenuItem(text = { Text("Rotayı sığdır") }, onClick = {
                    cancelDriveAnimation()
                    layersOpen = false; pendingFitDataset = null; cameraPolicy.userGesture(); driveFollow.userGesture(); following = false
                    if (cameraViewportReady) map?.let { resetDrivingCamera(it); frameRoute(it) }
                    else pendingFitDataset = dataset
                }, modifier = Modifier.testTag("directions-fit-route"))
                if (driveEnabled) DropdownMenuItem(text = { Text(if (driveMode == DriveCameraMode.SIMPLE_2D) "Sürüş görünümü" else "Basit 2D") }, onClick = {
                    cancelDriveAnimation()
                    driveMode = if (driveMode == DriveCameraMode.ADAPTIVE) DriveCameraMode.SIMPLE_2D else DriveCameraMode.ADAPTIVE
                    layersOpen = false; driveRecenter++
                    if (!following && cameraViewportReady) map?.let(::resetDrivingCamera)
                })
                DropdownMenuItem(text = { Text(if (showRoute) "✓ Planlanan rota" else "Planlanan rota") }, onClick = { showRoute = !showRoute })
                DropdownMenuItem(text = { Text(if (showMembers) "✓ Grup üyeleri" else "Grup üyeleri") }, onClick = { showMembers = !showMembers })
            }
        }
    }
    val preparedNotice: @Composable () -> Unit = {
        if (preparedDisplay) Surface(color = MaterialTheme.colorScheme.surface) {
            Text(when {
                preparedStyle == null -> "Hazır rota · Harita paketi kullanılamıyor; rota çizgisi gösteriliyor."
                preparedCenterCovered -> "Hazır harita · Merkez seçili alanda; ekranın tamamı için kapsam garantisi yok."
                else -> "Hazır rota · Harita merkezi kayıtlı alanın dışında."
            }, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    navigationHeader?.invoke(navigationControls, preparedNotice)
    val observeTouch: (Boolean) -> Unit = { active ->
        if (active) cancelDriveAnimation()
        mapTouchActive = active
    }
    Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { mapSize = it }) {
        val viewport = Modifier.fillMaxSize().then(if (navigationLayout) Modifier.testTag("navigation-home-map") else Modifier)
        OsmMapView(styleJson = displayStyle, modifier = viewport,
            onReady = { ready ->
                map = ready
                if (preparedStyle != null) preparedState?.pack?.request?.let { request ->
                    ready.setMinZoomPreference(request.minZoom.toDouble())
                    ready.setMaxZoomPreference(request.maxZoom.toDouble())
                }
                styleRevision++
                projectStops(ready)
            },
            onMapTouchActive = if (navigationLayout) observeTouch else null,
            onMapDisposing = { disposing ->
                gestureRemovals.remove(disposing)?.invoke()
                if (map === disposing) {
                    cancelDriveAnimation()
                    map = null
                }
            },
            onClick = { target, coordinate ->
                val point = target.projection.toScreenLocation(coordinate.mapCoordinate())
                val index = target.queryRenderedFeatures(point, SAMPLE_LAYER).firstOrNull()?.getNumberProperty("sampleIndex")?.toInt()
                val sample = index?.let { assessment?.samples?.getOrNull(it) }
                if (sample != null) { onSampleClick(sample); true } else false
            },
            onLongClick = onMapLongClick,
            attributionBottomInset = attributionBottomInset, attributionAtStart = navigationLayout,
            maxFps = if (drivingCamera != null) 30 else null,
            mapAttribution = if (preparedStyle != null) org.iz.navigation.offline.OfflineCorridorSession.MAP_ATTRIBUTION else "© OpenStreetMap contributors",
            onAttributionClick = if (preparedDisplay) ({ showOfflineNotices = true }) else null,
        )
        if (preparedDisplay && navigationHeader == null) Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = attributionBottomInset + 28.dp, start = 12.dp, end = 12.dp)
        ) { preparedNotice() }
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
        if (navigationLayout) {
            if (navigationHeader == null && navigationControlsReady) {
                val controlsModifier = Modifier.align(Alignment.TopEnd).padding(top = navigationControlsTopInset, end = 12.dp)
                if (navigationControlsHorizontal) Row(controlsModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { navigationControls() }
                else Column(controlsModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { navigationControls() }
            }
        } else Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                Row {
                    TextButton(onClick = {
                        cancelDriveAnimation()
                        pendingFitDataset = null; cameraPolicy.userGesture(); driveFollow.userGesture(); following = false
                        if (cameraViewportReady) map?.let { resetDrivingCamera(it); frameRoute(it) }
                        else pendingFitDataset = dataset
                    }, modifier = Modifier.testTag("directions-fit-route")) { Text("Rotayı sığdır") }
                    if (fix != null) TextButton(onClick = {
                        cancelDriveAnimation()
                        pendingFitDataset = null; driveFollow.recenter(); cameraPolicy.followCurrent(); following = true
                        if (cameraViewportReady) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(fix.mapCoordinate(), 16.0))
                    }, modifier = Modifier.testTag("directions-recenter")) { Text(if (following) "Konum izleniyor" else "Konumum") }
                }
            }
        }
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
    DisposableEffect(map, navigationLayout) {
        val target = map ?: return@DisposableEffect onDispose {}
        fun holdForGesture() {
            pendingFitDataset = null
            cameraPolicy.userGesture()
            driveFollow.userGesture()
            following = false
        }
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) holdForGesture()
        }
        // MapLibre can coalesce camera-start events when a gesture takes over an ease.
        // Its gesture callbacks still report ownership without canceling a user transition afterward.
        val gesture = object : MapLibreMap.OnMoveListener, MapLibreMap.OnScaleListener,
            MapLibreMap.OnRotateListener, MapLibreMap.OnShoveListener, MapLibreMap.OnFlingListener {
            override fun onMoveBegin(detector: MoveGestureDetector) = holdForGesture()
            override fun onMove(detector: MoveGestureDetector) = Unit
            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            override fun onScaleBegin(detector: StandardScaleGestureDetector) = holdForGesture()
            override fun onScale(detector: StandardScaleGestureDetector) = Unit
            override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
            override fun onRotateBegin(detector: RotateGestureDetector) = holdForGesture()
            override fun onRotate(detector: RotateGestureDetector) = Unit
            override fun onRotateEnd(detector: RotateGestureDetector) = Unit
            override fun onShoveBegin(detector: ShoveGestureDetector) = holdForGesture()
            override fun onShove(detector: ShoveGestureDetector) = Unit
            override fun onShoveEnd(detector: ShoveGestureDetector) = Unit
            override fun onFling() = holdForGesture()
        }
        target.addOnCameraMoveStartedListener(listener)
        if (navigationLayout) {
            target.addOnMoveListener(gesture)
            target.addOnScaleListener(gesture)
            target.addOnRotateListener(gesture)
            target.addOnShoveListener(gesture)
            target.addOnFlingListener(gesture)
        }
        gestureRemovals[target] = {
            target.removeOnCameraMoveStartedListener(listener)
            if (navigationLayout) {
                target.removeOnMoveListener(gesture)
                target.removeOnScaleListener(gesture)
                target.removeOnRotateListener(gesture)
                target.removeOnShoveListener(gesture)
                target.removeOnFlingListener(gesture)
            }
        }
        onDispose { gestureRemovals.remove(target)?.invoke() }
    }
    LaunchedEffect(map, dataset, driveEnabled, cameraViewportReady) {
        if (!cameraViewportReady) return@LaunchedEffect
        if (driveEnabled) {
            if (!wasDriving || drivingDataset != dataset) {
                cancelDriveAnimation()
                drivePolicy.reset(); following = driveFollow.follows(true)
                if (following) cameraPolicy.followCurrent()
                wasDriving = true; drivingDataset = dataset
            }
        } else if (wasDriving) {
            cancelDriveAnimation()
            drivePolicy.reset(); map?.let(::resetDrivingCamera); wasDriving = false; drivingDataset = null
        }
    }
    // An explicit fit survives a pending measurement; a later gesture/recenter cancels it.
    LaunchedEffect(map, dataset, cameraViewportReady, pendingFitDataset) {
        val requested = pendingFitDataset ?: return@LaunchedEffect
        if (requested != dataset) { pendingFitDataset = null; return@LaunchedEffect }
        val target = map ?: return@LaunchedEffect
        if (!cameraViewportReady) return@LaunchedEffect
        pendingFitDataset = null
        cancelDriveAnimation()
        resetDrivingCamera(target); frameRoute(target)
    }
    LaunchedEffect(map, drivingCamera, driveViewport, following, driveMode, driveRecenter, lifecycleState, cameraViewportReady, mapTouchActive) {
        val target = map ?: return@LaunchedEffect
        val animator = driveAnimator ?: return@LaunchedEffect
        val input = drivingCamera
        if (!following || mapTouchActive) return@LaunchedEffect // The touch/command already took ownership.
        if (!cameraViewportReady || input?.enabled != true || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            cancelDriveAnimation()
            if (!cameraViewportReady) driveRefreshNeeded = true
            return@LaunchedEffect
        }
        val viewportChanged = previousDriveViewport?.let { it != driveViewport } == true
        previousDriveViewport = driveViewport
        if (viewportChanged) {
            cancelDriveAnimation()
            driveRefreshNeeded = true
        }
        if (!drivingMapHasVisibleViewport(driveViewport)) {
            cancelDriveAnimation()
            if (!driveViewportBlocked) target.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(target.cameraPosition).tilt(0.0).bearing(0.0).build()))
            driveViewportBlocked = true
            driveRefreshNeeded = true
            return@LaunchedEffect // Hold target and last padding; never recenter behind fully covering panels.
        }
        driveViewportBlocked = false
        if (!input.fresh || input.coordinate == null) {
            cancelDriveAnimation()
            if (driveMode == DriveCameraMode.SIMPLE_2D || !driveViewport.hasSafeAdaptiveArea()) {
                val padding = cameraPadding.map { it.toDouble() }.toDoubleArray()
                val current = target.cameraPosition
                if (current.tilt != 0.0 || current.bearing != 0.0 || !padding.contentEquals(current.padding)) {
                    target.moveCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(current).tilt(0.0).bearing(0.0).padding(padding).build()))
                }
            }
            return@LaunchedEffect
        }
        val recenter = appliedRecenter != driveRecenter
        drivePolicy.update(input.copy(mode = driveMode), driveViewport, recenter || viewportChanged || driveRefreshNeeded)?.let { position ->
            appliedRecenter = driveRecenter
            driveRefreshNeeded = false
            if (recenter || position.pitch == 0.0) {
                // This command applies the accepted target immediately, so it needs no refresh.
                animator.cancel()
                target.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                    .target(GeoCoordinate(position.coordinate.latitude, position.coordinate.longitude).mapCoordinate())
                    .zoom(position.zoom).bearing(position.bearing).tilt(position.pitch).padding(position.padding.toDoubleArray()).build()))
            } else animator.ease(position)
        }
    }
    // Source identities are independent: position changes never rebuild or re-upload static
    // geometry. A recreated style deliberately reloads every source.
    LaunchedEffect(map, styleRevision, routeJson) { map?.updateMeasuredGeoJson(ROUTE_SOURCE, routeJson, PowerDiagnostics.Metric.MAP_ROUTE) }
    LaunchedEffect(map, styleRevision, stopJson) { map?.updateMeasuredGeoJson(STOP_SOURCE, stopJson, PowerDiagnostics.Metric.MAP_ROUTE) }
    LaunchedEffect(map, styleRevision, samplesJson) { map?.updateMeasuredGeoJson(SAMPLE_SOURCE, samplesJson, PowerDiagnostics.Metric.MAP_ROUTE) }
    LaunchedEffect(map, styleRevision, fixJson) { map?.updateMeasuredGeoJson(FIX_SOURCE, fixJson, PowerDiagnostics.Metric.MAP_POSITION) }
    LaunchedEffect(map, dataset, routeCoordinates, coordinates.isNotEmpty(), cameraPadding, driveEnabled, cameraViewportReady) {
        if (!cameraViewportReady || driveEnabled) return@LaunchedEffect
        map?.let { target ->
            if (cameraPolicy.shouldFrame(dataset, coordinates.isNotEmpty(), cameraPadding)) {
                cancelDriveAnimation()
                following = false
                if (navigationLayout && cameraIdentity?.startsWith("session:") == true && fix != null) {
                    cameraPolicy.followCurrent(); following = true
                    focusRouteCoordinate(target, fix, 16.0, cameraPadding)
                } else frameRoute(target)
            }
        }
    }
    LaunchedEffect(map, liveCoordinate, following, cameraPadding, driveEnabled, cameraViewportReady) {
        if (!cameraViewportReady || driveEnabled) return@LaunchedEffect
        if (following && fix != null) map?.let { target ->
            if (navigationLayout) focusRouteCoordinate(target, fix, target.cameraPosition.zoom.coerceAtLeast(15.0), cameraPadding)
            else target.moveCamera(CameraUpdateFactory.newLatLngZoom(fix.mapCoordinate(), target.cameraPosition.zoom.coerceAtLeast(15.0)))
        }
    }
}

/** Per-surface manual hold survives planner/Actions transitions and map recreation. */
internal class DrivingMapFollowPolicy {
    private var manuallyHeld = false
    fun userGesture() { manuallyHeld = true }
    fun recenter() { manuallyHeld = false }
    fun follows(enabled: Boolean): Boolean = enabled && !manuallyHeld
}

internal fun drivingMapHasVisibleViewport(viewport: DriveCameraViewport): Boolean = viewport.known &&
    viewport.widthPx > 0 && viewport.heightPx > 0 && viewport.leftPx >= 0 && viewport.rightPx >= 0 &&
    viewport.topPx >= 0 && viewport.bottomPx >= 0 &&
    viewport.leftPx.toLong() + viewport.rightPx < viewport.widthPx &&
    viewport.topPx.toLong() + viewport.bottomPx < viewport.heightPx

private fun resetDrivingCamera(map: MapLibreMap) {
    map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition)
        .tilt(0.0).bearing(0.0).padding(doubleArrayOf(0.0, 0.0, 0.0, 0.0)).build()))
}

/** Keep the current point in the exposed viewport, including after an explicit recenter. */
private fun focusRouteCoordinate(map: MapLibreMap, coordinate: GeoCoordinate, zoom: Double, padding: List<Int>) {
    map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition)
        .target(coordinate.mapCoordinate()).zoom(zoom)
        .padding(padding.map { it.toDouble() }.toDoubleArray()).build()))
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
