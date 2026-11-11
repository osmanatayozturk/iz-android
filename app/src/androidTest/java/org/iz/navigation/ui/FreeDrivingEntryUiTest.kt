package org.iz.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.speed.RoadSpeedState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreeDrivingEntryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Ux0111ComposeHost(compose)
    @org.junit.After fun closeHost() = host.close()
    @Test fun carRecordingWithoutGuidanceKeepsSearchShortcutsSpeedAndSession() = verify(Transport.CAR)
    @Test fun motorcycleRecordingWithoutGuidanceKeepsSearchShortcutsSpeedAndSession() = verify(Transport.MOTORCYCLE)
    private fun verify(mode: Transport) {
        val nav = NavigationState(sessionId = "synthetic-free", sessionTransport = mode, recording = true,
            guidance = false, locationActive = true, roadSpeed = RoadSpeedState(visible = true, ownKmh = 20.0))
        val shortcut = DestinationShortcut(DestinationShortcutKind.HOME,
            Place(id = "synthetic-home", name = "Sentetik ev", latitude = 41.0, longitude = 29.0))
        var searches = 0
        var selections = 0
        var mutations = 0
        host.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { IzTheme {
            Box(Modifier.requiredSize(360.dp, 720.dp)) {
                NavigationHomeContent(ui = PhoneDirectionsState(), nav = nav, points = emptyList(), members = emptyList(),
                    showPlanner = false, weatherLabel = "18°", onSearch = { searches++ }, onRecord = { mutations++ },
                    onGroup = {}, onMenu = {}, onWeather = {}, onMute = { mutations++ }, onManageSession = {},
                    onLocate = {}, onPin = {}, shortcuts = listOf(shortcut), onShortcut = { selections++ }, planner = {})
            }
        } } }
        compose.onNodeWithTag("open-navigation").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("destination-shortcut-HOME").assertIsDisplayed().performClick()
        compose.onNodeWithTag("road-speed-panel").assertIsDisplayed()
        compose.onNodeWithTag("home-record").assertTextContains("Kayıt açık")
        compose.onNodeWithTag("compact-card-mute").assertDoesNotExist()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, searches); assertEquals(1, selections); assertEquals(0, mutations)
            assertTrue(nav.recording); assertFalse(nav.guidance); assertEquals("synthetic-free", nav.sessionId)
        }
    }
}
