package org.iz.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotorcyclePhoneLayoutTest {
    @get:Rule val compose = createComposeRule()
    private fun render(width: Dp, height: Dp, live: Boolean) {
        val shortcuts = listOf(DestinationShortcut(DestinationShortcutKind.HOME,
            Place(id = "home", name = "Ev hedefi", latitude = 41.0, longitude = 29.0)))
        val nav = if (live) NavigationState(recording = true, journey = Journey(transport = Transport.MOTORCYCLE),
            sessionTransport = Transport.MOTORCYCLE, sessionId = "layout") else NavigationState()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            IzTheme { Box(Modifier.requiredSize(width, height)) {
                NavigationHomeContent(PhoneDirectionsState(), nav, emptyList(), emptyList(), false, "Hava",
                    {}, {}, {}, {}, {}, {}, {}, {}, {}, shortcuts, {}, {})
            } }
        } }
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    @Test fun shortcutOffsetKeepsLiveHeaderAndRightSideControlsApart() {
        render(360.dp, 640.dp, true)
        val search = bounds("open-navigation")
        val recenter = bounds("directions-recenter")
        val liveCard = bounds("live-guidance-card")
        assertTrue("Controls must start below shifted search header", recenter.top >= search.bottom)
        assertTrue("Live card must preserve the control lane", liveCard.right <= recenter.left)
    }
    @Test fun shortcutsInShortLandscapeKeepBothControlsAboveBottomActions() {
        render(640.dp, 320.dp, false)
        compose.onNodeWithTag("destination-shortcut-HOME").assertIsDisplayed()
        compose.onNodeWithTag("directions-recenter").assertIsDisplayed()
        compose.onNodeWithTag("map-layers").assertIsDisplayed()
        val search = bounds("open-navigation")
        val bottom = bounds("home-record")
        for (tag in listOf("directions-recenter", "map-layers")) {
            val control = bounds(tag)
            assertTrue(control.top >= search.bottom)
            assertTrue(control.bottom <= bottom.top)
        }
    }
}