package org.iz.navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.iz.navigation.data.Place
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.integration.DiaryMap
import org.iz.navigation.integration.OsmServiceSettings
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.weather.RouteWeatherSample
import org.iz.navigation.weather.WeatherAssessment
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherReading
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Real DiaryMap/WeatherRouteMap in synthetic hosts, not MainActivity entry evidence. No database writes. */
@RunWith(AndroidJUnit4::class)
class DiaryMapSelectionPriorityTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 60_000)
    fun candidateTouchPreservesPrivatePinPriorityAndNativeLongPress() = nativePriority(useWeather = false)

    @Test(timeout = 60_000)
    fun candidateTouchPreservesWeatherSamplePriorityAndNativeLongPress() = nativePriority(useWeather = true)

    private fun nativePriority(useWeather: Boolean) {
        val app = instrumentation.targetContext.applicationContext
        OsmLightEditorQaTargetAdmission.requireAdmittedTarget()
        assertEquals("org.iz.navigation.qalighteditor20261113", app.packageName)
        @Suppress("DEPRECATION")
        assertFalse(Manifest.permission.INTERNET in app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty())
        val previous = main { MapLibre.getInstance(app); MapLibre.getModuleProvider() }
        val offline = MainMapOsmEditorEntryTest.NoNetworkMaps(previous)
        main { MapLibre.setModuleProvider(offline) }
        val place = Place(id = "qa-private-${UUID.randomUUID()}", name = "Synthetic private pin", latitude = 35.1234, longitude = 25.1234)
        val chosen = mutableListOf<Place>()
        val sample = RouteWeatherSample(WeatherCoordinate(place.latitude!!, place.longitude!!), 0.0, 1L,
            WeatherReading(null, null, null, null, null, null), emptySet(), false)
        val assessment = WeatherAssessment(1L, listOf(sample), 0.0, false, null, null, null, null, null, null)
        val sampleChosen = mutableListOf<RouteWeatherSample>()
        val longPresses = mutableListOf<GeoCoordinate>()
        var publicChosen = 0
        val mounted = mutableStateOf(true)
        try {
            compose.setContent { IzTheme {
                if (mounted.value) {
                    if (useWeather) WeatherRouteMap(null, assessment, Modifier.fillMaxSize(), onSampleClick = { sampleChosen += it },
                        onMapLongClick = { longPresses += it }, onOsmPlaceClick = { publicChosen++ }, expandable = false,
                        cameraViewportInsets = PaddingValues(16.dp))
                    else DiaryMap(emptyList(), listOf(place), Modifier.fillMaxSize(), onPlaceClick = { chosen += it },
                        onMapLongClick = { longPresses += it }, onOsmPlaceClick = { publicChosen++ }, expandable = false)
                }
            } }
            var view: MapView? = null
            compose.waitUntil(10_000) { main {
                view = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .single { it.packageName == app.packageName }.window.decorView.findMap()
                view?.width?.let { it > 0 } == true
            } }
            val actual = requireNotNull(view)
            val loaded = AtomicReference<MapLibreMap?>()
            main { actual.getMapAsync { loaded.set(it) } }
            compose.waitUntil(10_000) { main { loaded.get()?.style?.isFullyLoaded == true } }
            val map = requireNotNull(loaded.get())
            fun discover() {
                main { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.latitude!!, place.longitude!!), 18.0)) }
                compose.waitForIdle()
                val privatePixel = main { map.projection.toScreenLocation(LatLng(place.latitude!!, place.longitude!!)) }
                val tapPixel = PointF(privatePixel.x - 100, privatePixel.y - 100)
                val tapped = main { map.projection.fromScreenLocation(tapPixel) }
                val nonce = UUID.randomUUID().mostSignificantBits and 0x7fffffff
                val elements = JSONArray().put(JSONObject().put("type", "node").put("id", 8_500_000_000_000L + nonce)
                    .put("lat", place.latitude).put("lon", place.longitude).put("tags", JSONObject().put("name", "Synthetic overlapping candidate").put("amenity", "bench")))
                    .put(JSONObject().put("type", "node").put("id", 8_600_000_000_000L + nonce)
                        .put("lat", place.latitude!! + .0003).put("lon", place.longitude!! + .0003)
                        .put("tags", JSONObject().put("name", "Synthetic other candidate").put("amenity", "bench")))
                val key = "nearby:editor-v2:${OsmPresetCatalog.BUNDLED_SHA256}:${OsmServiceSettings(app).read().overpass}:${String.format(Locale.US, "%.4f", tapped.latitude)}:${String.format(Locale.US, "%.4f", tapped.longitude)}"
                // The second invocation uses exactly the admitted fixture, without replacing it.
                if (chosen.isEmpty() && sampleChosen.isEmpty()) MapSelectionCacheFixture.store(app, key, JSONObject().put("elements", elements).toString())
                compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(tapPixel.x, tapPixel.y)) }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("place-info-map-candidate-0").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("place-info-discovery-collapse").performClick()
            }
            discover()
            compose.onNodeWithTag("place-info-map-candidate-0").performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(if (useWeather) emptyList<Place>() else listOf(place), chosen)
                assertEquals(if (useWeather) listOf(sample) else emptyList<RouteWeatherSample>(), sampleChosen)
                assertEquals(0, publicChosen)
            }
            compose.onNodeWithTag("place-info-discovery").assertDoesNotExist()
            discover()
            compose.onNodeWithTag("place-info-map-candidate-0").performTouchInput { longClick() }
            compose.runOnIdle {
                assertEquals(1, longPresses.size)
                assertEquals(place.latitude!!, longPresses.single().latitude, .00003)
                assertEquals(place.longitude!!, longPresses.single().longitude, .00003)
                assertEquals(0, publicChosen)
            }
        } finally {
            // Dispose native views before draining the owned failure-only transport.
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            try { offline.drain(); offline.assertHealthy() }
            finally { main { MapLibre.setModuleProvider(previous) } }
        }
    }

    private fun View.findMap(): MapView? {
        if (this is MapView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findMap()?.let { return it }
        return null
    }
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
}
