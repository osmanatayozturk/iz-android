package org.iz.navigation.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
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
        touch("handlebar-calibration-open")
        enterDirectionalMode("handlebar-calibration-cancel", KeyEvent.KEYCODE_TAB)
        pressOn("handlebar-calibration-cancel", KeyEvent.KEYCODE_ENTER)
        awaitFocus("handlebar-calibration-open")
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
        touch("handlebar-calibration-open")
        compose.onNodeWithTag("handlebar-calibration-start").assertIsNotEnabled()
        enterDirectionalMode("handlebar-calibration-cancel")
        pressOn("handlebar-calibration-cancel", KeyEvent.KEYCODE_DPAD_UP)
        pressOn("handlebar-calibration-parked", KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("handlebar-calibration-parked").assertIsOn()
        pressOn("handlebar-calibration-parked", KeyEvent.KEYCODE_DPAD_DOWN)
        pressOn("handlebar-calibration-start", KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("handlebar-calibration-step").assertIsDisplayed()
        captureEvidence(EvidenceFrame.STEP, compose.onNode(isDialog()))
        compose.runOnIdle { assertNull(saved); completed.complete(HandlebarCalibrationResult.Ready(profile)) }
        pressOn("handlebar-calibration-save", KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus("handlebar-calibration-open")
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
        touch("handlebar-calibration-open")
        touch("handlebar-calibration-parked", scroll = true)
        touch("handlebar-calibration-start", scroll = true)
        compose.onNodeWithTag("handlebar-calibration-step").assertIsDisplayed()
        touch("handlebar-calibration-cancel", scroll = true)
        compose.onNodeWithTag("handlebar-calibration-cancel").assertDoesNotExist()
        enterDirectionalMode("handlebar-calibration-open")
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
        touch("handlebar-calibration-open")
        touch("handlebar-calibration-parked", scroll = true)
        touch("handlebar-calibration-start", scroll = true)
        compose.onNodeWithText("Gidonu önceki sol konuma getir", substring = true).assertIsDisplayed()
        enterDirectionalMode("handlebar-calibration-retry")
        captureEvidence(EvidenceFrame.RETRY, compose.onNode(isDialog()))
        pressOn("handlebar-calibration-retry", KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, attempts); assertEquals(1, retries) }
        pressOn("handlebar-calibration-cancel", KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Gidon kalibrasyonu hazır.").assertIsDisplayed()
    }

    /** Follow the same window and touch-mode route as hardware keys and a real tap. */
    private fun enterDirectionalMode(tag: String, keyCode: Int = KeyEvent.KEYCODE_DPAD_UP) {
        awaitWindow(tag, inTouchMode = true)
        compose.onNodeWithTag(tag).assertIsNotFocused()
        sendKey(keyCode)
        awaitFocus(tag)
    }

    private fun pressOn(tag: String, keyCode: Int) {
        awaitFocus(tag)
        sendKey(keyCode)
    }

    private fun sendKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun awaitFocus(tag: String) {
        awaitWindow(tag, inTouchMode = false)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsFocused()
    }

    private fun awaitWindow(tag: String, inTouchMode: Boolean? = null) {
        compose.waitUntil(5_000) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull() ?: return@waitUntil false
            val view = (node.root as ViewRootForTest).view
            compose.runOnIdle { view.hasWindowFocus() && (inTouchMode == null || view.isInTouchMode == inTouchMode) }
        }
    }

    private fun touch(tag: String, scroll: Boolean = false) {
        awaitWindow(tag)
        val interaction = compose.onNodeWithTag(tag)
        if (scroll) interaction.performScrollTo()
        val node = interaction.assertIsDisplayed().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val (x, y) = compose.runOnIdle {
            check(view.hasWindowFocus())
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val center = node.boundsInRoot.center
            (center.x + origin[0]) to (center.y + origin[1])
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        compose.waitForIdle()
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
