package org.iz.navigation.ui

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.ui.history.LocalUiEntryKey
import org.iz.navigation.ui.history.LocalUiExitGuard
import org.iz.navigation.ui.history.UiEditOwner
import org.iz.navigation.ui.history.UiEditState
import org.iz.navigation.ui.history.UiExitConfirmation
import org.iz.navigation.ui.history.UiExitGuard
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/**
 * One actual DiaryApp origin regression plus actual exit-owner/dialog component boundaries.
 * Existing isolated QA rehost only; no shared host changes, real session or network writes.
 * Component acknowledgment is supplied explicitly: it is NOT proof of the OSM autosave adapter.
 * No MainActivity delivery/process death, full history matrix, audibility or native-map proof.
 * Run only with a fresh exclusive source/product/test/target allocation at normal font.
 */
@RunWith(AndroidJUnit4::class)
class Ux0112PhoneFlowAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Ux0111ComposeHost(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { DiaryRepository(context) }
    private var ownedJourney: Journey? = null
    private var baseline = emptyList<Journey>()

    @Before fun requireAllocatedIdleQa() {
        check(context.packageName == "org.iz.navigation.batterycheck")
        assertEquals("Only allocated normal font; never override it", 1f, context.resources.configuration.fontScale, .01f)
        check(!TrackerSettings(context).enabled) { "QA automatic recording must already be disabled; never change it here" }
        assertNoActualSession()
    }

    @After fun closeOnlyOwnedFixture() {
        try { host.close() }
        finally {
            ownedJourney?.let { captured -> runBlocking {
                repository.getJourney(captured.id)?.let { current ->
                    check(current.startedAt == captured.startedAt && current.endedAt == captured.endedAt &&
                        current.transport == captured.transport && current.title == captured.title) {
                        "Fixture identity changed; never delete a replacement row"
                    }
                    repository.deleteJourney(captured.id)
                }
                assertEquals("Only the owned synthetic row may be removed", baseline, repository.journeys.first())
            } }
        }
    }

    @Test fun actualJourneyMenuSettingsSystemBackReturnsToSameDetail() {
        val journey = runBlocking {
            baseline = repository.journeys.first()
            check(baseline.none { it.endedAt == null || it.status == JourneyStatus.TEMPORARY }) {
                "Require an allocated QA diary without active/temporary rows; never clear existing records"
            }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val value = Journey(id = id, title = "Synthetic UX0112 origin ${id.take(8)}",
                transport = Transport.WALK, status = JourneyStatus.CONFIRMED,
                startedAt = now - 60_000L, endedAt = now)
            check(repository.getJourney(value.id) == null && baseline.none { it.id == value.id })
            ownedJourney = value
            repository.saveJourney(value)
            assertEquals(value, repository.getJourney(value.id))
            value
        }
        val incoming = mutableStateOf<Intent?>(Intent().putExtra("trackingPrompt", "journey").putExtra("journeyId", journey.id))
        var consumed = 0
        host.setContent { IzTheme { DiaryApp(incoming.value, { incoming.value = null; consumed++ }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Yolculuğu düzenle").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(journey.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Yolculuğu düzenle").assertIsDisplayed()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
        compose.onNodeWithText("Hareketi fark et").assertIsDisplayed()
        compose.onNodeWithContentDescription("Yolculuğu düzenle").assertDoesNotExist()
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Yolculuğu düzenle").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(journey.title).assertIsDisplayed()
        compose.onNodeWithText("Hareketi fark et").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Back must not redeliver the consumed entry", 1, consumed) }
        assertEquals(journey, runBlocking { repository.getJourney(journey.id) })
        assertEquals(baseline, runBlocking { repository.journeys.first() }.filterNot { it.id == journey.id })
        assertNoActualSession()
    }

    @Test fun exitDialogKeepPreservesInputAndDiscardLeavesExactlyOnce() {
        val guard = UiExitGuard()
        val input = mutableStateOf("Synthetic unsaved delta")
        val visible = mutableStateOf(true)
        var discarded = 0
        var exited = 0
        val edit = UiEditState("editor-a", "account-a", 2L, 1L, dirty = true, busy = false)
        host.setContent { IzTheme {
            CompositionLocalProvider(LocalUiExitGuard provides guard, LocalUiEntryKey provides edit.entryKey) {
                if (visible.value) {
                    UiEditOwner(edit) { discarded++; input.value = "Acknowledged baseline" }
                    Text(input.value)
                }
                UiExitConfirmation(guard)
            }
        } }
        compose.runOnIdle { guard.requestExit { exited++; visible.value = false } }
        compose.onNodeWithText("Kaydedilmemiş değişiklikler var").assertIsDisplayed()
        compose.onNodeWithTag("ui-exit-keep").assertTextContains("Düzenlemeye dön").performClick()
        compose.onNodeWithText("Synthetic unsaved delta").assertIsDisplayed()
        compose.onNodeWithTag("ui-exit-discard").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, discarded); assertEquals(0, exited) }
        compose.runOnIdle { guard.requestExit { exited++; visible.value = false } }
        compose.onNodeWithTag("ui-exit-discard").assertTextContains("Değişikliklerden vazgeç").performClick()
        compose.onNodeWithText("Synthetic unsaved delta").assertDoesNotExist()
        compose.runOnIdle {
            guard.discardAndExit()
            assertEquals(1, discarded)
            assertEquals(1, exited)
            assertEquals("Acknowledged baseline", input.value)
            assertNull(guard.pending.value)
            assertNull(guard.editState.value)
        }
        assertNoActualSession()
    }

    @Test fun acknowledgmentDuringDialogPreservesCommittedBaselineOnExit() {
        val guard = UiExitGuard()
        val edit = mutableStateOf(UiEditState("editor-ack", "account-a", 7L, 6L, dirty = true, busy = false))
        val visible = mutableStateOf(true)
        var acknowledgedText = "Previous baseline"
        var discarded = 0
        var exited = 0
        host.setContent { IzTheme {
            CompositionLocalProvider(LocalUiExitGuard provides guard, LocalUiEntryKey provides edit.value.entryKey) {
                if (visible.value) UiEditOwner(edit.value) { discarded++; acknowledgedText = "Incorrect rollback" }
                UiExitConfirmation(guard)
            }
        } }
        compose.runOnIdle { guard.requestExit { exited++; visible.value = false } }
        compose.onNodeWithTag("ui-exit-discard").assertIsDisplayed()
        // Model an actual feature-supplied acknowledgment, not a delay or forced save.
        compose.runOnIdle {
            acknowledgedText = "Newly acknowledged baseline"
            edit.value = edit.value.copy(acknowledgedRevision = 7L, dirty = false)
        }
        compose.onNodeWithTag("ui-exit-discard").assertDoesNotExist()
        compose.onNodeWithTag("ui-exit-keep").assertDoesNotExist()
        compose.onNodeWithText("Kaydedilmemiş değişiklikler var").assertDoesNotExist()
        compose.runOnIdle {
            // The retired confirmation/action must neither discard nor exit after ACK.
            guard.discardAndExit()
            assertEquals(0, discarded)
            assertEquals(0, exited)
            assertTrue("Acknowledgment must leave the current form open", visible.value)
            assertEquals("Newly acknowledged baseline", acknowledgedText)
            assertNull(guard.pending.value)
            assertEquals(edit.value, guard.editState.value)
        }
        // A separate, fresh Back request sees clean state and exits once without discard.
        compose.runOnIdle { guard.requestExit { exited++; visible.value = false } }
        compose.onNodeWithTag("ui-exit-discard").assertDoesNotExist()
        compose.runOnIdle {
            guard.discardAndExit()
            assertEquals(0, discarded)
            assertEquals(1, exited)
            assertFalse(visible.value)
            assertEquals("Newly acknowledged baseline", acknowledgedText)
            assertNull(guard.pending.value)
            assertNull(guard.editState.value)
        }
        assertNoActualSession()
    }

    @Test fun replacementOwnerAccountRevisionInvalidatesOldDialogAndAction() {
        val guard = UiExitGuard()
        val edit = mutableStateOf(UiEditState("original-editor", "account-a", 1L, 0L, dirty = true, busy = false))
        val discarded = mutableListOf<String>()
        var originalExits = 0
        var replacementExits = 0
        host.setContent { IzTheme {
            CompositionLocalProvider(LocalUiExitGuard provides guard, LocalUiEntryKey provides edit.value.entryKey) {
                UiEditOwner(edit.value) { discarded += edit.value.entryKey }
                UiExitConfirmation(guard)
            }
        } }
        compose.runOnIdle { guard.requestExit { originalExits++ } }
        compose.onNodeWithTag("ui-exit-discard").assertIsDisplayed()
        compose.runOnIdle { edit.value = UiEditState("replacement-editor", "account-b", 2L, 1L, dirty = true, busy = false) }
        compose.onNodeWithTag("ui-exit-discard").assertDoesNotExist()
        compose.runOnIdle {
            guard.discardAndExit()
            assertTrue(discarded.isEmpty())
            assertEquals(0, originalExits)
            assertEquals("replacement-editor", guard.editState.value?.entryKey)
            assertEquals("account-b", guard.editState.value?.accountKey)
            assertEquals(2L, guard.editState.value?.editRevision)
            guard.requestExit { replacementExits++ }
        }
        compose.onNodeWithTag("ui-exit-discard").performClick()
        compose.runOnIdle {
            assertEquals(listOf("replacement-editor"), discarded)
            assertEquals(0, originalExits)
            assertEquals(1, replacementExits)
        }
        assertNoActualSession()
    }

    @Test fun busyOwnerBlocksDiscardWhileKeepEditingStaysAvailable() {
        val guard = UiExitGuard()
        val edit = mutableStateOf(UiEditState("busy-editor", "account-a", 3L, 2L, dirty = true, busy = false))
        var discarded = 0
        var exited = 0
        host.setContent { IzTheme {
            CompositionLocalProvider(LocalUiExitGuard provides guard, LocalUiEntryKey provides edit.value.entryKey) {
                UiEditOwner(edit.value) { discarded++ }
                UiExitConfirmation(guard)
            }
        } }
        compose.runOnIdle { guard.requestExit { exited++ } }
        compose.runOnIdle { edit.value = edit.value.copy(busy = true) }
        compose.onNodeWithTag("ui-exit-discard").assertIsNotEnabled()
        compose.onNodeWithTag("ui-exit-keep").assertIsEnabled().performClick()
        compose.runOnIdle {
            guard.discardAndExit()
            guard.requestExit { exited++ }
            assertEquals(0, discarded)
            assertEquals(0, exited)
            assertNull(guard.pending.value)
            assertEquals(edit.value, guard.editState.value)
        }
        assertNoActualSession()
    }

    private fun assertNoActualSession() {
        assertFalse(TrackingService.isRunning)
        val current = (context.applicationContext as IzApplication).navigation.state.value
        assertNull(current.sessionId)
        assertFalse(current.guidance || current.recording || current.locationActive)
    }
}
