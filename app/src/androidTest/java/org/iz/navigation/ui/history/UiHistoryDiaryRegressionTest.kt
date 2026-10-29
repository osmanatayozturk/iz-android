package org.iz.navigation.ui.history

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.ui.DiaryApp
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.Ux0111ComposeHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiHistoryDiaryRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Ux0111ComposeHost(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { DiaryRepository(context) }
    private var owned: Journey? = null
    @After fun closeOwned() {
        try { host.close() } finally { owned?.let { row -> runBlocking {
            repository.getJourney(row.id)?.let { current ->
                check(current.startedAt == row.startedAt && current.transport == row.transport)
                repository.deleteJourney(row.id)
            }
        } } }
    }
    @Test fun journeyMenuSettingsBackReturnsSameJourneyWithoutEndingIt() {
        check(context.packageName == "org.iz.navigation.batterycheck" && !TrackingService.isRunning)
        val nav = (context.applicationContext as IzApplication).navigation.state.value
        check(nav.sessionId == null && !nav.guidance && !nav.recording)
        val existing = runBlocking { repository.journeys.first() }
        check(existing.none { it.endedAt == null || it.status == org.iz.navigation.data.JourneyStatus.TEMPORARY })
        val journey = runBlocking { repository.createJourney(Transport.WALK, false, System.currentTimeMillis()) }
        check(journey.id !in existing.map { it.id })
        owned = journey
        val incoming = mutableStateOf<Intent?>(Intent().putExtra("trackingPrompt", "journey").putExtra("journeyId", journey.id))
        host.setContent { IzTheme { DiaryApp(incoming.value, { incoming.value = null }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bitir").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
        compose.onAllNodesWithText("Bitir").assertCountEquals(0)
        compose.onNodeWithTag("return-home-map").performClick()
        compose.onNodeWithText("Bitir").assertExists()
        assertNull(runBlocking { repository.getJourney(journey.id) }?.endedAt)
        assertFalse(TrackingService.isRunning)
    }
    @Test fun warmExternalEntryKeepsOrDiscardsOnlyUnsavedJourneyEditorWithoutSaving() {
        check(context.packageName == "org.iz.navigation.batterycheck" && !TrackingService.isRunning)
        val nav = (context.applicationContext as IzApplication).navigation.state.value
        check(nav.sessionId == null && !nav.guidance && !nav.recording)
        val existing = runBlocking { repository.journeys.first() }
        check(existing.none { it.endedAt == null || it.status == org.iz.navigation.data.JourneyStatus.TEMPORARY })
        val journey = runBlocking { repository.createJourney(Transport.WALK, false, System.currentTimeMillis()) }
        check(journey.id !in existing.map { it.id }); owned = journey
        val incoming = mutableStateOf<Intent?>(Intent().putExtra("trackingPrompt", "journey").putExtra("journeyId", journey.id))
        host.setContent { IzTheme { DiaryApp(incoming.value, { incoming.value = null }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bitir").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("Yolculuğu düzenle").performClick()
        compose.onNode(hasSetTextAction() and hasText("Ad")).performTextReplacement("Unsaved QA title")
        compose.runOnIdle { incoming.value = Intent().putExtra("openGpx", true) }
        compose.onNodeWithTag("ui-exit-keep").performClick()
        compose.onNodeWithText("Unsaved QA title").assertExists()
        assertEquals(journey.title, runBlocking { repository.getJourney(journey.id) }?.title)
        compose.runOnIdle { incoming.value = Intent().putExtra("openGpx", true) }
        compose.onNodeWithTag("ui-exit-discard").performClick()
        compose.onNodeWithTag("gpx-screen").assertIsDisplayed()
        compose.onNodeWithText("Yolculuğu düzenle").assertDoesNotExist()
        val persisted = runBlocking { repository.getJourney(journey.id) }
        assertEquals(journey.title, persisted?.title); assertNull(persisted?.endedAt)
        assertFalse(TrackingService.isRunning)
    }
}
