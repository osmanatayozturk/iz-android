package org.iz.navigation.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.iz.navigation.placeinfo.PlaceInfoKey
import org.iz.navigation.placeinfo.PublicPlaceSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Duration

/** Real session/repository/controller/renderer with synthetic boundaries, not native entrypoint,
 * actual survey publication/back-stack, real route/recorder/GPS, or external handler acceptance.
 */
@RunWith(AndroidJUnit4::class)
class PlaceInformationIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var fixture: PlaceInformationTestFixtures? = null

    @Before fun requireAllocation() { PlaceInformationTestFixtures.requireIsolatedAllocation() }
    @After fun disposeOnlyOwnedComposition() {
        fixture?.let { f ->
            compose.runOnIdle { f.mounted.value = false; f.reader.abortOutstanding() }
            compose.waitForIdle()
        }
        // Retain only newly owned bounded public-cache fixtures; never clear any application data.
    }
    private fun newFixture(initialKey: PlaceInfoKey? = PlaceInformationTestFixtures.KEY_A) =
        PlaceInformationTestFixtures(initialKey).also { fixture = it }
    private fun show(f: PlaceInformationTestFixtures) { compose.setContent { f.Content() } }
    private fun request(f: PlaceInformationTestFixtures, index: Int): ControlledPlaceInfoReader.Pending {
        compose.waitUntil(10_000) { f.reader.requests.size > index }
        return f.reader.requests[index]
    }
    private fun awaitSnapshot(f: PlaceInformationTestFixtures, expected: PublicPlaceSnapshot) {
        compose.waitUntil(10_000) {
            f.latestSession.get()?.state?.let { !it.loading && it.snapshot == expected && it.key == expected.key } == true
        }
        compose.runOnIdle {
            val state = checkNotNull(f.latestSession.get()).state
            assertEquals(expected.key, state.key)
            assertEquals(expected.version, state.snapshot?.version)
            assertEquals(expected.tags, state.snapshot?.tags)
            assertEquals(expected.fetchedAtMillis, state.snapshot?.fetchedAtMillis)
        }
    }
    private fun click(tag: String) {
        // This host uses a normal verticalScroll Column, not a lazy list.
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun assertPassiveCallbacks(f: PlaceInformationTestFixtures, externalCount: Int = 0) = compose.runOnIdle {
        assertEquals(0, f.directionCallbacks)
        assertEquals(0, f.surveyCallbacks)
        assertEquals(0, f.correctionCallbacks)
        assertEquals(0, f.noteCallbacks)
        assertEquals(externalCount, f.externalChoices.size)
        assertEquals(externalCount, f.intentContext.intents.size)
    }

    @Test fun selectionAndEndpointChangesRejectCancellationIgnoringLateReads() {
        val f = newFixture()
        show(f)
        val oldA = request(f, 0)
        compose.runOnIdle { f.key.value = PlaceInformationTestFixtures.KEY_B }
        val oldB = request(f, 1)
        compose.runOnIdle { f.key.value = PlaceInformationTestFixtures.KEY_B_OTHER_ENDPOINT }
        val current = request(f, 2)
        val expected = f.snapshot(PlaceInformationTestFixtures.KEY_B_OTHER_ENDPOINT, 9, "Current endpoint B")
        current.found(expected)
        awaitSnapshot(f, expected)
        compose.waitUntil(10_000) { oldA.cancellationObserved.get() && oldB.cancellationObserved.get() }
        oldA.found(f.snapshot(PlaceInformationTestFixtures.KEY_A, 90, "Obsolete A"))
        oldB.found(f.snapshot(PlaceInformationTestFixtures.KEY_B, 91, "Obsolete endpoint B"))
        compose.waitUntil(10_000) { oldA.finished.get() && oldB.finished.get() &&
            oldA.callerCompleted.get() && oldB.callerCompleted.get() }
        compose.waitForIdle()
        awaitSnapshot(f, expected)
        compose.onNodeWithText("Current endpoint B").assertIsDisplayed()
        compose.onNodeWithText("Obsolete A").assertDoesNotExist()
        compose.onNodeWithText("Obsolete endpoint B").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(PlaceInformationTestFixtures.KEY_A, PlaceInformationTestFixtures.KEY_B,
                PlaceInformationTestFixtures.KEY_B_OTHER_ENDPOINT), f.reader.requests.map { it.key })
            assertTrue(f.externalChoices.isEmpty())
            assertTrue(f.intentContext.intents.isEmpty())
        }
        assertPassiveCallbacks(f)
    }

    @Test fun nullOrHiddenKeyRejectsLateCompletionWithoutNewReads() {
        val f = newFixture(null)
        show(f)
        compose.onNodeWithTag(PlaceInformationTestFixtures.HIDDEN_TAG).assertIsDisplayed()
        compose.runOnIdle { assertTrue(f.reader.requests.isEmpty()); f.key.value = PlaceInformationTestFixtures.KEY_A }
        val pending = request(f, 0)
        compose.runOnIdle { f.visible.value = false }
        compose.waitUntil(10_000) { pending.cancellationObserved.get() }
        pending.found(f.snapshot(name = "Late hidden result"))
        compose.waitUntil(10_000) { pending.finished.get() && pending.callerCompleted.get() }
        compose.waitForIdle()
        compose.onNodeWithTag("place-information-card").assertDoesNotExist()
        compose.onNodeWithText("Late hidden result").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(f.latestSession.get()?.state?.snapshot)
            f.key.value = null
            f.visible.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, f.reader.requests.size)
            assertNull(f.latestSession.get()?.state?.key)
            assertNull(f.latestSession.get()?.state?.snapshot)
        }
        assertPassiveCallbacks(f)
    }

    @Test fun changedContributionRevisionRefreshesOnlyItsCurrentKey() {
        val f = newFixture()
        val beforeA = f.snapshot(name = "Before A")
        val beforeB = f.snapshot(PlaceInformationTestFixtures.KEY_B, 20, "Before B")
        runBlocking { assertTrue(f.cache.put(beforeA)); assertTrue(f.cache.put(beforeB)) }
        show(f)
        awaitSnapshot(f, beforeA)
        compose.runOnIdle { assertTrue(f.reader.requests.isEmpty()); f.contributionRevision.value = "synthetic-sent-A-v8" }
        val updatedA = f.snapshot(version = 8, name = "Updated A")
        request(f, 0).also { assertEquals(beforeA.key, it.key); it.found(updatedA) }
        awaitSnapshot(f, updatedA)
        compose.runOnIdle { f.contributionRevision.value = "synthetic-sent-A-v8" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, f.reader.requests.size); f.key.value = beforeB.key }
        awaitSnapshot(f, beforeB)
        compose.runOnIdle { assertEquals(1, f.reader.requests.size); f.contributionRevision.value = "synthetic-sent-B-v21" }
        val updatedB = f.snapshot(beforeB.key, 21, "Updated B")
        request(f, 1).also { assertEquals(beforeB.key, it.key); it.found(updatedB) }
        awaitSnapshot(f, updatedB)
        compose.runOnIdle { f.visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { f.contributionRevision.value = "synthetic-sent-B-v22-while-hidden" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(beforeA.key, beforeB.key), f.reader.requests.map { it.key }) }
        assertPassiveCallbacks(f)
        // Supplied revision-signal handling only; no claim of real SENT upload/back integration.
    }

    @Test fun cachedFailureAndUnavailableRemainDistinctFromMissingInformation() {
        val f = newFixture()
        val cached = f.snapshot(name = "Cached public venue")
        runBlocking { assertTrue(f.cache.put(cached)) }
        show(f)
        awaitSnapshot(f, cached)
        compose.runOnIdle { assertTrue(f.reader.requests.isEmpty()) }
        android.util.Log.i("IzQaPlacePhase", "offline.refresh.before")
        click("place-info-refresh")
        request(f, 0).fail(IOException("Synthetic offline failure"))
        android.util.Log.i("IzQaPlacePhase", "offline.response.after")
        compose.waitUntil(10_000) { f.latestSession.get()?.state?.let { it.failed && !it.loading } == true }
        compose.runOnIdle {
            val state = checkNotNull(f.latestSession.get()).state
            assertEquals(cached, state.snapshot)
            assertEquals("Cached public venue", state.snapshot?.tags?.get("name"))
            assertEquals("24/7", state.snapshot?.tags?.get("opening_hours"))
            assertTrue(state.stale)
            assertNull(state.unavailableStatus)
        }
        android.util.Log.i("IzQaPlacePhase", "offline.status-scroll.before")
        compose.onNodeWithTag("place-info-status").performScrollTo().assertTextContains("Güncel bilgiler alınamadı.", substring = true)
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("Kaydedilmiş OSM saatlerine", substring = true)
        android.util.Log.i("IzQaPlacePhase", "offline.hours-scroll.after")
        for ((index, status) in listOf(404, 410).withIndex()) {
            android.util.Log.i("IzQaPlacePhase", if (status == 404) "unavailable404.refresh.before" else "unavailable410.refresh.before")
            click("place-info-refresh")
            request(f, index + 1).unavailable(status)
            android.util.Log.i("IzQaPlacePhase", if (status == 404) "unavailable404.response.after" else "unavailable410.response.after")
            compose.waitUntil(10_000) { f.latestSession.get()?.state?.let { !it.loading && it.unavailableStatus == status } == true }
            android.util.Log.i("IzQaPlacePhase", if (status == 404) "unavailable404.status-scroll.before" else "unavailable410.status-scroll.before")
            compose.onNodeWithTag("place-info-status").performScrollTo().assertTextContains("Bu OSM nesnesi artık kullanılamıyor.", substring = true)
            compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("Güncel saat durumu kullanılamıyor", substring = true)
            android.util.Log.i("IzQaPlacePhase", if (status == 404) "unavailable404.hours-scroll.after" else "unavailable410.hours-scroll.after")
            compose.onNodeWithTag("place-info-survey").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("place-info-correct").performScrollTo().assertIsNotEnabled()
        }
        android.util.Log.i("IzQaPlacePhase", "recovery.refresh.before")
        click("place-info-refresh")
        val missingHours = PublicPlaceSnapshot(cached.key, 8, mapOf("name" to "Recovered without hours"), f.clock.millis())
        request(f, 3).found(missingHours)
        android.util.Log.i("IzQaPlacePhase", "recovery.response.after")
        awaitSnapshot(f, missingHours)
        compose.runOnIdle {
            val state = checkNotNull(f.latestSession.get()).state
            assertFalse(state.failed); assertFalse(state.stale); assertNull(state.unavailableStatus)
            assertEquals(4, f.reader.requests.size)
        }
        android.util.Log.i("IzQaPlacePhase", "recovery.hours-scroll.before")
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("Saat bilgisi yok", substring = true)
        android.util.Log.i("IzQaPlacePhase", "recovery.hours-scroll.after")
        assertPassiveCallbacks(f)
    }

    @Test fun zoneOverrideRecomputesVisibleHoursWithoutRefetchOrPublicMutation() {
        val f = newFixture()
        val snapshot = f.snapshot(tags = mapOf("opening_hours" to "Th 09:00-18:00", "timezone" to "Europe/Berlin"))
        val expectedTags = mapOf("name" to "Synthetic public A", "opening_hours" to "Th 09:00-18:00", "timezone" to "Europe/Berlin")
        runBlocking { assertTrue(f.cache.put(snapshot)) }
        show(f)
        awaitSnapshot(f, snapshot)
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("şu an açık", substring = true)
        click("place-info-zone-change")
        compose.onNodeWithTag("place-info-zone-input").performTextReplacement("Europe/Istanbul")
        compose.onNodeWithTag("place-info-zone-option-Europe/Istanbul").performClick()
        compose.onNodeWithTag("place-info-zone-save").performClick()
        compose.onNodeWithTag("place-info-zone").performScrollTo().assertTextContains("Europe/Istanbul", substring = true)
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("şu an kapalı", substring = true)
        compose.runOnIdle {
            assertEquals("Europe/Istanbul", f.overrides.read(snapshot.key))
            assertTrue(f.reader.requests.isEmpty())
            assertEquals(snapshot, f.latestSession.get()?.state?.snapshot)
        }
        click("place-info-zone-change")
        compose.onNodeWithTag("place-info-zone-input").performTextReplacement("Mars/Invalid")
        compose.onNodeWithTag("place-info-zone-save").assertIsNotEnabled()
        compose.onNodeWithTag("place-info-zone-cancel").performClick()
        click("place-info-zone-change")
        compose.onNodeWithTag("place-info-zone-reset").performClick()
        compose.onNodeWithTag("place-info-zone").performScrollTo().assertTextContains("Europe/Berlin", substring = true)
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("şu an açık", substring = true)
        compose.runOnIdle { f.visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { f.clock.advance(Duration.ofHours(2)); f.visible.value = true }
        awaitSnapshot(f, snapshot)
        compose.waitUntil(10_000) { f.latestSession.get()?.instant == f.clock.instant() }
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("şu an kapalı", substring = true)
        compose.runOnIdle {
            assertEquals(listOf(snapshot.key to "Europe/Istanbul", snapshot.key to null), f.overrides.writes.toList())
            assertNull(f.overrides.read(snapshot.key)); assertTrue(f.reader.requests.isEmpty())
            assertEquals(expectedTags, snapshot.tags)
            assertEquals(expectedTags, f.latestSession.get()?.state?.snapshot?.tags)
            assertEquals(snapshot.fetchedAtMillis, f.latestSession.get()?.state?.snapshot?.fetchedAtMillis)
        }
        assertPassiveCallbacks(f)
    }

    @Test fun selectedContactAliasUsesRealExternalHelperOnlyAfterTap() {
        val f = newFixture()
        val snapshot = f.snapshot(tags = linkedMapOf("phone" to "+1 202-555-0101",
            "contact:phone" to "+1 202-555-0102", "website" to "https://example.org/first",
            "contact:website" to "https://example.net/second"))
        runBlocking { assertTrue(f.cache.put(snapshot)) }
        show(f)
        awaitSnapshot(f, snapshot)
        compose.runOnIdle { assertTrue(f.intentContext.intents.isEmpty()); assertTrue(f.externalChoices.isEmpty()) }
        click("place-info-phone-1")
        click("place-info-web-1")
        compose.runOnIdle {
            assertEquals(listOf(true, false), f.externalChoices.map { it.second })
            assertEquals(listOf("tel:+12025550102", "https://example.net/second"), f.externalChoices.map { it.first.uri })
            assertEquals(listOf(Intent.ACTION_DIAL, Intent.ACTION_VIEW), f.intentContext.intents.map { it.action })
            assertEquals(listOf("tel:+12025550102", "https://example.net/second"), f.intentContext.intents.map { it.dataString })
            assertEquals(setOf("contact:phone"), f.externalChoices[0].first.sourceKeys)
            assertEquals(setOf("contact:website"), f.externalChoices[1].first.sourceKeys)
            assertTrue(f.intentContext.intents[1].hasCategory(Intent.CATEGORY_BROWSABLE))
            assertFalse(f.intentContext.intents.any { it.action == Intent.ACTION_CALL })
            assertTrue(f.externalErrors.isEmpty()); assertTrue(f.reader.requests.isEmpty())
        }
        assertPassiveCallbacks(f, externalCount = 2)
        click("place-info-zone-change")
        compose.onNodeWithTag("place-info-zone-cancel").performClick()
        compose.runOnIdle { assertEquals(2, f.intentContext.intents.size); assertEquals(2, f.externalChoices.size) }
    }
}
