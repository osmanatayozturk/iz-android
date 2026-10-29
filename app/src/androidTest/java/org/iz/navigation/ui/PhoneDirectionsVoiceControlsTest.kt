package org.iz.navigation.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.navigation.NavigationState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneDirectionsVoiceControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Ux0111ComposeHost(compose)
    @After fun closeHost() = host.close()
    @Test fun voiceControlsOccurOnceAfterEssentialSessionAndMuteActions() {
        host.setContent { IzTheme {
            PhoneDirectionsContent(ui = PhoneDirectionsState(), nav = NavigationState(sessionId = "qa-voice", guidance = true),
                points = emptyList(), commandBusy = false, onClose = {}, chooseOrigin = {}, chooseDestination = {},
                currentOrigin = {}, transport = {}, preview = {}, start = {}, freeDrive = {}, finish = {}, mute = {},
                stopGuidance = {}, showLive = {}, trafficSettings = {}, lastDestination = null, permissions = {}, showMap = false,
                voiceControls = { Text("VOICE CONTROLS", Modifier.testTag("voice-probe")) })
        } }
        compose.onNodeWithTag("voice-probe").performScrollTo()
        val voice = compose.onNodeWithTag("voice-probe").fetchSemanticsNode().boundsInRoot
        val mute = compose.onNodeWithText("Sesi kapat").fetchSemanticsNode().boundsInRoot
        assertTrue("Voice diagnostics must follow essential session/mute actions", voice.top >= mute.bottom)
        compose.onNodeWithText(PhoneSessionEnd.ALL.label).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice-probe").performScrollTo()
        compose.onAllNodesWithTag("voice-probe").assertCountEquals(1)
    }
}
