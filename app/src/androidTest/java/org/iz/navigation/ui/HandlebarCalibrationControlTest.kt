package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.iz.navigation.lean.HandlebarCalibrationResult
import org.iz.navigation.lean.HandlebarCalibrationStep
import org.iz.navigation.lean.LeanCalibration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HandlebarCalibrationControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyboardCancelReturnsFocusToOpenerAndDoesNotSave() {
        var saves = 0
        var captures = 0
        compose.setContent { MaterialTheme {
            HandlebarCalibrationControl(false, { true }, { saves++ }, {}) { _, _, _ ->
                captures++
                error("Capture requires park confirmation and Start")
            }
        } }
        compose.onNodeWithTag("handlebar-calibration-open").performClick()
        val cancel = compose.onNodeWithTag("handlebar-calibration-cancel")
        cancel.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("handlebar-calibration-open").assertIsFocused()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, captures) }
    }

    @Test fun parkConfirmationAndExplicitSaveGateTheProfile() {
        val completed = CompletableDeferred<HandlebarCalibrationResult>()
        val profile = LeanCalibration("fixture", 1, listOf(0.0, 0.0, 9.8), listOf(0.0, 0.0, 0.0),
            bodyConfirmed = false, steeringAxis = listOf(0.0, 0.0, -1.0))
        var saved: LeanCalibration? = null
        compose.setContent { MaterialTheme {
            HandlebarCalibrationControl(false, { true }, { saved = it }, {}) { step, _, _ ->
                step(HandlebarCalibrationStep.LEFT)
                completed.await()
            }
        } }
        compose.onNodeWithTag("handlebar-calibration-open").performClick()
        compose.onNodeWithTag("handlebar-calibration-start").assertIsNotEnabled()
        compose.onNodeWithTag("handlebar-calibration-cancel").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        val park = compose.onNodeWithTag("handlebar-calibration-parked")
        park.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        park.assertIsOn().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("handlebar-calibration-start").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("handlebar-calibration-step").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved); completed.complete(HandlebarCalibrationResult.Ready(profile)) }
        compose.onNodeWithTag("handlebar-calibration-save").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("handlebar-calibration-open").assertIsFocused()
        compose.runOnIdle { assertSame(profile, saved) }
    }

    @Test fun cancelingAnActiveCaptureStopsItAndRestoresFocus() {
        var stopped = false
        compose.setContent { MaterialTheme {
            HandlebarCalibrationControl(false, { true }, { error("Canceled capture cannot save") }, {}) { step, _, _ ->
                step(HandlebarCalibrationStep.RIGHT)
                try { awaitCancellation() } finally { stopped = true }
            }
        } }
        compose.onNodeWithTag("handlebar-calibration-open").performClick()
        compose.onNodeWithTag("handlebar-calibration-parked").performScrollTo().performClick()
        compose.onNodeWithTag("handlebar-calibration-start").performScrollTo().performClick()
        compose.onNodeWithTag("handlebar-calibration-step").assertIsDisplayed()
        compose.onNodeWithTag("handlebar-calibration-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("handlebar-calibration-open").assertIsFocused()
        compose.onNodeWithTag("handlebar-calibration-save").assertDoesNotExist()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test fun aFailedRightStepRetriesInTheSameSessionAndKeepsThePreviousProfile() {
        var attempts = 0
        var retries = 0
        compose.setContent { MaterialTheme {
            HandlebarCalibrationControl(true, { true }, { error("No replacement profile") }, { error("No reset") }) { step, accepted, retry ->
                attempts++
                step(HandlebarCalibrationStep.LEFT)
                accepted()
                step(HandlebarCalibrationStep.RIGHT)
                retry(HandlebarCalibrationStep.RIGHT)
                retries++
                step(HandlebarCalibrationStep.RIGHT)
                awaitCancellation()
            }
        } }
        compose.onNodeWithTag("handlebar-calibration-open").performClick()
        compose.onNodeWithTag("handlebar-calibration-parked").performScrollTo().performClick()
        compose.onNodeWithTag("handlebar-calibration-start").performScrollTo().performClick()
        compose.onNodeWithText("Gidonu önceki sol konuma getir", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("handlebar-calibration-retry").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, attempts); assertEquals(1, retries) }
        compose.onNodeWithTag("handlebar-calibration-cancel").performScrollTo().performClick()
        compose.onNodeWithText("Gidon kalibrasyonu hazır.").assertIsDisplayed()
    }
}
