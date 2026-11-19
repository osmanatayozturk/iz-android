package org.iz.navigation.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.HeatmapData
import org.iz.navigation.integration.buildHeatmapData
import org.iz.navigation.integration.prepareHeatmapRender
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

    @Test fun passengerFilterAppliesOnlyWhenConfirmedAndCanBeCleared() {
        val empty = prepareHeatmapRender(HeatmapData())
        var journeyListOpened = false
        compose.setContent {
            IzTheme { HeatmapScreenContent(DiaryState(), onOpenJourneys = { journeyListOpened = true }) { empty } }
        }
        compose.onNodeWithTag("heatmap_show_filters").performClick()
        compose.onNodeWithTag("heatmap_filter_PASSENGER").performScrollTo().performClick().assertIsSelected()
        pressBack()
        compose.onNodeWithText("İzlerin burada birikecek").assertIsDisplayed()

        applyFilter("heatmap_filter_PASSENGER")
        compose.onNodeWithText("Yolcu için henüz iz yok").assertIsDisplayed()
        compose.onNodeWithText("Filtreyi temizle").performClick()
        compose.onNodeWithText("İzlerin burada birikecek").assertIsDisplayed()
        compose.onNodeWithText("Yolculuklara dön").performClick()
        compose.runOnIdle { assertTrue(journeyListOpened) }
    }

    @Test fun periodAndTransportApplyTogetherAndClearTogether() {
        val empty = prepareHeatmapRender(HeatmapData())
        compose.setContent { IzTheme { HeatmapScreenContent(DiaryState()) { empty } } }
        compose.onNodeWithTag("heatmap_show_filters").performClick()
        compose.onNodeWithTag("heatmap_period_WEEK").performClick()
        pressBack()
        compose.onNodeWithText("Tüm kayıtlar · Tüm ulaşım türleri").assertIsDisplayed()
        compose.onNodeWithTag("heatmap_show_filters").performClick()
        compose.onNodeWithTag("heatmap_period_MONTH").performClick()
        compose.onNodeWithTag("heatmap_filter_WALK").performScrollTo().performClick()
        compose.onNodeWithTag("heatmap_apply_filters").performScrollTo().performClick()
        compose.onNodeWithText("Son 30 gün · ${Transport.WALK.label()}").assertIsDisplayed()
        compose.onNodeWithText("Bu dönemde seçimine uygun iz yok").assertIsDisplayed()
        compose.onNodeWithText("Filtreyi temizle").performClick()
        compose.onNodeWithText("Tüm kayıtlar · Tüm ulaşım türleri").assertIsDisplayed()
    }

    @Test fun latestAppliedFilterOwnsResultAndLegendAppearsInPreviewAndFullscreen() {
        val carJourneys = (1..8).map { index ->
            Journey(id = "car-$index", transport = Transport.CAR, startedAt = 0, endedAt = 90_000)
        }
        val walk = Journey(id = "walk", transport = Transport.WALK, startedAt = 0, endedAt = 90_000)
        val journeys = carJourneys + walk
        val points = journeys.flatMap { journey ->
            val latitude = if (journey.transport == Transport.CAR) 39.90 else 41.02
            listOf(
                TrackPoint(
                    id = "${journey.id}-a", journeyId = journey.id,
                    latitude = latitude, longitude = 32.8000, recordedAt = 0, accuracy = 5f,
                ),
                TrackPoint(
                    id = "${journey.id}-b", journeyId = journey.id,
                    latitude = latitude + .001, longitude = 32.801, recordedAt = 60_000, accuracy = 5f,
                ),
            )
        }
        val renders = listOf(null, Transport.CAR, Transport.WALK).associateWith { mode ->
            prepareHeatmapRender(buildHeatmapData(journeys, points, mode, now = 100_000))
        }
        compose.setContent {
            IzTheme { HeatmapScreenContent(DiaryState(journeys = journeys)) { filter -> renders[filter.mode] } }
        }

        applyFilter("heatmap_filter_CAR")
        compose.onNodeWithText("8 yolculuk", substring = true).assertExists()
        applyFilter("heatmap_filter_WALK")
        compose.onNodeWithTag("heatmap_legend").assertIsDisplayed()
        listOf("Geçiş sıklığı", "1", "2-3", "4-7", "8+").forEach { label ->
            compose.onNodeWithText(label, substring = false, useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText("1 yolculuk", substring = true).assertExists()
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

    private fun applyFilter(tag: String) {
        compose.onNodeWithTag("heatmap_show_filters").performClick()
        compose.onNodeWithTag(tag).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("heatmap_apply_filters").performScrollTo().performClick()
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
