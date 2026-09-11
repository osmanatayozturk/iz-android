package com.atay.iz.integration

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import com.atay.iz.ui.NavigationHomeContent
import com.atay.iz.ui.PhoneDirectionsState
import com.atay.iz.navigation.NavigationState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atay.iz.ui.IzTheme
import com.atay.iz.data.TrackPoint
import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.RouteVertex
import com.atay.iz.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Checks pixels from the compositor, not merely GeoJSON or a registered style image. */
@RunWith(AndroidJUnit4::class)
class DirectionsMarkerRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editedEndpointsMoveBadgesWithinTheSameMountedMap() {
        val original = listOf(RouteStop("A", WeatherCoordinate(39.9208, 32.8541)),
            RouteStop("B", WeatherCoordinate(39.9308, 32.8641)))
        val stops = mutableStateOf(original)
        var activity: Activity? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { activity = context.activity() }
            IzTheme { WeatherRouteMap(null, null, Modifier.fillMaxSize().testTag("marker-map"),
                expandable = false, stops = stops.value, cameraIdentity = "same-mounted-map") }
        }
        val (mapView, map) = readyMap { activity }
        waitForBadgeAt(map, "B", original.last().coordinate)
        val before = compose.onNodeWithTag("directions-map-marker-B").fetchSemanticsNode().boundsInRoot
        val changed = original.map { it.copy(coordinate = it.coordinate.copy(longitude = it.coordinate.longitude - .002)) }
        compose.runOnIdle { stops.value = changed }
        waitForBadgeAt(map, "B", changed.last().coordinate)
        val after = compose.onNodeWithTag("directions-map-marker-B").fetchSemanticsNode().boundsInRoot
        assertTrue("Edited B must move without recreating the native map", abs(after.center.x - before.center.x) > 30f)
        compose.runOnIdle { assertTrue(activity?.window?.decorView?.findMap() === mapView) }
    }

    @Test fun routeFixAndRecordedGeometryRefreshWithinTheSameMountedMap() {
        val firstStops = listOf(RouteStop("A", WeatherCoordinate(39.9208, 32.8541)),
            RouteStop("B", WeatherCoordinate(39.9308, 32.8641)))
        fun route(id: String, stops: List<RouteStop>) = PlannedRoute(id, stops,
            stops.mapIndexed { index, stop -> RouteVertex(stop.coordinate, index * 600.0) }, 1_500.0, 600.0, 0L)
        fun points(longitude: Double) = listOf(
            TrackPoint(journeyId = "recorded", latitude = 39.924, longitude = longitude, recordedAt = 0L, accuracy = 5f),
            TrackPoint(journeyId = "recorded", latitude = 39.925, longitude = longitude, recordedAt = 60_000L, accuracy = 5f))
        val currentRoute = mutableStateOf(route("original", firstStops))
        val fix = mutableStateOf(firstStops.first().coordinate)
        val recorded = mutableStateOf(points(32.857))
        var activity: Activity? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { activity = context.activity() }
            IzTheme { WeatherRouteMap(currentRoute.value, null, Modifier.fillMaxSize(), expandable = false,
                liveCoordinate = fix.value, recordedPoints = recorded.value, cameraIdentity = "same-mounted-map") }
        }
        val (mapView, map) = readyMap { activity }
        waitForSourceAt(map, "weather-planned-route", firstStops.last().coordinate)
        waitForSourceAt(map, "directions-current-fix", fix.value)
        waitForSourceAt(map, "directions-recorded-route", WeatherCoordinate(39.925, 32.857))
        val changedStops = firstStops.map { it.copy(coordinate = it.coordinate.copy(longitude = it.coordinate.longitude - .001)) }
        val changedFix = WeatherCoordinate(39.926, 32.858)
        compose.runOnIdle {
            currentRoute.value = route("refreshed", changedStops)
            fix.value = changedFix
            recorded.value = points(32.858)
        }
        waitForSourceAt(map, "weather-planned-route", changedStops.last().coordinate)
        waitForSourceAt(map, "weather-planned-stops", changedStops.last().coordinate)
        waitForSourceAt(map, "directions-current-fix", changedFix)
        waitForSourceAt(map, "directions-recorded-route", WeatherCoordinate(39.925, 32.858))
        compose.runOnIdle { assertTrue(activity?.window?.decorView?.findMap() === mapView) }
    }

    @Test fun plannerInitialAndExplicitFitProjectEndpointsIntoExposedMapViewport() {
        val a = RouteStop("A", WeatherCoordinate(39.92, 32.85))
        val b = RouteStop("B", WeatherCoordinate(39.95, 32.85))
        var activity: Activity? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { activity = context.activity() }
            IzTheme { Box(Modifier.requiredSize(360.dp, 640.dp)) {
                NavigationHomeContent(PhoneDirectionsState(origin = a, destination = b, preview = PlannedRoute("viewport", listOf(a, b), listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 600.0)), 3_300.0, 600.0, 0L)),
                    NavigationState(), emptyList(), emptyList(), true, "Hava",
                    {}, {}, {}, {}, {}, {}, {}, {}, {},
                    { Text("Planner", Modifier.fillMaxSize().testTag("planner-obstruction")) })
            } }
        }
        val (_, map) = readyMap { activity }
        fun endpointsVisible(): Boolean {
            val mapBounds = compose.onNodeWithTag("navigation-home-map").fetchSemanticsNode().boundsInRoot
            val search = compose.onNodeWithTag("open-navigation").fetchSemanticsNode().boundsInRoot
            val planner = compose.onNodeWithTag("planner-obstruction").fetchSemanticsNode().boundsInRoot
            val layers = compose.onNodeWithTag("map-layers").fetchSemanticsNode().boundsInRoot
            var projected = emptyList<PointF>()
            compose.runOnIdle {
                projected = listOf(a, b).map { map.projection.toScreenLocation(LatLng(it.coordinate.latitude, it.coordinate.longitude)) }
            }
            return listOf(a, b).mapIndexed { index, stop ->
                val x = mapBounds.left + projected[index].x
                val y = mapBounds.top + projected[index].y
                val badge = compose.onAllNodesWithTag("directions-map-marker-${stop.label}")
                    .fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                badge != null && badge.top > search.bottom && badge.bottom < planner.top &&
                    x > mapBounds.left && x < layers.left && y > search.bottom && y < planner.top
            }.all { it }
        }
        compose.waitUntil(10_000) { endpointsVisible() }
        compose.runOnIdle { map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(40.1, 32.85), 14.0)) }
        compose.onNodeWithTag("map-layers").performClick()
        compose.onNodeWithTag("directions-fit-route").performClick()
        compose.waitUntil(5000) { endpointsVisible() }
    }
    @Test fun verticalItineraryKeepsNorthernBadgeFullyInsideMap() {
        var density = 1f
        compose.setContent {
            density = LocalContext.current.resources.displayMetrics.density
            IzTheme {
                WeatherRouteMap(null, null, Modifier.fillMaxSize(), expandable = false,
                    stops = listOf(RouteStop("A", WeatherCoordinate(39.92, 32.85)),
                        RouteStop("B", WeatherCoordinate(39.95, 32.85))))
            }
        }
        compose.waitUntil(10_000) {
            val badges = compose.onAllNodesWithTag("directions-map-marker-B").fetchSemanticsNodes()
            badges.size == 1 && badges.single().boundsInRoot.height >= 28f * density - 1f
        }
        val badge = compose.onNodeWithTag("directions-map-marker-B").fetchSemanticsNode().boundsInRoot
        assertTrue("Northern endpoint label must keep its full height after framing", badge.height >= 28f * density - 1f)
    }

    @Test fun bothEndpointBadgesArePaintedEvenWhenLiveFixSharesOrigin() {
        val a = RouteStop("A", WeatherCoordinate(39.9208, 32.8541))
        val b = RouteStop("B", WeatherCoordinate(39.9308, 32.8641))
        var activity: Activity? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { activity = context.activity() }
            IzTheme {
                WeatherRouteMap(null, null, Modifier.fillMaxSize().testTag("marker-map"), expandable = false,
                    stops = listOf(a, b), liveCoordinate = a.coordinate)
            }
        }
        val map = AtomicReference<MapLibreMap>()
        var mapView: MapView? = null
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                mapView = activity?.window?.decorView?.findMap()
                mapView?.getMapAsync { map.set(it) }
            }
            map.get() != null
        }
        var greenPixels = 0
        var redPixels = 0
        var diagnostic = ""
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rendering = runCatching {
            compose.waitUntil(10_000) {
                var aPoint = PointF()
                var bPoint = PointF()
                compose.runOnIdle {
                    val current = map.get()
                    val offset = IntArray(2)
                    mapView!!.getLocationOnScreen(offset)
                    fun projected(stop: RouteStop) = current.projection.toScreenLocation(
                        LatLng(stop.coordinate.latitude, stop.coordinate.longitude)).apply {
                        x += offset[0]; y += offset[1]
                    }
                    aPoint = projected(a)
                    bPoint = projected(b)
                    diagnostic = "A=$aPoint B=$bPoint"
                }
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                greenPixels = screenshot.countColorNear(aPoint, 40, 96, 77)
                redPixels = screenshot.countColorNear(bPoint, 133, 62, 43)
                screenshot.recycle()
                greenPixels > 100 && redPixels > 100
            }
            true
        }
        if (rendering.isFailure) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "directions-marker-render-failure.png")
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            val aBounds = compose.onAllNodesWithTag("directions-map-marker-A").fetchSemanticsNodes().map { it.boundsInRoot }
            val bBounds = compose.onAllNodesWithTag("directions-map-marker-B").fetchSemanticsNodes().map { it.boundsInRoot }
            diagnostic += "; density=${instrumentation.targetContext.resources.displayMetrics.density}; Compose A=$aBounds B=$bBounds; screenshot=${file.absolutePath}"
            throw AssertionError("Endpoint badges are absent from GPU output: A=$greenPixels B=$redPixels; $diagnostic", rendering.exceptionOrNull())
        }
        val before = compose.onNodeWithTag("directions-map-marker-B").fetchSemanticsNode().boundsInRoot
        var beforePoint = PointF()
        compose.runOnIdle { beforePoint = map.get().projection.toScreenLocation(LatLng(b.coordinate.latitude, b.coordinate.longitude)) }
        swipeNativeMap(mapView!!)
        val pan = runCatching {
            compose.waitUntil(5_000) {
                var currentPoint = PointF()
                compose.runOnIdle { currentPoint = map.get().projection.toScreenLocation(LatLng(b.coordinate.latitude, b.coordinate.longitude)) }
                val after = compose.onNodeWithTag("directions-map-marker-B").fetchSemanticsNode().boundsInRoot
                abs(currentPoint.x - beforePoint.x) > 30f && abs(after.center.x - before.center.x) > 30f
            }
        }
        if (pan.isFailure) {
            var afterPoint = PointF()
            compose.runOnIdle { afterPoint = map.get().projection.toScreenLocation(LatLng(b.coordinate.latitude, b.coordinate.longitude)) }
            val after = compose.onNodeWithTag("directions-map-marker-B").fetchSemanticsNode().boundsInRoot
            throw AssertionError("Camera pan did not move B badge: SDK before=$beforePoint after=$afterPoint; badge before=$before after=$after", pan.exceptionOrNull())
        }
        compose.waitUntil(5_000) {
            var point = PointF()
            compose.runOnIdle { point = map.get().projection.toScreenLocation(LatLng(b.coordinate.latitude, b.coordinate.longitude)) }
            val badge = compose.onNodeWithTag("directions-map-marker-B").fetchSemanticsNode().boundsInRoot
            val mapBounds = compose.onNodeWithTag("marker-map").fetchSemanticsNode().boundsInRoot
            abs(badge.center.x - mapBounds.left - point.x) < 3f && badge.bottom < mapBounds.top + point.y
        }
    }

    private fun swipeNativeMap(mapView: MapView) {
        val location = IntArray(2)
        var width = 0
        var height = 0
        compose.runOnIdle {
            mapView.getLocationOnScreen(location)
            width = mapView.width
            height = mapView.height
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downAt = SystemClock.uptimeMillis()
        val y = location[1] + height * .55f
        fun inject(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                location[0] + width * fraction, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Platform touch must reach the native map", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, .70f)
        for (step in 1..20) {
            SystemClock.sleep(20)
            inject(MotionEvent.ACTION_MOVE, .70f - .25f * step / 20f)
        }
        inject(MotionEvent.ACTION_UP, .45f)
    }

    private fun readyMap(activity: () -> Activity?): Pair<MapView, MapLibreMap> {
        var view: MapView? = null
        val map = AtomicReference<MapLibreMap>()
        compose.waitUntil(10_000) {
            var loaded = false
            compose.runOnIdle {
                view = activity()?.window?.decorView?.findMap()
                view?.getMapAsync { map.set(it) }
                loaded = map.get()?.style?.isFullyLoaded == true
            }
            loaded
        }
        return view!! to map.get()
    }

    private fun waitForBadgeAt(map: MapLibreMap, label: String, coordinate: WeatherCoordinate) {
        compose.waitUntil(10_000) {
            var point = PointF()
            compose.runOnIdle { point = map.projection.toScreenLocation(LatLng(coordinate.latitude, coordinate.longitude)) }
            val badge = compose.onAllNodesWithTag("directions-map-marker-$label").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val bounds = compose.onNodeWithTag("marker-map").fetchSemanticsNode().boundsInRoot
            badge != null && badge.height > 0f && abs(badge.center.x - bounds.left - point.x) < 3f && badge.bottom < bounds.top + point.y
        }
    }

    private fun waitForSourceAt(map: MapLibreMap, source: String, coordinate: WeatherCoordinate) {
        var observed = ""
        val result = runCatching {
            compose.waitUntil(10_000) {
                var matches = false
                compose.runOnIdle {
                    val features = map.style?.getSourceAs<GeoJsonSource>(source)?.querySourceFeatures(null).orEmpty()
                    observed = features.joinToString { it.toJson() }
                    matches = features.any { hasCoordinate(JSONObject(it.toJson()).getJSONObject("geometry").getJSONArray("coordinates"), coordinate) }
                }
                matches
            }
        }
        if (result.isFailure) throw AssertionError("Native source $source did not update to $coordinate; observed=$observed", result.exceptionOrNull())
    }

    private fun hasCoordinate(values: JSONArray, coordinate: WeatherCoordinate): Boolean {
        if (values.opt(0) is Number) return abs(values.getDouble(0) - coordinate.longitude) < .00005 &&
            abs(values.getDouble(1) - coordinate.latitude) < .00005
        return (0 until values.length()).any { hasCoordinate(values.getJSONArray(it), coordinate) }
    }

    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
    private fun View.findMap(): MapView? {
        if (this is MapView) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).findMap()?.let { return it }
        return null
    }
    private fun Bitmap.countColorNear(point: PointF, red: Int, green: Int, blue: Int): Int {
        var count = 0
        val centerX = point.x.roundToInt()
        val centerY = point.y.roundToInt()
        for (y in (centerY - 64).coerceAtLeast(0)..(centerY + 64).coerceAtMost(height - 1)) {
            for (x in (centerX - 64).coerceAtLeast(0)..(centerX + 64).coerceAtMost(width - 1)) {
                val pixel = getPixel(x, y)
                if (abs(((pixel shr 16) and 255) - red) <= 5 && abs(((pixel shr 8) and 255) - green) <= 5 &&
                    abs((pixel and 255) - blue) <= 5) count++
            }
        }
        return count
    }
}


