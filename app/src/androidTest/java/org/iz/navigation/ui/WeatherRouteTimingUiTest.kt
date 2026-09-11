package org.iz.navigation.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RideWeatherLiveState
import org.iz.navigation.weather.RideWeatherStatus
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherRouteTimingUiTest {
    @get:Rule val compose = createComposeRule()
    private val origin = WeatherCoordinate(41.0, 29.0)
    private val destination = WeatherCoordinate(41.01, 29.01)
    private val route = PlannedRoute("timing", listOf(RouteStop("A", origin), RouteStop("B", destination)),
        listOf(RouteVertex(origin, 0.0), RouteVertex(destination, 600.0)), 1_400.0, 600.0, 1_000_000)

    @Test fun planTimingIsVisibleBeforeAnyWeatherAssessmentExists() {
        compose.setContent { IzTheme { WeatherRouteTimingOverlay(route, 1_000_000) } }
        compose.onNodeWithText("Tahmini süre: 10 dk").assertIsDisplayed()
        compose.onNodeWithText("Tahmini varış: ${weatherTime(1_600_000)}").assertIsDisplayed()
    }

    @Test fun fullscreenRetainsLiveDurationArrivalAndTheStaleGpsExplanation() {
        val live = mutableStateOf(RideWeatherLiveState(status = RideWeatherStatus.READY, journeyId = "journey",
            route = route, remainingMeters = 1_400.0, arrivalAt = 1_600_000,
            remainingSeconds = 600.0, timingUpdatedAt = 1_000_000))
        compose.setContent {
            IzTheme {
                WeatherRouteMap(route, null, Modifier.fillMaxSize().testTag("timing_map"), overlay = {
                    WeatherRouteTimingOverlay(route, 1_000_000, live.value)
                })
            }
        }
        compose.onNodeWithTag("timing_map").performTouchInput { click(center) }
        val fullscreen = hasAnyAncestor(hasTestTag("fullscreen_map"))
        compose.onNode(hasText("Kalan süre: 10 dk") and fullscreen).assertIsDisplayed()
        compose.runOnIdle { live.value = live.value.copy(remainingSeconds = 300.0, arrivalAt = 1_350_000,
            timingUpdatedAt = 1_050_000, gpsStale = true) }
        compose.onNode(hasText("Kalan süre: 5 dk") and fullscreen).assertIsDisplayed()
        compose.onNode(hasText("Son tahmini varış: ${weatherTime(1_350_000)}") and fullscreen).assertIsDisplayed()
        compose.onNode(hasText("Konum güncel değil; süre tahmini sabitlendi.") and fullscreen).assertIsDisplayed()
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "qa").apply { mkdirs() }
        File(directory, "v050-timing-fullscreen.png").outputStream().use { output ->
            check(compose.onNodeWithTag("fullscreen_map").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
