package org.iz.navigation.lean

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.iz.navigation.MainActivity
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.LeanImuCheckDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeanDiagnosticEntryAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun normalSettingsOpensSensorCheckAndGpsComparison() {
        val context = requireLeanDiagnosticEmulator()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("home-menu").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-calibrate"))
            compose.onNodeWithTag("settings-open-calibrate").performClick()

            compose.onNodeWithTag("lean-imu-check-open").performScrollTo().performClick()
            compose.onNodeWithTag("lean-imu-check").assertIsDisplayed()
            compose.onNodeWithTag("lean-imu-check-start").assertIsNotEnabled()
            pressBack()
            compose.onNodeWithTag("lean-imu-check").assertDoesNotExist()

            compose.onNodeWithTag("location-comparison-open").performScrollTo().performClick()
            compose.onNodeWithTag("location-comparison-panel").assertIsDisplayed()
            compose.onNodeWithTag("location-comparison-start").assertIsNotEnabled()
            compose.onNodeWithTag("location-comparison-fused-summary").assertExists()
            compose.onNodeWithTag("location-comparison-gps-summary").assertExists()
            compose.onNodeWithTag("location-comparison-result").assertTextContains("Ölçüm başlamadı.")
            compose.onNodeWithTag("location-comparison-close").performScrollTo().performClick()
            compose.onNodeWithTag("location-comparison-panel").assertDoesNotExist()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class LeanImuDiagnosticLifecycleAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parkedConsentTwoSensorReadoutAndBackCancelTheSyntheticStream() {
        requireLeanDiagnosticEmulator()
        val launches = AtomicInteger()
        val closed = AtomicInteger()
        val updates = Channel<LeanImuCheckProgress>(Channel.UNLIMITED)
        val kinds = LeanSensorKind.entries
        val registration = kinds.associateWith { LeanImuCheckRegistration.LISTENING }
        fun progress(remaining: Long, accel: Long, gyro: Long, complete: Boolean) =
            LeanImuCheckProgress(
                remaining,
                registration,
                mapOf(
                    LeanSensorKind.ACCELEROMETER to LeanImuCheckCounts(accel, accel),
                    LeanSensorKind.GYROSCOPE to LeanImuCheckCounts(gyro, gyro),
                ),
                complete,
            )

        var open by mutableStateOf(true)
        var safe by mutableStateOf(true)
        compose.setContent {
            IzTheme {
                if (!open) TextButton(onClick = { open = true }) { Text("QA open") }
                if (open) LeanImuCheckDialog(
                    canStart = safe,
                    startAllowed = { safe },
                    test = {
                        flow {
                            launches.incrementAndGet()
                            try {
                                for (item in updates) {
                                    emit(item)
                                    if (item.complete) break
                                }
                            } finally {
                                closed.incrementAndGet()
                            }
                        }
                    },
                    onClose = { open = false },
                )
            }
        }

        fun start() {
            val start = compose.onNodeWithTag("lean-imu-check-start")
            start.assertIsNotEnabled()
            compose.onNodeWithTag("lean-imu-check-parked").performClick()
            start.assertIsEnabled().performClick()
        }
        fun send(update: LeanImuCheckProgress) {
            check(updates.trySend(update).isSuccess)
        }
        fun waitForText(text: String) {
            compose.waitUntil(4_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }

        assertEquals(0, launches.get())
        start()
        send(progress(10_000_000_000L, 7L, 5L, complete = false))
        compose.onNodeWithTag("lean-imu-check-remaining").assertTextContains("Kalan 10 sn")
        waitForText("İvmeölçer · 7 örnek")
        compose.onNodeWithText("Jiroskop · 5 örnek").assertExists()
        pressBack()
        compose.waitUntil(4_000) { closed.get() == 1 }
        compose.onNodeWithTag("lean-imu-check").assertDoesNotExist()

        compose.onNodeWithText("QA open").performClick()
        start()
        send(progress(0L, 49L, 0L, complete = true))
        waitForText("İvmeölçer · 49 örnek alındı")
        compose.onNodeWithText("Jiroskop · Örnek gelmedi").assertExists()
        compose.onNodeWithTag("lean-imu-check-remaining").assertDoesNotExist()
        compose.waitUntil(4_000) { closed.get() == 2 }

        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithText("QA open").performClick()
        start()
        send(progress(8_000_000_000L, 2L, 1L, complete = false))
        waitForText("Kalan 8 sn")
        compose.runOnIdle { safe = false }
        compose.waitUntil(4_000) { closed.get() == 3 }
        compose.onNodeWithTag("lean-imu-check-status").assertTextContains("test durdu", substring = true)
        compose.onNodeWithTag("lean-imu-check-start").assertIsNotEnabled()
        assertEquals(3, launches.get())
        updates.close()
    }
}
