package com.atay.iz.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atay.iz.data.*
import com.atay.iz.MainActivity
import com.atay.iz.tracking.TrackingController
import com.atay.iz.tracking.TrackingService
import com.atay.iz.wearprotocol.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class WearStartFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun phoneConfirmationStartsActualServiceAndConsumesRequest() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = TrackingController(context)
        val repository = DiaryRepository(context)
        val bridge = WearPhoneBridge.get(context)
        controller.disableDetection()
        repository.activeJourney()?.let { controller.finish(it.id) }
        repository.restore(DiarySnapshot())
        context.getSharedPreferences("wear_commands_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        fun notificationMode(mode: String) {
            automation.executeShellCommand("appops set ${context.packageName} POST_NOTIFICATION $mode").use { fd ->
                FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            }
        }
        notificationMode("ignore")
        val request = WearCommand(UUID.randomUUID().toString(), WearAction.START, System.currentTimeMillis(), WearMode.PASSENGER)
        try {
            assertEquals(WearResultCode.NEEDS_PHONE, bridge.handle("test-watch", request, false).code)
            val key = bridge.pendingKey()!!
            // With notifications off, opening the real phone app must reveal the stored request.
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Başlat").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Başlat").performClick()
                // onCreate starts foreground before the asynchronous ACTION_START attaches a journey.
                withTimeout(15_000) {
                    while (true) {
                        val current = repository.activeJourney()
                        if (current?.transport == Transport.PASSENGER && TrackingService.isRunning &&
                            TrackingService.runningJourneyId == current.id) break
                        delay(100)
                    }
                }
                val id = repository.activeJourney()!!.id
                assertEquals(id, TrackingService.runningJourneyId)
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Telefonda kayıt başladı.").fetchSemanticsNodes().isNotEmpty() }
                assertNull(bridge.pending(key))
                assertEquals(WearResultCode.STARTED, bridge.handle("test-watch", request, false).code)
                val stop = WearCommand(UUID.randomUUID().toString(), WearAction.STOP, System.currentTimeMillis(), journeyId = id)
                assertEquals(WearResultCode.STOPPED, bridge.handle("test-watch", stop, false).code)
                assertFalse(TrackingService.isRunning)
                assertNotNull(repository.getJourney(id)?.endedAt)
                assertEquals(WearResultCode.STARTED, bridge.handle("test-watch", request, false).code)
                assertNull(repository.activeJourney())
                compose.onNodeWithText("Kapat").performClick()
            }
        } finally {
            notificationMode("allow")
            repository.activeJourney()?.let { controller.finish(it.id) }
            repository.restore(DiarySnapshot())
            context.getSharedPreferences("wear_commands_v1", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
