package org.iz.navigation.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Only synthetic callbacks: these tests never construct the production OSM search client. */
@RunWith(AndroidJUnit4::class)
class OsmSearchDialogTest {
    @get:Rule val compose = createComposeRule()
    private val a = SelectedOsmPlace("Örnek bina", 40.0, 30.0, OsmRef(OsmType.WAY, 101))
    private val b = SelectedOsmPlace("Örnek bina", 40.001, 30.001, OsmRef(OsmType.WAY, 102))
    private val pending = PendingSearch()

    @After fun releasePendingCallbacks() { compose.runOnIdle { pending.calls.forEach { it.success(emptyList()) } } }

    private fun type(text: String) {
        compose.onNodeWithTag("osm-search-query").performTextReplacement(text)
        closeSoftKeyboard()
    }
    private fun submit() { compose.onNodeWithTag("osm-search-submit").performClick() }
    private fun showPending() {
        compose.setContent { IzTheme { OsmSearchDialog({}, {}, initialQuery = "ilk", searchPlaces = pending::search) } }
    }

    @Test fun typingNeverSearchesAndDuplicateNamesKeepTheirExactSelectedObject() {
        val queries = CopyOnWriteArrayList<String>()
        var selected: SelectedOsmPlace? = null
        compose.setContent { IzTheme {
            OsmSearchDialog({}, { selected = it }, searchPlaces = { query -> queries += query; listOf(a, b) })
        } }
        type("ilk"); type("  bina  ")
        compose.runOnIdle { assertTrue(queries.isEmpty()) }
        submit()
        compose.onAllNodesWithText("Örnek bina").assertCountEquals(2)
        compose.onNodeWithText("40.001, 30.001").performClick()
        compose.runOnIdle { assertEquals(listOf("bina"), queries.toList()); assertSame(b, selected) }
    }

    @Test fun editingAfterSuccessImmediatelyRemovesOldChoices() {
        compose.setContent { IzTheme { OsmSearchDialog({}, {}, initialQuery = "ilk", searchPlaces = { listOf(a) }) } }
        submit()
        compose.onNodeWithText("Örnek bina").assertIsDisplayed()
        type("yeni")
        compose.onNodeWithText("Örnek bina").assertDoesNotExist()
        compose.onNodeWithTag("osm-search-empty").assertDoesNotExist()
        compose.onNodeWithTag("osm-search-submit").assertIsEnabled()
    }

    @Test fun editingClearsBothEmptyResultAndFailureMessages() {
        compose.setContent { IzTheme {
            OsmSearchDialog({}, {}, initialQuery = "ilk", searchPlaces = {
                if (it == "ilk") emptyList() else throw IllegalStateException("Sentetik arama hatası")
            })
        } }
        submit(); compose.onNodeWithTag("osm-search-empty").assertIsDisplayed()
        type("hata"); compose.onNodeWithTag("osm-search-empty").assertDoesNotExist()
        submit(); compose.onNodeWithTag("osm-search-error").assertIsDisplayed()
        type("yeni"); compose.onNodeWithTag("osm-search-error").assertDoesNotExist()
    }

    @Test fun oldResultAndFinallyCannotReplaceSameTextResubmission() {
        showPending(); submit()
        compose.waitUntil(5_000) { pending.calls.size == 1 }
        type("başka"); type("ilk")
        compose.onNodeWithTag("osm-search-submit").assertIsEnabled()
        compose.runOnIdle { assertTrue(pending.calls[0].job.isCancelled) }
        submit(); compose.waitUntil(5_000) { pending.calls.size == 2 }
        compose.runOnIdle { pending.calls[0].success(listOf(a)) }
        compose.onNodeWithText("Örnek bina").assertDoesNotExist()
        compose.onNodeWithTag("osm-search-progress").assertExists()
        compose.onNodeWithTag("osm-search-submit").assertIsNotEnabled()
        compose.runOnIdle { pending.calls[1].success(listOf(b)) }
        compose.onNodeWithText("40.001, 30.001").assertIsDisplayed()
        compose.onNodeWithTag("osm-search-progress").assertDoesNotExist()
    }

    @Test fun oldFailureCannotReplaceNewSuccessfulResults() {
        showPending(); submit(); compose.waitUntil(5_000) { pending.calls.size == 1 }
        type("yeni"); submit(); compose.waitUntil(5_000) { pending.calls.size == 2 }
        compose.runOnIdle { pending.calls[1].success(listOf(b)) }
        compose.onNodeWithText("40.001, 30.001").assertIsDisplayed()
        compose.runOnIdle { pending.calls[0].failure() }
        compose.onNodeWithTag("osm-search-error").assertDoesNotExist()
        compose.onNodeWithText("40.001, 30.001").assertIsDisplayed()
        compose.onNodeWithTag("osm-search-submit").assertIsEnabled()
    }

    @Test fun dismissingDialogCancelsSearchAndLateReplyDoesNotReopenIt() {
        var visible by mutableStateOf(true)
        compose.setContent { IzTheme {
            if (visible) OsmSearchDialog({ visible = false }, {}, initialQuery = "ilk", searchPlaces = pending::search)
        } }
        submit(); compose.waitUntil(5_000) { pending.calls.size == 1 }
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { assertTrue(pending.calls[0].job.isCancelled); pending.calls[0].success(listOf(a)) }
        compose.onNodeWithTag("osm-search-query").assertDoesNotExist()
        compose.onNodeWithText("Örnek bina").assertDoesNotExist()
    }

    @Test fun longResultsStayScrollableWithinLimitAndKeepAttributionVisible() {
        val places = List(40) { index -> SelectedOsmPlace("Bina $index", 40.0 + index * .001, 30.0, OsmRef(OsmType.WAY, 200L + index)) }
        var selected: SelectedOsmPlace? = null
        compose.setContent { IzTheme {
            OsmSearchDialog({}, { selected = it }, initialQuery = "bina", searchPlaces = { places })
        } }
        submit()
        val results = compose.onNodeWithTag("osm-search-results")
        val height = results.fetchSemanticsNode().boundsInRoot.height
        assertTrue(height > 0f && height <= with(compose.density) { 320.dp.toPx() } + 1f)
        compose.onNodeWithTag("osm-search-attribution").assertIsDisplayed()
        results.performScrollToNode(hasText("Bina 39"))
        compose.onNodeWithText("Bina 39").performClick()
        compose.runOnIdle { assertSame(places[39], selected) }
        compose.onNodeWithTag("osm-search-attribution").assertIsDisplayed()
    }

    private class PendingSearch {
        class Call(val job: Job, private val continuation: Continuation<List<SelectedOsmPlace>>) {
            private val resolved = AtomicBoolean()
            fun success(results: List<SelectedOsmPlace>) { if (resolved.compareAndSet(false, true)) continuation.resume(results) }
            fun failure() { if (resolved.compareAndSet(false, true)) continuation.resumeWithException(IllegalStateException("Eski sentetik hata")) }
        }
        val calls = CopyOnWriteArrayList<Call>()
        suspend fun search(@Suppress("UNUSED_PARAMETER") query: String): List<SelectedOsmPlace> {
            val job = currentCoroutineContext().job
            return suspendCoroutine { calls += Call(job, it) }
        }
    }
}
