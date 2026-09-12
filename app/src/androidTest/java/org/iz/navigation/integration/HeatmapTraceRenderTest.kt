package org.iz.navigation.integration

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.ui.IzTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Exercises the real MapLibre SDK and compositor; no map implementation is mocked. */
@RunWith(AndroidJUnit4::class)
class HeatmapTraceRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sdkAppliesTraceLayersAndSnapshotsSparseAndDenseFixturesAtThreeZooms() {
        val data = mutableStateOf(renderFixture(8.0, dense = false))
        val filterKey = mutableStateOf("render-initial")
        var activity: Activity? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { activity = context.activity() }
            IzTheme {
                HeatmapMap(data.value, filterKey.value, Modifier.requiredSize(360.dp, 560.dp),
                    expandable = false)
            }
        }
        val (view, map) = readyMap { activity }
        assertSdkStyle(map)

        listOf(8.0, 12.0, 16.0).forEach { zoom ->
            listOf(false, true).forEach { dense ->
                val fixture = renderFixture(zoom, dense)
                compose.runOnIdle {
                    data.value = fixture
                    filterKey.value = "render-z${zoom.toInt()}-${if (dense) "dense" else "sparse"}"
                }
                waitForSource(map, dense)
                compose.runOnIdle {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(RENDER_CENTER, zoom))
                    map.triggerRepaint()
                }
                waitForCamera(map, RENDER_CENTER, zoom)
                waitForRendered(view, map)
                val bitmap = snapshot(map)
                val file = saveSnapshot(bitmap, "heatmap-${if (dense) "dense" else "sparse"}-z${zoom.toInt()}.png")
                assertTrue("MapLibre snapshot was not persisted: ${file.absolutePath}", file.length() > 0)
                HEATMAP_RGB.forEach { rgb ->
                    assertTrue("${rgb.contentToString()} trace color missing at z$zoom; ${file.absolutePath}",
                        bitmap.countNear(rgb[0], rgb[1], rgb[2]) > 8)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun cameraFramesFirstDataAndNewFilterButSameFilterRefreshesNeverResetIt() {
        val data = mutableStateOf(HeatmapData())
        val filterKey = mutableStateOf("ALL")
        var activity: Activity? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { activity = context.activity() }
            IzTheme {
                HeatmapMap(data.value, filterKey.value, Modifier.requiredSize(360.dp, 560.dp),
                    expandable = false)
            }
        }
        val (view, map) = readyMap { activity }
        val first = traceData(39.920, 32.850)
        compose.runOnIdle { data.value = first }
        waitUntilVisible(view, map, first.lines.single().coordinates)

        val manual = LatLng(40.730, 30.270)
        compose.runOnIdle { map.moveCamera(CameraUpdateFactory.newLatLngZoom(manual, 11.25)) }
        waitForCamera(map, manual, 11.25)
        val refreshed = traceData(manual.latitude - .003, manual.longitude - .0045)
        compose.runOnIdle { data.value = refreshed }
        waitForSourceCoordinate(map, refreshed.lines.single().coordinates.last())
        assertCamera(map, manual, 11.25)

        compose.runOnIdle { data.value = HeatmapData() }
        waitForEmptySource(map)
        assertCamera(map, manual, 11.25)

        // Empty -> nonempty is still a refresh of ALL, so it must keep the user's camera.
        val sameFilterReturn = traceData(manual.latitude - .002, manual.longitude - .003)
        compose.runOnIdle { data.value = sameFilterReturn }
        waitForSourceCoordinate(map, sameFilterReturn.lines.single().coordinates.last())
        assertCamera(map, manual, 11.25)

        // An empty intervening filter still starts a new A filter session when returning to A.
        compose.runOnIdle {
            data.value = HeatmapData()
            filterKey.value = "WALK"
        }
        waitForEmptySource(map)
        assertCamera(map, manual, 11.25)
        compose.runOnIdle {
            data.value = first
            filterKey.value = "ALL"
        }
        waitUntilVisible(view, map, first.lines.single().coordinates)
        compose.runOnIdle {
            val cameraTarget = checkNotNull(map.cameraPosition.target)
            assertTrue("Returning after an empty filter must frame the active trace again",
                abs(cameraTarget.latitude - 39.923) < .02 && abs(cameraTarget.longitude - 32.8545) < .02)
        }
    }

    private fun assertSdkStyle(map: MapLibreMap) {
        compose.runOnIdle {
            val style = checkNotNull(map.style)
            val line = style.getLayer("journey-heatmap") as? LineLayer
            val points = style.getLayer("journey-heatmap-points") as? CircleLayer
            assertNotNull("journey-heatmap must be a native LineLayer", line)
            assertNotNull("journey-heatmap-points must be a native CircleLayer", points)
            assertFalse("Legacy heatmap layers must be absent", style.layers.any { it is HeatmapLayer })
            assertEquals("heatmap", line!!.sourceId)
            assertEquals("heatmap", points!!.sourceId)
            assertEquals(1f, line.lineOpacity.value!!, 0f)
            assertEquals(0f, line.lineBlur.value!!, 0f)
            assertEquals(1f, points.circleOpacity.value!!, 0f)
            assertEquals(0f, points.circleBlur.value!!, 0f)
            val width = line.lineWidth.expression!!.toArray().contentDeepToString()
            listOf("zoom", "8", "1", "12", "2", "16", "3").forEach {
                assertTrue("Unexpected native line-width expression: $width", width.contains(it))
            }
            val lineColor = line.lineColor.expression!!.toArray().contentDeepToString()
            val pointColor = points.circleColor.expression!!.toArray().contentDeepToString()
            listOf("get", "color").forEach { value ->
                assertTrue("Unexpected native line-color expression: $lineColor", lineColor.contains(value))
                assertTrue("Unexpected native point-color expression: $pointColor", pointColor.contains(value))
            }
            assertTrue(checkNotNull(line.filter).toArray().contentDeepToString().contains("LineString"))
            assertTrue(checkNotNull(points.filter).toArray().contentDeepToString().contains("Point"))
        }
    }

    private fun renderFixture(zoom: Double, dense: Boolean): HeatmapData {
        val degreesPerPixel = 360.0 / (256.0 * (1 shl zoom.toInt()))
        val halfSpan = degreesPerPixel * 105.0
        val rowGap = degreesPerPixel * 24.0
        val samples = if (dense) 25 else 2
        val counts = listOf(1, 2, 4, 8)
        val lines = counts.mapIndexed { row, count ->
            HeatmapTraceLine(
                coordinates = List(samples) { index ->
                    val fraction = index.toDouble() / (samples - 1)
                    GeoCoordinate(
                        RENDER_CENTER.latitude + (row - 1.5) * rowGap,
                        RENDER_CENTER.longitude - halfSpan + 2.0 * halfSpan * fraction,
                    )
                },
                journeyCount = count,
            )
        }
        return HeatmapData(
            lines = lines,
            isolatedPoints = listOf(HeatmapTracePoint(
                GeoCoordinate(RENDER_CENTER.latitude + 2.0 * rowGap, RENDER_CENTER.longitude),
                if (dense) 7 else 3)),
            journeyCount = 8,
        )
    }

    private fun traceData(latitude: Double, longitude: Double) = HeatmapData(
        lines = listOf(HeatmapTraceLine(listOf(
            GeoCoordinate(latitude, longitude),
            GeoCoordinate(latitude + .006, longitude + .009),
        ), journeyCount = 1)),
        isolatedPoints = listOf(HeatmapTracePoint(GeoCoordinate(latitude + .003, longitude + .0045), 1)),
        journeyCount = 1,
    )

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

    private fun waitForSource(map: MapLibreMap, dense: Boolean) {
        val pointMarker = if (dense) 7 else 3
        compose.waitUntil(10_000) {
            var matches = false
            compose.runOnIdle {
                val features = map.style?.getSourceAs<GeoJsonSource>("heatmap")?.querySourceFeatures(null).orEmpty()
                val json = features.map { JSONObject(it.toJson()) }
                val lines = json.filter { it.getJSONObject("geometry").getString("type") == "LineString" }
                val points = json.filter { it.getJSONObject("geometry").getString("type") == "Point" }
                val observed = json.map { feature ->
                    val properties = feature.getJSONObject("properties")
                    properties.getInt("journeyCount") to properties.getString("color").uppercase()
                }.toSet()
                matches = lines.isNotEmpty() && points.any {
                    it.getJSONObject("properties").getInt("journeyCount") == pointMarker
                } && EXPECTED_BANDS.all { it in observed }
            }
            matches
        }
    }

    private fun waitForRendered(view: MapView, map: MapLibreMap) {
        compose.waitUntil(10_000) {
            var rendered = false
            compose.runOnIdle {
                rendered = map.queryRenderedFeatures(
                    RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                    "journey-heatmap", "journey-heatmap-points",
                ).isNotEmpty()
            }
            rendered
        }
    }

    private fun waitForSourceCoordinate(map: MapLibreMap, coordinate: GeoCoordinate) {
        compose.waitUntil(10_000) {
            var found = false
            compose.runOnIdle {
                found = map.style?.getSourceAs<GeoJsonSource>("heatmap")?.querySourceFeatures(null).orEmpty()
                    .map { JSONObject(it.toJson()).getJSONObject("geometry").getJSONArray("coordinates") }
                    .any { coordinates -> hasCoordinate(coordinates, coordinate) }
            }
            found
        }
    }

    private fun waitForEmptySource(map: MapLibreMap) {
        compose.waitUntil(10_000) {
            var empty = false
            compose.runOnIdle {
                empty = map.style?.getSourceAs<GeoJsonSource>("heatmap")?.querySourceFeatures(null)?.isEmpty() == true
            }
            empty
        }
    }

    private fun hasCoordinate(values: org.json.JSONArray, coordinate: GeoCoordinate): Boolean {
        if (values.opt(0) is Number) return abs(values.getDouble(0) - coordinate.longitude) < .00005 &&
            abs(values.getDouble(1) - coordinate.latitude) < .00005
        return (0 until values.length()).any { hasCoordinate(values.getJSONArray(it), coordinate) }
    }

    private fun waitUntilVisible(view: MapView, map: MapLibreMap, coordinates: List<GeoCoordinate>) {
        compose.waitUntil(10_000) {
            var visible = false
            compose.runOnIdle {
                visible = coordinates.all {
                    val point = map.projection.toScreenLocation(LatLng(it.latitude, it.longitude))
                    point.x in 8f..(view.width - 8f) && point.y in 8f..(view.height - 8f)
                }
            }
            visible
        }
    }

    private fun waitForCamera(map: MapLibreMap, target: LatLng, zoom: Double) {
        compose.waitUntil(5_000) {
            var matches = false
            compose.runOnIdle {
                val camera = map.cameraPosition
                val cameraTarget = checkNotNull(camera.target)
                matches = abs(cameraTarget.latitude - target.latitude) < .0001 &&
                    abs(cameraTarget.longitude - target.longitude) < .0001 && abs(camera.zoom - zoom) < .01
            }
            matches
        }
    }

    private fun assertCamera(map: MapLibreMap, target: LatLng, zoom: Double) {
        compose.runOnIdle {
            val camera = map.cameraPosition
            val cameraTarget = checkNotNull(camera.target)
            assertEquals(target.latitude, cameraTarget.latitude, .0001)
            assertEquals(target.longitude, cameraTarget.longitude, .0001)
            assertEquals(zoom, camera.zoom, .01)
        }
    }

    private fun snapshot(map: MapLibreMap): Bitmap {
        val bitmap = AtomicReference<Bitmap>()
        compose.runOnIdle { map.snapshot { bitmap.set(it) } }
        compose.waitUntil(10_000) { bitmap.get() != null }
        return bitmap.get()
    }

    private fun saveSnapshot(bitmap: Bitmap, name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(checkNotNull(context.getExternalFilesDir(null)), name).also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun Bitmap.countNear(red: Int, green: Int, blue: Int): Int {
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = getPixel(x, y)
            if (abs(((pixel shr 16) and 255) - red) <= 8 &&
                abs(((pixel shr 8) and 255) - green) <= 8 && abs((pixel and 255) - blue) <= 8) count++
        }
        return count
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

    private companion object {
        val RENDER_CENTER = LatLng(39.9208, 32.8541)
        val HEATMAP_RGB = listOf(
            intArrayOf(57, 137, 201), intArrayOf(84, 190, 172),
            intArrayOf(240, 198, 93), intArrayOf(216, 97, 63),
        )
        val EXPECTED_BANDS = setOf(
            1 to "#3989C9", 2 to "#54BEAC", 4 to "#F0C65D", 8 to "#D8613F",
        )
    }
}
