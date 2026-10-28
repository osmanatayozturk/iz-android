package org.iz.navigation.ui

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.tracking.TrackingService
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual DiaryApp wiring in the isolated QA package; no real tracker or invented session. */
@RunWith(AndroidJUnit4::class)
class DiaryEndingRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Ux0111ComposeHost(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { DiaryRepository(context) }
    private val owned = mutableListOf<Journey>()
    private var baseline = emptyList<Journey>()
    private lateinit var journey: Journey

    @Before fun prepareOwnedJourney() = runBlocking {
        check(context.packageName == "org.iz.navigation.batterycheck")
        check(!TrackingService.isRunning)
        val nav = (context.applicationContext as IzApplication).navigation.state.value
        check(nav.sessionId == null && !nav.guidance && !nav.recording && !nav.locationActive)
        baseline = repository.journeys.first()
        check(baseline.none { it.endedAt == null || it.status == JourneyStatus.TEMPORARY }) {
            "Require an allocated QA diary with no active/temporary rows; never clear unrelated data"
        }
        journey = createOwned()
    }

    private suspend fun createOwned(): Journey {
        val started = System.currentTimeMillis()
        val value = repository.createJourney(Transport.WALK, temporary = false, now = started)
        check(value.startedAt == started && value.status == JourneyStatus.CONFIRMED && value.endedAt == null &&
            value.id !in baseline.map { it.id } && value.id !in owned.map { it.id }) { "Never claim an existing journey as fixture-owned" }
        owned += value
        return value
    }

    @After fun releaseOwnedFixture() {
        try { host.close() }
        finally { runBlocking { owned.asReversed().forEach { captured ->
            repository.getJourney(captured.id)?.let { current ->
                check(current.startedAt == captured.startedAt && current.transport == captured.transport)
                repository.deleteJourney(captured.id)
            }
        } } }
    }

    private fun openActualDiaryAndPressBitir() {
        val incoming = mutableStateOf<Intent?>(Intent().putExtra("trackingPrompt", "journey").putExtra("journeyId", journey.id))
        host.setContent { IzTheme { DiaryApp(incoming.value, { incoming.value = null }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bitir").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Bitir").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-session-end").assertIsDisplayed()
        compose.onNodeWithText("Yolculuk kaydı bitsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Yolculuk kaydı bitecek.", substring = true).assertIsDisplayed()
        assertNull("Opening Bitir must not mutate the actual diary", runBlocking { repository.getJourney(journey.id) }?.endedAt)
        assertFalse(TrackingService.isRunning)
    }

    @Test fun diaryBitirRequiresExplicitConsequencesAndCancelPreservesJourney() {
        openActualDiaryAndPressBitir()
        compose.onNodeWithTag("cancel-session-end").performClick()
        compose.onNodeWithTag("confirm-session-end").assertDoesNotExist()
        assertNull(runBlocking { repository.getJourney(journey.id) }?.endedAt)
        assertEquals(baseline, runBlocking { repository.journeys.first() }.filterNot { it.id == journey.id })
    }

    @Test fun diaryConfirmEndsOnlyCapturedJourneyAndPreservesOtherDiaryRows() {
        openActualDiaryAndPressBitir()
        compose.onNodeWithTag("confirm-session-end").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.getJourney(journey.id) }?.endedAt != null }
        assertEquals(baseline, runBlocking { repository.journeys.first() }.filterNot { it.id == journey.id })
        assertFalse(TrackingService.isRunning)
    }

    @Test fun staleDiaryConfirmationCannotEndReplacementActiveJourney() {
        openActualDiaryAndPressBitir()
        val replacement = runBlocking {
            repository.finishJourney(journey.id)
            createOwned()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("confirm-session-end").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("confirm-session-end").assertDoesNotExist()
        assertNull("The replacement journey must remain active", runBlocking { repository.getJourney(replacement.id) }?.endedAt)
        assertFalse(TrackingService.isRunning)
    }
}