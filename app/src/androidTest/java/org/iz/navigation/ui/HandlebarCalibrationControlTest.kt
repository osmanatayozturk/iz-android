package org.iz.navigation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.iz.navigation.lean.HandlebarCalibrationResult
import org.iz.navigation.lean.HandlebarCalibrationStep
import org.iz.navigation.lean.HandlebarLeanGate
import org.iz.navigation.lean.LeanCalibration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

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
        compose.setContent { EvidenceFixture {
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
        captureEvidence(EvidenceFrame.STEP, compose.onNode(isDialog()))
        compose.runOnIdle { assertNull(saved); completed.complete(HandlebarCalibrationResult.Ready(profile)) }
        compose.onNodeWithTag("handlebar-calibration-save").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("handlebar-calibration-open").assertIsFocused()
        compose.runOnIdle { assertSame(profile, saved) }
        captureEvidence(EvidenceFrame.RESTORED_FOCUS, compose.onNodeWithTag("handlebar-calibration-fixture"))
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
        compose.setContent { EvidenceFixture {
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
        val retryButton = compose.onNodeWithTag("handlebar-calibration-retry").assertIsFocused()
        captureEvidence(EvidenceFrame.RETRY, compose.onNode(isDialog()))
        retryButton.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, attempts); assertEquals(1, retries) }
        compose.onNodeWithTag("handlebar-calibration-cancel").performScrollTo().performClick()
        compose.onNodeWithText("Gidon kalibrasyonu hazır.").assertIsDisplayed()
    }

    private enum class EvidenceFrame(val filename: String) {
        STEP("01-handlebar-calibration-step.png"),
        RETRY("02-handlebar-calibration-retry.png"),
        RESTORED_FOCUS("03-handlebar-calibration-restored-focus.png"),
    }

    @Composable
    private fun EvidenceFixture(content: @Composable () -> Unit) {
        MaterialTheme {
            Surface {
                Column(Modifier.fillMaxWidth().testTag("handlebar-calibration-fixture").padding(16.dp), content = { content() })
            }
        }
    }

    /** The two callers mount synthetic content and crop only its Compose dialog or fixture. */
    private fun captureEvidence(frame: EvidenceFrame, node: SemanticsNodeInteraction) {
        if (!HandlebarLeanGate.enabled) return
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        check(app.packageName == "org.iz.navigation.qahandlebar20261118")
        val directory = File(app.cacheDir, "handlebar-calibration-qa/$evidenceRunId")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            check(bitmap.width in 1..4096 && bitmap.height in 1..4096)
            val png = File(directory, frame.filename)
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val evidenceRunId = UUID.randomUUID().toString()
    }
}
