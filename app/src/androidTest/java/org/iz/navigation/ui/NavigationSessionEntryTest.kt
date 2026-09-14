package org.iz.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSessionEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bodyAndFinishShortcutOpenSamePanelWhileMuteStaysIndependent() {
        val nav = NavigationState(sessionId = "session-a", guidance = true)
        val panel = mutableStateOf(false)
        var opens = 0; var resets = 0; var mutes = 0
        val open = { openPhoneDirectionsPanel(nav, panel.value, { resets++ }, { opens++; panel.value = true }) }
        compose.setContent { IzTheme { Box(Modifier.requiredSize(360.dp, 560.dp)) {
            NavigationHomeContent(ui = PhoneDirectionsState(), nav = nav, points = emptyList(), members = emptyList(),
                showPlanner = panel.value, weatherLabel = "Hava", onSearch = open, onRecord = {}, onGroup = {},
                onMenu = {}, onWeather = {}, onMute = { mutes++ }, onManageSession = open, onLocate = {}, onPin = {}, planner = {})
        } } }
        compose.onNodeWithContentDescription("Sesi kapat").performClick()
        compose.runOnIdle { assertEquals(1, mutes); assertEquals(0, opens); assertFalse(panel.value) }
        assertEquals("Yolculuk işlemlerini aç", compose.onNodeWithTag("live-guidance-card")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("live-guidance-card").performClick()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, resets); assertTrue(panel.value); panel.value = false }
        compose.onNodeWithTag("home-finish-session").performClick()
        compose.runOnIdle { assertEquals(2, opens); assertEquals(0, resets); panel.value = false }
        compose.onNodeWithTag("open-navigation").performClick()
        compose.runOnIdle { assertEquals(3, opens); assertEquals(0, resets) }
    }
}
