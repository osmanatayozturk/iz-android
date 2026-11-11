package org.iz.navigation.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.iz.navigation.car.SharedMapInput
import org.iz.navigation.car.SharedMapStop
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedLinkRetryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transientShortLinkFailureRetriesExplicitlyAndHandsOffWithoutStarting() {
        val input = SharedMapInput.ShortLink("https://maps.app.goo.gl/synthetic")
        val target = mutableStateOf<RouteStop?>(null)
        val navigation = MutableStateFlow(NavigationState())
        val original = navigation.value
        var attempts = 0
        var starts = 0
        compose.setContent { IzTheme {
            val selected = target.value
            if (selected == null) SharedMapEntryDialog(input, onClose = {},
                onSingle = { target.value = it }, onItinerary = { _, _ -> error("Unexpected itinerary") },
                resolve = { raw ->
                    assertEquals(input.url, raw)
                    attempts++
                    if (attempts == 1) SharedMapInput.Unsupported("Synthetic timeout", input.url)
                    else SharedMapInput.Single(SharedMapStop("Synthetic target", WeatherCoordinate(41.01, 29.01)))
                })
            else QuickDirectionsDialog(emptyList(), navigation,
                locate = { error("Receiving a target must not locate") }, validateOrigin = {},
                plan = { _, _, _, _ -> error("Receiving a target must not plan") },
                activate = { _, _, _ -> starts++; "unexpected-session" },
                onDismiss = {}, onStarted = {}, hasLocationPermission = { true },
                initialDestination = selected, sharedPreview = true)
        } }
        compose.onNodeWithText("Synthetic timeout").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, attempts); assertNull(target.value); assertEquals(original, navigation.value) }
        compose.onNodeWithTag("shared-link-retry").performClick()
        compose.onNodeWithTag("shared-preview").assertIsDisplayed()
        compose.onNodeWithTag("shared-start").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals("Synthetic target", target.value?.label)
            assertEquals(0, starts)
            assertEquals(original, navigation.value)
        }
    }

    @Test fun directUnsupportedLinkExplainsFailureWithoutShortLinkRetry() {
        var attempts = 0
        compose.setContent { IzTheme {
            SharedMapEntryDialog(SharedMapInput.Unsupported("Synthetic permanent failure"), onClose = {},
                onSingle = { error("Unexpected target") }, onItinerary = { _, _ -> error("Unexpected itinerary") },
                resolve = { attempts++; null })
        } }
        compose.onNodeWithText("Synthetic permanent failure").assertIsDisplayed()
        compose.onNodeWithTag("shared-link-retry").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, attempts) }
    }
}
