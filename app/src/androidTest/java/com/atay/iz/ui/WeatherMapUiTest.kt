package com.atay.iz.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.integration.WeatherRouteMap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherMapUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tappingWeatherMapPreviewOpensFullscreen() {
        compose.setContent {
            IzTheme {
                WeatherRouteMap(
                    route = null,
                    assessment = null,
                    modifier = Modifier.fillMaxSize().testTag("weather_map_preview"),
                )
            }
        }

        compose.onNodeWithTag("weather_map_preview").performTouchInput { click(center) }
        compose.onNodeWithTag("fullscreen_map").assertIsDisplayed()
    }
}
