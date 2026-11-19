package org.iz.navigation.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatistics
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.HeatmapData
import org.iz.navigation.integration.prepareHeatmapRender
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunningUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun runningCanBeSelectedWithLargeTextAndScrollsWithoutHidingConfirmation() {
        var chosen: Transport? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                IzTheme { TransportDialog(Transport.WALK, "Yeni bir yolculuk", {}, onChoose = { chosen = it }) }
            }
        }
        compose.onNodeWithTag("transport_options").assert(hasScrollAction())
        compose.onNodeWithText("Koşu").performScrollTo().performClick()
        compose.onNodeWithText("Kaydı başlat").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Transport.RUN, chosen) }
    }

    @Test fun runningEditorPreservesExistingJourneyAndMeasuredSteps() {
        val journey = Journey(transport = Transport.WALK, startedAt = 100, endedAt = 200, stepCount = 42)
        var edited: Journey? = null
        compose.setContent { IzTheme { JourneyEditor(journey, {}, { edited = it }) } }
        compose.onNodeWithText("Koşu").performClick()
        compose.onNodeWithText("Kaydet").performClick()
        compose.runOnIdle { assertEquals(journey.copy(transport = Transport.RUN), edited) }
    }

    @Test fun runningStatisticsShowPhoneStepsAndPaceIncludingStops() {
        val journey = Journey(id = "run", transport = Transport.RUN, startedAt = 0, endedAt = 60_000, stepCount = 120)
        val points = listOf(
            TrackPoint(journeyId = "run", latitude = 0.0, longitude = 0.0, accuracy = 5f, recordedAt = 0),
            TrackPoint(journeyId = "run", latitude = Math.toDegrees(100.0 / 6_371_000.0), longitude = 0.0, accuracy = 5f, recordedAt = 30_000),
            TrackPoint(journeyId = "run", latitude = Math.toDegrees(100.0 / 6_371_000.0), longitude = 0.0, accuracy = 5f, recordedAt = 60_000),
        )
        val stats = JourneyStatistics.calculate(journey, points)
        compose.setContent { IzTheme { JourneyStatsPanel(journey, stats, "Hazırlanıyor") } }
        compose.onNodeWithText("Ortalama tempo").assertExists()
        compose.onNodeWithText("10:00 dk/km").assertExists()
        compose.onNodeWithText("120 adım").assertExists()
    }

    @Test fun runningHeatmapFilterHasItsOwnEmptyState() {
        val empty = prepareHeatmapRender(HeatmapData())
        compose.setContent { IzTheme { HeatmapScreenContent(DiaryState()) { empty } } }
        compose.onNodeWithTag("heatmap_show_filters").performClick()
        compose.onNodeWithTag("heatmap_filter_RUN").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("heatmap_apply_filters").performScrollTo().performClick()
        compose.onNodeWithText("Koşu için henüz iz yok").assertIsDisplayed()
        compose.onNodeWithTag("heatmap_show_filters").performClick()
        compose.onNodeWithTag("heatmap_filter_WALK").performScrollTo().performClick()
        compose.onNodeWithTag("heatmap_apply_filters").performScrollTo().performClick()
        compose.onNodeWithText("Yürüyüş için henüz iz yok").assertIsDisplayed()
    }
}
