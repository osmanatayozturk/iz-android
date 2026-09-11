package org.iz.navigation.integration

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.iz.navigation.data.GeoCoordinate
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.UUID

/** SDK lifecycle belongs to the composition, including removal while its Activity stays resumed. */
@Composable
internal fun OsmMapView(
    styleJson: String,
    modifier: Modifier = Modifier,
    onReady: (MapLibreMap) -> Unit,
    onClick: (MapLibreMap, GeoCoordinate) -> Boolean = { _, _ -> false },
    onLongClick: (GeoCoordinate) -> Unit = {},
    attributionBottomInset: Dp = 4.dp,
    attributionAtStart: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = LocalSaveableStateRegistry.current
    val savedKey = rememberSaveable { "osm-map-${UUID.randomUUID()}" }
    val ready by rememberUpdatedState(onReady)
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val mapView = remember(context, styleJson) {
        OsmMapRuntime.initialize(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context)
            .setPrefetchZoomDelta(0).attributionEnabled(false).logoEnabled(false)
            .camera(CameraPosition.Builder().target(LatLng(39.0, 35.0)).zoom(4.5).build()))
            .also { it.onCreate(registry?.consumeRestored(savedKey) as? Bundle) }
    }
    DisposableEffect(mapView, lifecycle, registry) {
        var active = true
        var started = false
        var resumed = false
        fun start() { if (!started) { mapView.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { mapView.onResume(); resumed = true } }
        fun pause() { if (resumed) { mapView.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { mapView.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { mapView.onLowMemory() }
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory() }
        }
        val provider = registry?.registerProvider(savedKey) { Bundle().also(mapView::onSaveInstanceState) }
        context.registerComponentCallbacks(callbacks)
        lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            if (!active) return@getMapAsync
            map.setPrefetchZoomDelta(0)
            map.setMaxZoomPreference(19.0)
            map.addOnMapClickListener { coordinate -> click(map, GeoCoordinate(coordinate.latitude, coordinate.longitude)) }
            map.addOnMapLongClickListener { coordinate -> longClick(GeoCoordinate(coordinate.latitude, coordinate.longitude)); true }
            map.setStyle(Style.Builder().fromJson(styleJson)) { if (active) ready(map) }
        }
        onDispose {
            active = false
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
            provider?.unregister()
            stop()
            mapView.onDestroy()
        }
    }
    val uriHandler = LocalUriHandler.current
    Box(modifier) {
        key(mapView) { AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) }
        Text(
            "© OpenStreetMap contributors",
            modifier = Modifier.align(if (attributionAtStart) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(start = 4.dp, end = 4.dp, bottom = attributionBottomInset)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.93f))
                .clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal object OsmMapRuntime {
    private var initialized = false
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        val client = OkHttpClient.Builder()
            .cache(Cache(File(context.applicationContext.cacheDir, "osm_tiles_http"), 64L * 1024 * 1024))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", osmUserAgent).build())
            }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // Preserve server cache policy and validators. Only absent policy gets a seven-day fallback.
                if (response.isSuccessful && response.header("Cache-Control") == null && response.header("Expires") == null) {
                    response.newBuilder().header("Cache-Control", "public, max-age=604800").build()
                } else response
            }.build()
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        initialized = true
    }
}

internal fun GeoCoordinate.mapCoordinate() = LatLng(latitude, longitude)

internal fun frameCoordinates(map: MapLibreMap, coordinates: List<GeoCoordinate>, padding: Int, singleZoom: Double) =
    frameCoordinates(map, coordinates, List(4) { padding }, singleZoom)

/** Insets are left, top, right, bottom in screen pixels. */
internal fun frameCoordinates(map: MapLibreMap, coordinates: List<GeoCoordinate>, padding: List<Int>, singleZoom: Double) {
    val distinct = coordinates.distinct()
    if (distinct.isEmpty()) return
    val update = if (distinct.size == 1) CameraUpdateFactory.newLatLngZoom(distinct.single().mapCoordinate(), singleZoom)
    else CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(distinct.map { it.mapCoordinate() }).build(),
        padding[0], padding[1], padding[2], padding[3])
    map.moveCamera(update)
}

internal fun mapStyle(tileUrl: String, sources: List<String>, layers: String): String {
    val sourceJson = JSONObject().put("osm-tiles", JSONObject()
        .put("type", "raster").put("tiles", JSONArray().put(tileUrl)).put("tileSize", 256)
        .put("minzoom", 0).put("maxzoom", 19)
        .put("attribution", "© OpenStreetMap contributors"))
    sources.forEach { sourceJson.put(it, JSONObject().put("type", "geojson").put("data", JSONObject(emptyGeoJson))) }
    return JSONObject().put("version", 8).put("sources", sourceJson)
        .put("layers", JSONArray("""[{"id":"osm-raster","type":"raster","source":"osm-tiles"},$layers]""")).toString()
}

internal const val emptyGeoJson = "{\"type\":\"FeatureCollection\",\"features\":[]}"
internal fun featureCollection(features: List<JSONObject>): String = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString()
internal fun pointFeature(latitude: Double, longitude: Double, properties: JSONObject = JSONObject()): JSONObject =
    JSONObject().put("type", "Feature").put("properties", properties)
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(longitude).put(latitude)))

internal fun MapLibreMap.updateGeoJson(sourceId: String, json: String) {
    style?.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(json)
}

