package org.iz.navigation.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.*
import org.iz.navigation.speed.*
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationHomeGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveInstructionsAndWeatherNeverCoverRecenterOrLayers() {
        val a = RouteStop("Mevcut konum", WeatherCoordinate(41.0, 29.0))
        val b = RouteStop("Uzun hedef adı olan varış noktası", WeatherCoordinate(41.1, 29.1))
        val route = PlannedRoute("geometry", listOf(a, b),
            listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 600.0)),
            5000.0, 600.0, 1L, transport = Transport.CAR,
            maneuvers = listOf(RouteManeuver(1, "Bağdat Caddesi yönünde sağa dön ve sahil yoluna devam et", "Sağa dön", emptyList(), 0, 1, 0.0, 600.0)))
        val nav = NavigationState(sessionId = "live", sessionTransport = Transport.CAR, guidance = true, route = route,
            locationActive = true, gpsStale = false,
            fix = NavigationFix(a.coordinate, System.currentTimeMillis(), 5f),
            progress = NavigationProgress(0.0, 600.0, 5000.0, 0.0, 0, 160.0),
            roadSpeed = RoadSpeedState(0.0, RoadSpeedLimit(50.0, RoadSpeedSource.TOMTOM_POSTED, true,
                System.currentTimeMillis(), a.coordinate, Transport.CAR, "test-road"), true))
        compose.setContent { IzTheme { Box(Modifier.requiredSize(360.dp, 560.dp)) {
            NavigationHomeContent(PhoneDirectionsState(), nav, emptyList(), emptyList(), false, "18° · Hava",
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } } }
        val card = compose.onNodeWithTag("live-guidance-card").fetchSemanticsNode().boundsInRoot
        val weather = compose.onNodeWithTag("home-weather").fetchSemanticsNode().boundsInRoot
        val speed = compose.onNodeWithTag("road-speed-panel").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("0").assertIsDisplayed()
        compose.onNodeWithText("Genel yol sınırı · TomTom").assertIsDisplayed()
        listOf("directions-recenter", "map-layers").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertFalse("Live guidance covers $tag", card.overlaps(control))
            assertFalse("Weather covers $tag", weather.overlaps(control))
            assertFalse("Speed covers $tag", speed.overlaps(control))
        }
        val actions = compose.onNodeWithTag("home-record").fetchSemanticsNode().boundsInRoot
        assertFalse("Speed covers recording controls", speed.overlaps(actions))
        assertFalse("Weather covers recording controls", weather.overlaps(actions))
        val attribution = compose.onNodeWithText("© OpenStreetMap contributors").fetchSemanticsNode().boundsInRoot
        // Its semantics may include the bottom inset; horizontal separation protects the actual text too.
        assertFalse("Weather covers OSM attribution", weather.overlaps(attribution))
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "qa").apply { mkdirs() }
        File(directory, "speed-phone-360x560.png").outputStream().use {
            check(compose.onNodeWithTag("navigation-home").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
