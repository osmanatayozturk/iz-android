package org.iz.navigation.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HeatmapUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun passengerAndAllFiltersHaveSeparateEmptyStates() {
        compose.setContent { IzTheme { HeatmapScreen(DiaryState(), now = 100_000) } }
        compose.onNodeWithTag("heatmap_filter_PASSENGER").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Yolcu için henüz iz yok").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Yolcu için henüz iz yok").assertIsDisplayed()
        compose.onNodeWithTag("heatmap_filter_PASSENGER").assertIsSelected()
        compose.onNodeWithTag("heatmap_filter_all").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("İzlerin burada birikecek").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("İzlerin burada birikecek").assertIsDisplayed()
        compose.onNodeWithTag("heatmap_filter_all").assertIsSelected()
    }

    @Test fun latestRapidFilterOwnsResultAndLegendAppearsInPreviewAndFullscreen() {
        val carJourneys = (1..8).map { index ->
            Journey(id = "car-$index", transport = Transport.CAR, startedAt = 0, endedAt = 90_000)
        }
        val walk = Journey(id = "walk", transport = Transport.WALK, startedAt = 0, endedAt = 90_000)
        val carPoints = carJourneys.flatMapIndexed { journeyIndex, journey ->
            List(2_000) { pointIndex ->
                TrackPoint(
                    id = "${journey.id}-$pointIndex",
                    journeyId = journey.id,
                    latitude = 39.90 + journeyIndex * .00001,
                    longitude = 32.80 + pointIndex * .000002,
                    recordedAt = pointIndex * 1_000L,
                    accuracy = 5f,
                )
            }
        }
        val walkPoints = listOf(
            TrackPoint(id = "walk-a", journeyId = walk.id, latitude = 41.0200, longitude = 29.0000,
                recordedAt = 0, accuracy = 5f),
            TrackPoint(id = "walk-b", journeyId = walk.id, latitude = 41.0210, longitude = 29.0020,
                recordedAt = 60_000, accuracy = 5f),
        )
        compose.setContent {
            IzTheme { HeatmapScreen(DiaryState(carJourneys + walk, carPoints + walkPoints), now = 100_000) }
        }

        // Exercise cancellation/publication ordering while the much larger car result can still be computing.
        compose.onNodeWithTag("heatmap_filter_CAR").performClick()
        compose.onNodeWithTag("heatmap_filter_WALK").performClick()
        compose.onNodeWithTag("heatmap_filter_WALK").assertIsSelected()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("heatmap_legend").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("1 yolculuk", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("heatmap_legend").assertIsDisplayed()
        listOf("Geçiş sıklığı", "1", "2–3", "4–7", "8+").forEach { label ->
            compose.onNodeWithText(label, substring = false, useUnmergedTree = true).assertExists()
        }
        compose.onAllNodesWithText("8 yolculuk", substring = true).assertCountEquals(0)
        assertTrue(saveScreen("heatmap-preview.png").length() > 0)

        compose.onNodeWithContentDescription("Haritayı tam ekran aç").performClick()
        compose.onNodeWithTag("fullscreen_map").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("heatmap_legend").fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithTag("heatmap_legend").assertCountEquals(2)
        compose.onAllNodesWithText("Geçiş sıklığı", substring = false, useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithText("1 yolculuk", substring = true).assertCountEquals(2)
        waitForFullscreenTrace()
        assertTrue(saveScreen("heatmap-fullscreen.png").length() > 0)
    }

    private fun waitForFullscreenTrace() {
        val mapView = AtomicReference<MapView>()
        val map = AtomicReference<MapLibreMap>()
        onView(isAssignableFrom(MapView::class.java)).inRoot(isDialog()).check { view: View, missing ->
            if (missing != null) throw missing
            val native = view as MapView
            mapView.set(native)
            native.getMapAsync { map.set(it) }
        }
        compose.waitUntil(10_000) {
            var ready = false
            compose.runOnIdle {
                val current = map.get()
                val view = mapView.get()
                if (current?.style?.isFullyLoaded == true && view != null) {
                    val sourceReady = current.style?.getSourceAs<GeoJsonSource>("heatmap")
                        ?.querySourceFeatures(null).orEmpty().isNotEmpty()
                    val rendered = current.queryRenderedFeatures(
                        RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                        "journey-heatmap", "journey-heatmap-points",
                    ).isNotEmpty()
                    ready = sourceReady && rendered
                }
            }
            ready
        }
        compose.runOnIdle { map.get().triggerRepaint() }
    }

    private fun saveScreen(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        return File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name).also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
