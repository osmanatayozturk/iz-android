package org.iz.navigation.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog as isDialogWindow
import androidx.test.espresso.matcher.ViewMatchers.isRoot as isRootView
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
    private val a = SelectedOsmPlace("Örnek bina", 40.0, 30.0, OsmRef(OsmType.WAY, 101), displayName = "Örnek bina, Sentetik Sokak 1, Deneme Mahallesi, Test İlçesi")
    private val b = SelectedOsmPlace("Örnek bina", 40.001, 30.001, OsmRef(OsmType.WAY, 102), displayName = "Örnek bina, Sentetik Sokak 2, Deneme Mahallesi, Test İlçesi")
    private val pending = PendingSearch()

    @After fun releasePendingCallbacks() { compose.runOnIdle { pending.calls.forEach { it.success(emptyList()) } } }

    private fun type(text: String) {
        compose.onNodeWithTag("osm-search-query").performScrollTo().performTextReplacement(text)
        closeSoftKeyboard()
    }
    private fun submit() { compose.onNodeWithTag("osm-search-submit").performScrollTo().performClick() }
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
        compose.onNodeWithText(requireNotNull(b.displayName)).performClick()
        compose.runOnIdle { assertEquals(listOf("bina"), queries.toList()); assertSame(b, selected) }
    }

    @Test fun absentAddressShowsExplanationAndPreservesSelectedPlace() {
        verifyMissingAddress(null)
    }

    @Test fun blankAddressShowsExplanationAndPreservesSelectedPlace() {
        verifyMissingAddress("   ")
    }

    @Test fun addressEqualToNameShowsExplanationAndPreservesSelectedPlace() {
        verifyMissingAddress("Örnek bina")
    }

    @Test fun whitespacePaddedNameShowsExplanationAndPreservesSelectedPlace() {
        verifyMissingAddress("  Örnek bina  ")
    }

    @Test fun meaningfulPartialAddressPreservesItsOriginalWhitespace() {
        val place = a.copy(displayName = "  Sentetik Sokak 12  ")
        var selected: SelectedOsmPlace? = null
        compose.setContent { IzTheme {
            OsmSearchDialog({}, { selected = it }, initialQuery = "bina", searchPlaces = { listOf(place) })
        } }
        submit()
        val address = compose.onNodeWithText("  Sentetik Sokak 12  ", useUnmergedTree = true)
        assertFullText(address, "  Sentetik Sokak 12  ")
        compose.onNodeWithText("Adres bilgisi yok").assertDoesNotExist()
        compose.onNodeWithText("  Sentetik Sokak 12  ").performClick()
        compose.runOnIdle { assertSame(place, selected) }
    }

    private fun verifyMissingAddress(displayName: String?) {
        val place = a.copy(displayName = displayName)
        var selected: SelectedOsmPlace? = null
        compose.setContent { IzTheme {
            OsmSearchDialog({}, { selected = it }, initialQuery = "bina", searchPlaces = { listOf(place) })
        } }
        submit()
        compose.onNodeWithText("Örnek bina").assertIsDisplayed()
        val explanation = compose.onNodeWithText("Adres bilgisi yok")
        revealInDialog(explanation)
        explanation.assertIsDisplayed().performClick()
        compose.onNodeWithText("40.0, 30.0").assertDoesNotExist()
        compose.runOnIdle { assertSame(place, selected) }
    }

    @Test fun fullNameAndExistingAddressWrapWithoutTruncationAtLargeFont() {
        val place = SelectedOsmPlace(
            "Sentetik araştırma binası", 40.0, 30.0, OsmRef(OsmType.WAY, 301),
            displayName = "Sentetik araştırma binası, Uzun Deneme Sokağı 12, Örnek Mahallesi, Test İlçesi",
        )
        var selected: SelectedOsmPlace? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                IzTheme { OsmSearchDialog({}, { selected = it }, initialQuery = "bina", searchPlaces = { listOf(place) }) }
            }
        }
        compose.onNodeWithText("Yer ara").assertIsDisplayed()
        submit()
        val name = compose.onNodeWithText(place.name, useUnmergedTree = true)
        val address = compose.onNodeWithText(requireNotNull(place.displayName), useUnmergedTree = true)
        assertFullText(name, place.name, requireWrapping = true)
        assertFullText(address, requireNotNull(place.displayName), requireWrapping = true)
        val nameBottom = name.fetchSemanticsNode().positionInRoot.y + name.fetchSemanticsNode().size.height
        assertTrue("The address must follow the name", address.fetchSemanticsNode().positionInRoot.y >= nameBottom)
        compose.onNodeWithText("40.0, 30.0").assertDoesNotExist()
        compose.onNodeWithText(requireNotNull(place.displayName)).performClick()
        compose.runOnIdle { assertSame(place, selected) }
        assertFullAttribution()
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
        compose.onNodeWithText(requireNotNull(b.displayName)).assertIsDisplayed()
        compose.onNodeWithTag("osm-search-progress").assertDoesNotExist()
    }

    @Test fun oldFailureCannotReplaceNewSuccessfulResults() {
        showPending(); submit(); compose.waitUntil(5_000) { pending.calls.size == 1 }
        type("yeni"); submit(); compose.waitUntil(5_000) { pending.calls.size == 2 }
        compose.runOnIdle { pending.calls[1].success(listOf(b)) }
        compose.onNodeWithText(requireNotNull(b.displayName)).assertIsDisplayed()
        compose.runOnIdle { pending.calls[0].failure() }
        compose.onNodeWithTag("osm-search-error").assertDoesNotExist()
        compose.onNodeWithText(requireNotNull(b.displayName)).assertIsDisplayed()
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
        verifyLongResults(keepKeyboardOpen = false)
    }

    @Test fun longResultsAndFullAttributionRemainReachableWithKeyboard() {
        verifyLongResults(keepKeyboardOpen = true)
    }

    private fun verifyLongResults(keepKeyboardOpen: Boolean) {
        val places = List(40) { index -> SelectedOsmPlace("Bina $index", 40.0 + index * .001, 30.0, OsmRef(OsmType.WAY, 200L + index), displayName = "Bina $index, Sentetik Sokak $index, Test İlçesi") }
        var selected: SelectedOsmPlace? = null
        var visible by mutableStateOf(true)
        compose.setContent { IzTheme {
            if (visible) OsmSearchDialog({ visible = false }, { selected = it }, initialQuery = "bina", searchPlaces = { places })
        } }
        if (keepKeyboardOpen) {
            compose.onNodeWithTag("osm-search-query").performScrollTo().performClick()
            compose.waitUntil(5_000) { keyboardIsVisible() }
        } else closeSoftKeyboard()
        submit()
        val results = compose.onNodeWithTag("osm-search-results")
        // Use the actual measured size: clipped bounds alone could hide an oversized list.
        val height = results.fetchSemanticsNode().size.height
        assertTrue(height > 0 && height <= with(compose.density) { 320.dp.toPx() } + 1f)
        assertFullAttribution()
        val searchButton = compose.onNodeWithTag("osm-search-submit")
        revealInDialog(searchButton)
        assertFullyVisible(searchButton)
        searchButton.assertIsEnabled()
        results.performScrollTo()
        results.performScrollToNode(hasText("Bina 39"))
        val lastResult = compose.onNodeWithText(requireNotNull(places[39].displayName))
        revealInDialog(lastResult)
        assertFullyVisible(lastResult)
        lastResult.performClick()
        compose.runOnIdle { assertSame(places[39], selected) }
        assertFullAttribution()
        if (keepKeyboardOpen) assertTrue("Keyboard must remain open during the regression", keyboardIsVisible())
        val close = compose.onNodeWithText("Kapat")
        assertFullyVisible(close)
        close.performClick()
        compose.onNodeWithTag("osm-search-query").assertDoesNotExist()
    }

    private fun keyboardIsVisible(): Boolean {
        var visible = false
        onView(isRootView()).inRoot(isDialogWindow()).check { view, failure ->
            if (failure != null) throw failure
            visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun assertFullAttribution() {
        val attribution = compose.onNodeWithTag("osm-search-attribution")
        assertFullText(attribution, "Arama Nominatim üzerinden yapılır. Sonuçlar © OpenStreetMap katkıcıları.")
    }

    private fun assertFullText(target: SemanticsNodeInteraction, expected: String, requireWrapping: Boolean = false) {
        revealInDialog(target)
        target.assertTextEquals(expected)
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val node = target.fetchSemanticsNode()
        val metrics = "text=$expected; density=${layout.layoutInput.density.density}; " +
            "fontScale=${layout.layoutInput.density.fontScale}; rootDensity=${compose.density.density}; rootFontScale=${compose.density.fontScale}; " +
            "fontSize=${layout.layoutInput.style.fontSize}; constraints=${layout.layoutInput.constraints}; " +
            "lines=${layout.lineCount}; overflowWidth=${layout.didOverflowWidth}; overflowHeight=${layout.didOverflowHeight}; hasVisualOverflow=${layout.hasVisualOverflow}; " +
            "size=${layout.size}; paragraphWidth=${layout.multiParagraph.width}; paragraphHeight=${layout.multiParagraph.height}; " +
            "lineEdges=${(0 until layout.lineCount).map { layout.getLineLeft(it) to layout.getLineRight(it) }}; " +
            "lastLineBottom=${layout.getLineBottom(layout.lineCount - 1)}; " +
            "lastLineEnd=${layout.getLineEnd(layout.lineCount - 1)}; textLength=${layout.layoutInput.text.length}; " +
            "nodeSize=${node.size}; nodePosition=${node.positionInRoot}; visibleBounds=${node.boundsInRoot}"
        android.util.Log.i("OsmSearchTextLayout", metrics)
        if (requireWrapping || expected == "  Sentetik Sokak 12  ") {
            // Only these synthetic fixtures are captured; preserve each attempt for Test to inspect.
            val folder = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
                "qa/osm-text-layout").apply { mkdirs() }
            val prefix = "text-${System.nanoTime()}"
            File(folder, "$prefix.txt").writeText(metrics + "\n" + compose.onNode(isDialog()).printToString())
            File(folder, "$prefix.png").outputStream().use {
                check(compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        if (requireWrapping) assertTrue("Long text must wrap naturally: $metrics", layout.lineCount > 1)
        assertFalse("Text layout overflows: $metrics", layout.hasVisualOverflow)
        assertEquals("Text must include the last character: $metrics", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("The last line must fit the measured text: $metrics", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
        assertFullyVisible(target)
    }

    private fun revealInDialog(target: SemanticsNodeInteraction) {
        // Scroll the nearest parent first (the list for a result, the body for other content).
        target.performScrollTo()
        // A list can be taller than the body while the IME is open. Reveal the result in that
        // outer viewport as well; a nearest-parent scroll alone does not prove it is accessible.
        val node = target.fetchSemanticsNode()
        val body = compose.onNodeWithTag("osm-search-content")
        val viewport = body.fetchSemanticsNode().boundsInRoot
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        val delta = when {
            top < viewport.top -> top - viewport.top
            bottom > viewport.bottom -> bottom - viewport.bottom
            else -> 0f
        }
        if (delta != 0f) body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
    }

    private fun assertFullyVisible(target: SemanticsNodeInteraction) {
        target.assertIsDisplayed()
        val node = target.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        assertEquals("Left edge clipped", node.positionInRoot.x, bounds.left, 1f)
        assertEquals("Top edge clipped", node.positionInRoot.y, bounds.top, 1f)
        assertEquals("Right edge clipped", node.positionInRoot.x + node.size.width, bounds.right, 1f)
        assertEquals("Bottom edge clipped", node.positionInRoot.y + node.size.height, bounds.bottom, 1f)
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
