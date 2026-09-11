package org.iz.navigation.ui

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
            progress = NavigationProgress(0.0, 600.0, 5000.0, 0.0, 0, 160.0))
        compose.setContent { IzTheme { Box(Modifier.requiredSize(360.dp, 560.dp)) {
            NavigationHomeContent(PhoneDirectionsState(), nav, emptyList(), emptyList(), false, "18° · Hava",
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } } }
        val card = compose.onNodeWithTag("live-guidance-card").fetchSemanticsNode().boundsInRoot
        val weather = compose.onNodeWithTag("home-weather").fetchSemanticsNode().boundsInRoot
        listOf("directions-recenter", "map-layers").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertFalse("Live guidance covers $tag", card.overlaps(control))
            assertFalse("Weather covers $tag", weather.overlaps(control))
        }
    }
}

