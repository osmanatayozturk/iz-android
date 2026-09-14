package org.iz.navigation.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSessionConfirmationTest {
    @get:Rule val compose = createComposeRule()
    private val nav = NavigationState(sessionId = "session-a", journey = Journey(id = "journey-a"),
        guidance = true, recording = true, sharingLocation = true, routeRevision = 7)

    @Test fun guidanceCancellationHasNoMutationAndApprovedBodyKeepsOtherWorkExplicit() {
        val visible = mutableStateOf(true)
        var confirms = 0
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.GUIDANCE, nav))
        compose.setContent { IzTheme {
            if (visible.value) PhoneSessionEndConfirmation(request, nav, false, { visible.value = false }, { confirms++ })
        } }
        compose.onNodeWithText("Yol tarifi bitsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Yol tarifi bitecek. Yolculuk kaydı devam edecek. Grup paylaşımı devam edecek.").assertIsDisplayed()
        compose.onNodeWithTag("cancel-session-end").performClick()
        compose.onNodeWithTag("confirm-session-end").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test fun recordingConfirmationDeliversOnlyCapturedRecordingRequest() {
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.RECORDING, nav))
        var delivered: PhoneSessionEndRequest? = null
        compose.setContent { IzTheme { PhoneSessionEndConfirmation(request, nav, false, {}, { delivered = it }) } }
        compose.onNodeWithText("Yolculuk kaydı bitecek. Yol tarifi devam edecek. Grup paylaşımı devam edecek.").assertIsDisplayed()
        compose.runOnIdle { assertNull(delivered) }
        compose.onNodeWithTag("confirm-session-end").performClick()
        compose.runOnIdle { assertEquals(request, delivered); assertEquals(PhoneSessionEnd.RECORDING, delivered?.action) }
    }

    @Test fun fullFinishExplainsAllActiveWorkAndBackDoesNotConfirm() {
        val visible = mutableStateOf(true)
        var confirms = 0
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, nav))
        compose.setContent { IzTheme {
            if (visible.value) PhoneSessionEndConfirmation(request, nav, false, { visible.value = false }, { confirms++ })
        } }
        compose.onNodeWithText("Yol tarifi bitecek. Yolculuk kaydı bitecek. Grup paylaşımı bitecek.").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("confirm-session-end").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test fun changedSessionOrPromisedStateInvalidatesOpenConfirmation() {
        val current = mutableStateOf(nav)
        val visible = mutableStateOf(true)
        var confirms = 0
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, nav))
        compose.setContent { IzTheme {
            if (visible.value) PhoneSessionEndConfirmation(request, current.value, false, { visible.value = false }, { confirms++ })
        } }
        compose.onNodeWithTag("confirm-session-end").assertIsEnabled()
        compose.runOnIdle { current.value = nav.copy(sessionId = "session-b") }
        compose.onNodeWithTag("confirm-session-end").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test fun busyConfirmationCannotSubmitButCanAlwaysCancel() {
        val locked = mutableStateOf(false)
        val visible = mutableStateOf(true)
        var confirms = 0
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, nav))
        compose.setContent { IzTheme {
            if (visible.value) PhoneSessionEndConfirmation(request, nav, locked.value, { visible.value = false }, { confirms++ })
        } }
        compose.onNodeWithTag("confirm-session-end").assertIsEnabled()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithTag("confirm-session-end").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-session-end").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, confirms) }
    }
}
