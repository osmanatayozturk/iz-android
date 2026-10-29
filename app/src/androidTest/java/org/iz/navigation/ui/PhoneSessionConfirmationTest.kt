package org.iz.navigation.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import android.view.KeyEvent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.matcher.RootMatchers.isDialog as isDialogWindow
import androidx.test.espresso.matcher.ViewMatchers.isRoot as isRootView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSessionConfirmationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val uxHost = Ux0111ComposeHost(compose)
    @org.junit.After fun closeUxHost() = uxHost.close()
    private val nav = NavigationState(sessionId = "session-a", journey = Journey(id = "journey-a"),
        guidance = true, recording = true, sharingLocation = true, routeRevision = 7)

    @Test fun guidanceCancellationHasNoMutationAndApprovedBodyKeepsOtherWorkExplicit() {
        val visible = mutableStateOf(true)
        var confirms = 0
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.GUIDANCE, nav))
        uxHost.setContent { IzTheme {
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
        uxHost.setContent { IzTheme { PhoneSessionEndConfirmation(request, nav, false, {}, { delivered = it }) } }
        compose.onNodeWithText("Yolculuk kaydı bitecek. Yol tarifi devam edecek. Grup paylaşımı devam edecek.").assertIsDisplayed()
        compose.runOnIdle { assertNull(delivered) }
        compose.onNodeWithTag("confirm-session-end").performClick()
        compose.runOnIdle { assertEquals(request, delivered); assertEquals(PhoneSessionEnd.RECORDING, delivered?.action) }
    }

    @Test fun fullFinishExplainsAllActiveWorkAndBackDoesNotConfirm() {
        val visible = mutableStateOf(true)
        var confirms = 0
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, nav))
        uxHost.setContent { IzTheme {
            if (visible.value) PhoneSessionEndConfirmation(request, nav, false, { visible.value = false }, { confirms++ })
        } }
        compose.onNodeWithText("Yol tarifi bitecek. Yolculuk kaydı bitecek. Grup paylaşımı bitecek.").assertIsDisplayed()
        onView(isRootView()).inRoot(isDialogWindow()).perform(pressKey(KeyEvent.KEYCODE_BACK))
        compose.onNodeWithTag("confirm-session-end").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test fun changedSessionOrPromisedStateInvalidatesOpenConfirmation() {
        val current = mutableStateOf(nav)
        val visible = mutableStateOf(true)
        var confirms = 0
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, nav))
        uxHost.setContent { IzTheme {
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
        uxHost.setContent { IzTheme {
            if (visible.value) PhoneSessionEndConfirmation(request, nav, locked.value, { visible.value = false }, { confirms++ })
        } }
        compose.onNodeWithTag("confirm-session-end").assertIsEnabled()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithTag("confirm-session-end").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-session-end").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, confirms) }
    }
    @Test fun diaryChoicesKeepGuidanceRecordingAndAllSeparateAndCancelHasNoDispatch() {
        val visible = mutableStateOf(true)
        val target = requireNotNull(diarySessionEndTarget(requireNotNull(nav.journey), nav))
        var confirms = 0
        uxHost.setContent { IzTheme {
            if (visible.value) DiarySessionEndActions(target, nav.journey, nav, false,
                { visible.value = false }, { _, _ -> confirms++ })
        } }
        PhoneSessionEnd.entries.forEach { compose.onNodeWithTag("diary-end-choice-${it.name}").assertIsDisplayed() }
        compose.onNodeWithTag("diary-end-choice-GUIDANCE").performClick()
        compose.onNodeWithText("Yol tarifi bitecek. Yolculuk kaydı devam edecek. Grup paylaşımı devam edecek.").assertIsDisplayed()
        compose.onNodeWithTag("cancel-session-end").performClick()
        compose.runOnIdle { assertEquals(0, confirms) }
    }

    @Test fun diaryChoiceConfirmationRejectsReplacementBeforeDispatch() {
        val current = mutableStateOf(nav)
        val visible = mutableStateOf(true)
        val target = requireNotNull(diarySessionEndTarget(requireNotNull(nav.journey), nav))
        var confirms = 0
        uxHost.setContent { IzTheme {
            if (visible.value) DiarySessionEndActions(target, current.value.journey, current.value, false,
                { visible.value = false }, { _, _ -> confirms++ })
        } }
        compose.onNodeWithTag("diary-end-choice-RECORDING").performClick()
        compose.onNodeWithTag("confirm-session-end").assertIsEnabled()
        compose.runOnIdle { current.value = nav.copy(sessionId = "replacement") }
        compose.onNodeWithTag("confirm-session-end").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirms) }
    }
}
