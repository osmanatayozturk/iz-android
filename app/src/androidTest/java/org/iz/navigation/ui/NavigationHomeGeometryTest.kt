package org.iz.navigation.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationHomeGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun idleHomeRestoresBrandAboveSearch() {
        renderIdle(360.dp, 560.dp)
        assertIdleBrandGeometry()
        captureHome("home-brand-small-360x560")
    }

    @Test fun idleBrandFitsLargeScreen() {
        renderIdle(412.dp, 840.dp)
        assertIdleBrandGeometry()
        captureHome("home-brand-large-412x840")
    }

    @Test fun idleBrandFitsLandscape() {
        renderIdle(640.dp, 360.dp)
        assertIdleBrandGeometry()
        captureHome("home-brand-landscape-640x360")
    }

    @Test fun idleBrandFitsShortLandscapeWithoutCoveringBottomActions() {
        renderIdle(640.dp, 320.dp)
        assertIdleBrandGeometry()
        captureHome("home-brand-landscape-640x320")
    }

    @Test fun idleBrandFitsLandscapeWithLargeFontWithoutCoveringBottomActions() {
        renderIdle(640.dp, 360.dp, fontScale = 1.5f)
        assertIdleBrandGeometry()
        captureHome("home-brand-landscape-large-font-640x360")
    }

    @Test fun idleBrandWrapsAtLargeFontWithoutCoveringControls() {
        renderIdle(360.dp, 560.dp, fontScale = 1.5f)
        assertIdleBrandGeometry()
        val title = compose.onNodeWithText("İz", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val slogan = compose.onNodeWithText("Küçük yollar, güzel anılar.", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val brand = compose.onNodeWithTag("home-brand").fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded slogan must remain inside the brand card", slogan.bottom <= brand.bottom)
        assertTrue("Slogan must be below the name", title.bottom <= slogan.top)
        captureHome("home-brand-large-font-360x560")
    }

    @Test fun brandIsOneAccessibleLabelWithNoDecorativeLogoDescription() {
        renderIdle(360.dp, 560.dp)
        val brand = compose.onNodeWithTag("home-brand").fetchSemanticsNode()
        assertEquals(listOf("İz", "Küçük yollar, güzel anılar."),
            brand.config[SemanticsProperties.Text].map { it.text })
        assertFalse("The logo must not add another spoken description", brand.config.contains(SemanticsProperties.ContentDescription))
        compose.onAllNodesWithText("İz", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Küçük yollar, güzel anılar.", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun brandHidesForPlanningGuidanceAndRecordingAndReturnsWhenIdle() {
        val nav = mutableStateOf(NavigationState())
        val planner = mutableStateOf(false)
        var recenterClicks = 0
        compose.setContent { IzTheme { Box(Modifier.requiredSize(360.dp, 560.dp)) {
            NavigationHomeContent(PhoneDirectionsState(), nav.value, emptyList(), emptyList(), planner.value, "Hava",
                { planner.value = true }, {}, {}, {}, {}, {}, {}, { recenterClicks++ }, {}, {})
        } } }
        compose.onNodeWithTag("home-brand").assertIsDisplayed()
        compose.onNodeWithTag("directions-recenter").performClick()
        compose.runOnIdle { assertEquals(1, recenterClicks) }
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("home-brand").assertDoesNotExist()
        compose.runOnIdle { planner.value = false }
        compose.onNodeWithTag("home-brand").assertIsDisplayed()
        listOf(
            NavigationState(guidance = true),
            NavigationState(recording = true, sessionTransport = Transport.WALK),
            NavigationState(sessionId = "free-journey", sessionTransport = Transport.CAR),
            NavigationState(locationActive = true),
        ).forEach { active ->
            compose.runOnIdle { nav.value = active }
            compose.onNodeWithTag("home-brand").assertDoesNotExist()
            compose.onNodeWithTag("open-navigation").assertIsDisplayed()
            compose.runOnIdle { nav.value = NavigationState() }
            compose.onNodeWithTag("home-brand").assertIsDisplayed()
        }
    }

    private fun renderIdle(width: Dp, height: Dp, fontScale: Float = 1f) {
        // Use a known density so portrait and landscape viewports both fit the emulator window.
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            IzTheme { Box(Modifier.requiredSize(width, height)) {
                NavigationHomeContent(PhoneDirectionsState(), NavigationState(), emptyList(), emptyList(), false, "Hava",
                    {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            } }
        } }
    }

    private fun assertIdleBrandGeometry() {
        compose.onNodeWithTag("home-brand").assertIsDisplayed()
        compose.onNodeWithText("İz", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Küçük yollar, güzel anılar.", useUnmergedTree = true).assertIsDisplayed()
        val brand = compose.onNodeWithTag("home-brand").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("open-navigation").fetchSemanticsNode().boundsInRoot
        val weather = compose.onNodeWithTag("home-weather").fetchSemanticsNode().boundsInRoot
        val home = compose.onNodeWithTag("navigation-home").fetchSemanticsNode().boundsInRoot
        val attribution = compose.onNodeWithText("© OpenStreetMap contributors").fetchSemanticsNode().boundsInRoot
        assertTrue("Brand must finish above search", brand.bottom <= search.top)
        assertFalse("Weather covers map attribution", weather.overlaps(attribution))
        listOf("directions-recenter", "map-layers", "home-record", "home-group", "home-menu").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must remain within the home viewport", home.contains(control.topLeft) && home.contains(control.bottomRight - androidx.compose.ui.geometry.Offset(1f, 1f)))
            assertFalse("Brand covers $tag", brand.overlaps(control))
            assertFalse("Search covers $tag", search.overlaps(control))
            assertFalse("Weather covers $tag", weather.overlaps(control))
        }
        listOf("directions-recenter", "map-layers").forEach { mapTag ->
            val mapControl = compose.onNodeWithTag(mapTag).fetchSemanticsNode().boundsInRoot
            listOf("home-record", "home-group", "home-menu").forEach { actionTag ->
                val action = compose.onNodeWithTag(actionTag).fetchSemanticsNode().boundsInRoot
                assertFalse("$mapTag covers $actionTag", mapControl.overlaps(action))
            }
        }
    }

    private fun captureHome(name: String) {
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(compose.onNodeWithTag("navigation-home").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

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
