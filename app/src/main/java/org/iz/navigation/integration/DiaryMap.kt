package org.iz.navigation.integration

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.Place
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.TrackPoint
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap

/** Displays only recorded coordinates. A missing fix always breaks the line. */
@Composable
fun DiaryMap(
    points: List<TrackPoint>,
    places: List<Place>,
    modifier: Modifier = Modifier,
    onPlaceClick: (Place) -> Unit = {},
    onMapLongClick: (GeoCoordinate) -> Unit = {},
    focusCurrentLocation: Boolean = false,
    onOsmPlaceClick: ((SelectedOsmPlace) -> Unit)? = null,
    followRecordedLocation: Boolean = false,
    expandable: Boolean = true,
    fullscreenActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    ExpandableMap(modifier, expandable = expandable, actions = fullscreenActions) { mapModifier ->
        DiaryMapContent(points, places, mapModifier, onPlaceClick, onMapLongClick,
            focusCurrentLocation, onOsmPlaceClick, followRecordedLocation)
    }
}

@Composable
private fun DiaryMapContent(
    points: List<TrackPoint>,
    places: List<Place>,
    modifier: Modifier,
    onPlaceClick: (Place) -> Unit,
    onMapLongClick: (GeoCoordinate) -> Unit,
    focusCurrentLocation: Boolean,
    onOsmPlaceClick: ((SelectedOsmPlace) -> Unit)?,
    followRecordedLocation: Boolean,
) {
    val context = LocalContext.current
    val service = remember(context) { OsmPlaces(context) }
    val tileUrl = OsmServiceSettings(context).read().tileUrl
    val styleJson = remember(tileUrl) { mapStyle(tileUrl, listOf("routes", "places", "current", "osm-pois"), diaryLayers) }
    val routes = remember(points) { splitRouteSegments(points) }
    val pins = remember(places) { places.filter { validCoordinate(it.latitude, it.longitude) } }
    val coordinates = remember(routes, pins) { routes.flatten() + pins.map { GeoCoordinate(it.latitude!!, it.longitude!!) } }
    val dataset = remember(points, pins) { points.map { it.journeyId }.distinct().joinToString() + ":" + pins.joinToString { it.id } }
    var map by remember(tileUrl) { mutableStateOf<MapLibreMap?>(null) }
    var overlayPlaces by remember { mutableStateOf(emptyList<SelectedOsmPlace>()) }
    var choices by remember { mutableStateOf(emptyList<SelectedOsmPlace>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val chooseOsmPlace by rememberUpdatedState(onOsmPlaceClick)
    val scope = rememberCoroutineScope()
    val location = rememberOpeningLocation(focusCurrentLocation)
    val latestRoutePoint = routes.lastOrNull()?.lastOrNull()
    var centeredOnLocation by remember { mutableStateOf(false) }
    LaunchedEffect(map, location.coordinate) {
        val target = location.coordinate
        if (map != null && !centeredOnLocation && target != null && !(followRecordedLocation && latestRoutePoint != null)) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(target.mapCoordinate(), 16.0))
            centeredOnLocation = true
        }
    }
    LaunchedEffect(map, followRecordedLocation, latestRoutePoint) {
        if (followRecordedLocation && latestRoutePoint != null) map?.let { current ->
            current.moveCamera(CameraUpdateFactory.newLatLngZoom(latestRoutePoint.mapCoordinate(), current.cameraPosition.zoom.coerceAtLeast(15.0)))
        }
    }
    // Reframe on a different set of journeys/places, not on every recorded location update.
    LaunchedEffect(map, dataset, coordinates.isEmpty()) {
        if (!focusCurrentLocation) map?.let { frameCoordinates(it, coordinates, 80, 15.0) }
    }
    LaunchedEffect(map, routes, pins, overlayPlaces, location.coordinate, latestRoutePoint, followRecordedLocation) {
        map?.let { current ->
            current.updateGeoJson("routes", featureCollection(routes.filter { it.size > 1 }.map { line ->
                JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", JSONObject()
                    .put("type", "LineString").put("coordinates", JSONArray(line.map { listOf(it.longitude, it.latitude) })))
            }))
            current.updateGeoJson("places", featureCollection(pins.map { place ->
                pointFeature(place.latitude!!, place.longitude!!, JSONObject().put("placeId", place.id))
            }))
            current.updateGeoJson("osm-pois", featureCollection(overlayPlaces.map { place ->
                pointFeature(place.latitude, place.longitude, JSONObject().put("name", place.name)
                    .put("osmType", place.osmRef?.type?.name).put("osmId", place.osmRef?.id))
            }))
            val fix = if (followRecordedLocation) latestRoutePoint ?: location.coordinate else location.coordinate
            current.updateGeoJson("current", featureCollection(listOfNotNull(fix?.let { pointFeature(it.latitude, it.longitude) })))
        }
    }
    LaunchedEffect(message) { if (message != null) { delay(5_000); message = null } }
    Box(modifier) {
        OsmMapView(
            styleJson = styleJson,
            modifier = Modifier.fillMaxSize(),
            onReady = { map = it },
            onLongClick = onMapLongClick,
            onClick = { current, coordinate ->
                val screenPoint = current.projection.toScreenLocation(coordinate.mapCoordinate())
                val privateFeature = current.queryRenderedFeatures(screenPoint, "private-place-pins").firstOrNull()
                val privatePlace = privateFeature?.getStringProperty("placeId")?.let { id -> pins.firstOrNull { it.id == id } }
                val osmFeature = current.queryRenderedFeatures(screenPoint, "osm-poi-pins").firstOrNull()
                val osmPlace = osmFeature?.toJson()?.let(::parseRenderedOsmPlace)
                when {
                    privatePlace != null -> onPlaceClick(privatePlace)
                    chooseOsmPlace == null -> Unit
                    osmPlace != null -> chooseOsmPlace?.invoke(osmPlace)
                    current.cameraPosition.zoom < 16 -> message = "Yakındaki yerleri seçmek için haritayı yakınlaştır."
                    !loading -> {
                        loading = true
                        message = null
                        scope.launch {
                            try {
                                val result = service.nearby(coordinate.latitude, coordinate.longitude)
                                overlayPlaces = result
                                when (result.size) {
                                    0 -> message = "Yakında bir OSM yeri bulunamadı. Uzun basarak özel bir yer ekleyebilirsin."
                                    1 -> chooseOsmPlace?.invoke(result.single())
                                    else -> choices = result
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                message = (error as? OsmServiceException)?.message ?: "Yakındaki yerler alınamadı. Bağlantını kontrol edip yeniden dokun."
                            } finally { loading = false }
                        }
                    }
                }
                true
            },
        )
        if (loading) Surface(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 64.dp), shape = MaterialTheme.shapes.small) {
            CircularProgressIndicator(Modifier.padding(10.dp).size(22.dp), strokeWidth = 2.dp)
        }
        message?.let { text ->
            Surface(Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, top = 64.dp).clickable { message = null }, shape = MaterialTheme.shapes.small) {
                Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (choices.isNotEmpty()) AlertDialog(
        onDismissRequest = { choices = emptyList() },
        title = { Text("Yakındaki yerler") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { place ->
                    TextButton(onClick = { choices = emptyList(); chooseOsmPlace?.invoke(place) }, modifier = Modifier.fillMaxWidth()) {
                        Text(place.name, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choices = emptyList() }) { Text("Vazgeç") } },
    )
}

private const val diaryLayers = """
 {"id":"journey-lines","type":"line","source":"routes","layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"#2A6657","line-width":4}},
 {"id":"osm-poi-pins","type":"circle","source":"osm-pois","paint":{"circle-color":"#3989C9","circle-radius":7,"circle-stroke-color":"#FFFFFF","circle-stroke-width":2}},
 {"id":"private-place-pins","type":"circle","source":"places","paint":{"circle-color":"#2A6657","circle-radius":9,"circle-stroke-color":"#FFFFFF","circle-stroke-width":2}},
 {"id":"current-location","type":"circle","source":"current","paint":{"circle-color":"#1976D2","circle-radius":6,"circle-stroke-color":"#FFFFFF","circle-stroke-width":2}}
"""

private data class OpeningLocation(val permitted: Boolean, val coordinate: GeoCoordinate?)

/** One cancellable foreground fix per map opening; never starts journey recording. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberOpeningLocation(enabled: Boolean): OpeningLocation {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun hasPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var permitted by remember { mutableStateOf(hasPermission()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var coordinate by remember { mutableStateOf<GeoCoordinate?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permitted = hasPermission() }
    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permitted = hasPermission() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled) {
        if (enabled && !permitted && !requested) {
            requested = true
            launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
    LaunchedEffect(enabled, permitted) {
        if (!enabled || !permitted) return@LaunchedEffect
        val cancellation = CancellationTokenSource()
        try {
            val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0).setDurationMillis(20_000).build()
            val fix = LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancellation.token).await()
            if (fix != null && validCoordinate(fix.latitude, fix.longitude)) coordinate = GeoCoordinate(fix.latitude, fix.longitude)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep a usable map when location is disabled/unavailable. */ }
        finally { cancellation.cancel() }
    }
    return OpeningLocation(enabled && permitted, coordinate)
}

internal fun splitRouteSegments(points: List<TrackPoint>): List<List<GeoCoordinate>> {
    val segments = mutableListOf<List<GeoCoordinate>>()
    points.groupBy { it.journeyId }.values.forEach { journeyPoints ->
        var current = mutableListOf<GeoCoordinate>()
        var previous: TrackPoint? = null
        fun finish() {
            if (current.isNotEmpty()) segments += current.toList()
            current = mutableListOf()
            previous = null
        }
        journeyPoints.sortedBy { it.recordedAt }.forEach { point ->
            if (!DiaryRules.isUsablePoint(point)) finish()
            else {
                if (point.breakBefore || previous?.let { !DiaryRules.connects(it, point) } == true) finish()
                current += GeoCoordinate(point.latitude, point.longitude)
                previous = point
            }
        }
        finish()
    }
    return segments
}
