package org.iz.navigation.moto

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.lean.LeanSettingsStore
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotorcyclePhoneEntryAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun activePhoneMapShowsCaptureReasonAndPersistsEnableAcrossFreeAndGuidedRecording() {
        val context = requireMotorcycleQaTarget()
        val app = context.applicationContext as IzApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = DiaryRepository(app)
        val navigator = app.navigation
        val lean = LeanSettingsStore.get(context)
        val previous = lean.state.value
        val bikes = listOf("QA Kuzey", "QA Güney").map { name ->
            GarageVehicle(name = name, transport = Transport.MOTORCYCLE, capacityLitres = 12.0)
        }
        val ownedTrips = mutableSetOf<String>()
        assertNull(navigator.state.value.sessionId)
        assertTrue(runBlocking { app.garage.vehicles.first() }.isEmpty())
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        try {
            lean.enabled(false)
            lean.clearCalibration()
            runBlocking { bikes.forEach { app.garage.saveVehicle(it) } }
            val intent = Intent(context, MainActivity::class.java).putExtra("openNavigation", true)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-record").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("home-record").performClick()
                compose.onNode(hasText("Motosiklet") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
                compose.onNode(hasText("Kaydı başlat") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(6_000) { compose.onAllNodesWithTag("garage-vehicle-choice").fetchSemanticsNodes().isNotEmpty() }
                assertNull(navigator.state.value.sessionId)
                compose.onNodeWithTag("garage-choose-${bikes.last().id}").performClick()
                compose.waitUntil(10_000) { navigator.state.value.recording && TrackingService.runningJourneyId != null }
                val free = navigator.state.value
                val freeJourney = requireNotNull(free.journey).also { ownedTrips += it.id }
                assertFalse(free.guidance)
                assertEquals(Transport.MOTORCYCLE, freeJourney.transport)
                assertEquals(bikes.last().id, runBlocking {
                    withTimeout(5_000) { app.garage.assignedVehicleId(freeJourney.id).first { it != null } }
                })
                compose.onNodeWithTag("lean-recording-status").assertTextContains("Yatış kaydı kapalı")
                compose.onNodeWithTag("lean-recording-enable").performClick()
                compose.waitUntil(5_000) { LeanSettingsStore(context).state.value.enabled }
                compose.onNodeWithTag("lean-recording-status").assertTextContains("montaj kalibrasyonu gerekli", substring = true)
                compose.onNodeWithText("Yatış sensörleri kaydediliyor").assertDoesNotExist()
                assertEquals(freeJourney.id, TrackingService.runningJourneyId)

                val qaFix = TrackPoint(journeyId = freeJourney.id, latitude = .27, longitude = -140.0,
                    recordedAt = System.currentTimeMillis(), accuracy = 3f, speed = 0f)
                runBlocking {
                    repository.addPoint(qaFix)
                    assertTrue(repository.journeyPoints(freeJourney.id).any { it.id == qaFix.id })
                    withContext(Dispatchers.Main) { navigator.finishSession(requireNotNull(free.sessionId)) }
                }
                compose.waitUntil(6_000) { !TrackingService.isRunning && navigator.state.value.sessionId == null }

                runBlocking { withContext(Dispatchers.Main) { navigator.startGuidance(route(), recordJourney = true) } }
                compose.waitUntil(10_000) { navigator.state.value.guidance && navigator.state.value.recording }
                val guided = navigator.state.value
                val guidedJourney = requireNotNull(guided.journey).also { ownedTrips += it.id }
                assertNotEquals(freeJourney.id, guidedJourney.id)
                assertEquals(Transport.MOTORCYCLE, guidedJourney.transport)
                compose.onNodeWithTag("lean-recording-status").assertTextContains("montaj kalibrasyonu gerekli", substring = true)
                compose.onNodeWithTag("lean-recording-enable").assertDoesNotExist()
                compose.onNodeWithText("Yatış sensörleri kaydediliyor").assertDoesNotExist()
            }
        } finally {
            runBlocking {
                val active = navigator.state.value
                active.journey?.id?.let(ownedTrips::add)
                withContext(Dispatchers.Main) { active.sessionId?.let { navigator.finishSession(it) } }
                ownedTrips.forEach { if (repository.getJourney(it) != null) repository.deleteJourney(it) }
                bikes.forEach { app.garage.deleteVehicle(it.id) }
            }
            lean.mount(previous.mount)
            lean.clearCalibration()
            previous.calibration?.let(lean::reference)
            lean.enabled(previous.enabled)
        }
    }

    private fun route(): PlannedRoute {
        val start = WeatherCoordinate(.27, -140.0)
        val end = WeatherCoordinate(.27, -139.996)
        return PlannedRoute("qa-moto-${UUID.randomUUID()}", listOf(RouteStop("QA başlangıç", start), RouteStop("QA varış", end)),
            listOf(RouteVertex(start, 0.0), RouteVertex(end, 90.0)), 445.0, 90.0, System.currentTimeMillis(),
            transport = Transport.MOTORCYCLE, maneuvers = listOf(RouteManeuver(10, "QA", "QA",
                emptyList(), 0, 1, 0.0, 90.0)))
    }
}
