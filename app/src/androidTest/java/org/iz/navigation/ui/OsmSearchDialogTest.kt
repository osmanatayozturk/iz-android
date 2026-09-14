package org.iz.navigation.ui

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test fun fullLongNameAndExistingAddressWrapAtActualDialogFontScale() {
        val place = SelectedOsmPlace(
            "Sentetik araştırma binası ek hizmet ve deney merkezi", 40.0, 30.0, OsmRef(OsmType.WAY, 301),
            displayName = "Sentetik araştırma binası ek hizmet ve deney merkezi, Uzun Deneme Sokağı 12, Örnek Mahallesi, Test İlçesi",
        )
        var selected: SelectedOsmPlace? = null
        compose.setContent {
            // Dialog owns its density; Test controls the actual system font-scale matrix.
            IzTheme { OsmSearchDialog({}, { selected = it }, initialQuery = "bina", searchPlaces = { listOf(place) }) }
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

    @Test fun initialTenLongResultsKeepCloseTouchableAboveImeWithoutScrolling() {
        assertEquals(1f, InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale, .01f)
        val places = List(10) { index ->
            SelectedOsmPlace(if (index == 0) "Sentetik araştırma binası ek hizmet ve deney merkezi" else "Sentetik IME durağı $index",
                .001 + index * .001, .002 + index * .001, OsmRef(OsmType.WAY, 4_100_692_000_000L + index),
                displayName = if (index == 0) "Sentetik araştırma binası ek hizmet ve deney merkezi, Uzun Deneme Sokağı 12, Örnek Mahallesi, Test İlçesi"
                else "Sentetik IME durağı $index, Test Sokağı $index")
        }
        val queries = CopyOnWriteArrayList<String>()
        var selected: SelectedOsmPlace? = null
        var visible by mutableStateOf(true)
        compose.setContent { IzTheme {
            if (visible) OsmSearchDialog({ visible = false }, { selected = it },
                initialQuery = "sentetik uzun sonuç", searchPlaces = { query -> queries += query; places })
        } }
        compose.onNodeWithTag("osm-search-query").performClick()
        compose.waitUntil(5_000) { OsmDialogImeTouch.keyboardIsVisible() }
        OsmDialogImeTouch.tapButton("Ara") // No reveal, scroll or keyboard dismissal.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(places.first().name).fetchSemanticsNodes().isNotEmpty() }
        val folder = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "qa/k05-initial-close/${SystemClock.uptimeMillis()}").apply { check(mkdirs()) }
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(folder, "initial.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
        // This is deliberately before attribution/result scrolling: earlier tests missed this state.
        File(folder, "initial-close.json").writeText(OsmDialogImeTouch.buttonFacts("Kapat").toString(2))
        OsmDialogImeTouch.tapButton("Kapat")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("osm-search-query").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertNull(selected); assertEquals(listOf("sentetik uzun sonuç"), queries.toList()) }
    }

    @Test fun tappingOutsideSearchSurfaceDismissesWithoutSelecting() {
        var visible by mutableStateOf(true)
        var selected: SelectedOsmPlace? = null
        compose.setContent { IzTheme {
            if (visible) OsmSearchDialog({ visible = false }, { selected = it }, searchPlaces = { emptyList() })
        } }
        closeSoftKeyboard()
        val viewport = compose.onNodeWithTag("osm-search-viewport")
        val surface = compose.onNodeWithTag("osm-search-surface")
        val area = viewport.fetchSemanticsNode().boundsInRoot
        val card = surface.fetchSemanticsNode().boundsInRoot
        assertTrue("Empty dialog must leave a genuine outside area", card.top - area.top > 4f)
        viewport.performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, 1f)) }
        compose.onNodeWithTag("osm-search-query").assertDoesNotExist()
        compose.runOnIdle { assertNull(selected) }
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
        if (keepKeyboardOpen) OsmDialogImeTouch.buttonFacts("Kapat") // Initial state, before any result/attribution swipe.
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
        if (keepKeyboardOpen) OsmDialogImeTouch.tapButton("Kapat") else close.performClick()
        compose.onNodeWithTag("osm-search-query").assertDoesNotExist()
    }

    @Test fun emptyResultKeepsCloseTouchableWithKeyboard() = verifyEmptyOrErrorImeClose(false)

    @Test fun errorKeepsCloseTouchableWithKeyboard() = verifyEmptyOrErrorImeClose(true)

    @Test fun dnsErrorKeepsCloseTouchableWithKeyboard() = verifyEmptyOrErrorImeClose(error = true, dns = true)

    private fun verifyEmptyOrErrorImeClose(error: Boolean, dns: Boolean = false) {
        var visible by mutableStateOf(true)
        compose.setContent { IzTheme {
            if (visible) OsmSearchDialog({ visible = false }, {}, initialQuery = "bina", searchPlaces = {
                if (dns) throw java.net.UnknownHostException("synthetic.invalid")
                if (error) throw IllegalArgumentException("Arama en fazla 200 karakter olabilir.")
                emptyList()
            })
        } }
        compose.onNodeWithTag("osm-search-query").performClick()
        compose.waitUntil(5_000) { OsmDialogImeTouch.keyboardIsVisible() }
        OsmDialogImeTouch.tapButton("Ara", reveal = true)
        val stateTag = if (error) "osm-search-error" else "osm-search-empty"
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(stateTag).fetchSemanticsNodes().isNotEmpty() }
        if (dns) compose.onNodeWithTag(stateTag).assertTextEquals("Arama servisine ulaşılamadı. Bağlantını kontrol edip yeniden ara.")
        OsmDialogImeTouch.buttonFacts("Kapat")
        OsmDialogImeTouch.tapButton("Kapat")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("osm-search-query").fetchSemanticsNodes().isEmpty() }
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
        val systemFontScale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        val metrics = "text=$expected; density=${layout.layoutInput.density.density}; " +
            "fontScale=${layout.layoutInput.density.fontScale}; systemFontScale=$systemFontScale; rootDensity=${compose.density.density}; rootFontScale=${compose.density.fontScale}; " +
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
        assertEquals("Dialog must use the actual system font scale: $metrics", systemFontScale, layout.layoutInput.density.fontScale, .01f)
        assertEquals("Layout must preserve the original text: $metrics", expected, layout.layoutInput.text.text)
        // Cached paragraph width can exceed an intrinsic text node without clipping its lines.
        // Check every rendered line against both measured sizes instead of hasVisualOverflow.
        assertFalse("Text height overflows: $metrics", layout.didOverflowHeight)
        val availableWidth = minOf(layout.size.width, node.size.width) + 1f
        val availableHeight = minOf(layout.size.height, node.size.height) + 1f
        for (line in 0 until layout.lineCount) {
            assertFalse("Line $line is ellipsized: $metrics", layout.isLineEllipsized(line))
            assertTrue("Line $line starts outside the text node: $metrics", layout.getLineLeft(line) >= -1f)
            assertTrue("Line $line ends outside the text node: $metrics", layout.getLineRight(line) <= availableWidth)
            assertTrue("Line $line starts above the text node: $metrics", layout.getLineTop(line) >= -1f)
            assertTrue("Line $line ends below the text node: $metrics", layout.getLineBottom(line) <= availableHeight)
        }
        assertEquals("Text must include the last character: $metrics", expected.length, layout.getLineEnd(layout.lineCount - 1))
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

/** IME-001: screen hit regions + real touch input, shared by component and real Activity tests. */
internal object OsmDialogImeTouch {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun imeWindow(): String? {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window windows"))
            .bufferedReader().use { it.readText() }
        return dump.split(Regex("(?m)(?=^  Window #[0-9]+)"))
            .singleOrNull { it.lineSequence().first().contains(" u0 InputMethod}") &&
                it.contains("mViewVisibility=0x0") && it.contains("mHasSurface=true") }
    }

    fun keyboardIsVisible(): Boolean = imeWindow()?.contains("touchable region=SkRegion((") == true

    private data class Geometry(val usable: Rect, val ime: Rect, val window: String)

    private fun geometry(): Geometry {
        val window = checkNotNull(imeWindow()) { "A visible IME window is required" }
        // WindowManager reports this touchable region in SCREEN coordinates (not IME-local insets).
        val region = checkNotNull(Regex("touchable region=SkRegion\\(\\(([0-9]+),([0-9]+),([0-9]+),([0-9]+)\\)\\)").find(window)) {
            "Expected the allocated docked keyboard's single rectangular touch region"
        }
        val ime = Rect(region.groupValues[1].toInt(), region.groupValues[2].toInt(),
            region.groupValues[3].toInt(), region.groupValues[4].toInt())
        val display = checkNotNull(Regex("display=\\[([0-9]+),([0-9]+)\\]\\[([0-9]+),([0-9]+)\\]").find(window))
        val usable = Rect(display.groupValues[1].toInt(), display.groupValues[2].toInt(),
            display.groupValues[3].toInt(), ime.top)
        check(!usable.isEmpty && ime.left <= usable.left && ime.right >= usable.right) {
            "Unknown keyboard/display geometry; do not guess touch coordinates"
        }
        return Geometry(usable, ime, window.lineSequence().filter {
            it.contains("touchable region=") || it.contains("Frames:") || it.contains("mAttrs=")
        }.joinToString("\n"))
    }

    private fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { findNode(child, predicate)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    private fun searchRoot(): AccessibilityNodeInfo {
        val root = checkNotNull(automation.rootInActiveWindow) { "No active application root" }
        val title = findNode(root) { it.text?.toString() == "Yer ara" }
        if (title == null) { root.recycle(); error("The active root must be the search dialog") }
        title.recycle()
        return root
    }

    private fun buttonBounds(label: String): Rect? {
        val root = searchRoot()
        try {
            var node = findNode(root) { it.text?.toString() == label } ?: return null
            try {
                while (!node.isClickable) {
                    val parent = node.parent ?: error("No clickable ancestor for $label")
                    node.recycle(); node = parent
                }
                check(node.isEnabled) { "$label is disabled" }
                return Rect().also(node::getBoundsInScreen)
            } finally { node.recycle() }
        } finally { root.recycle() }
    }

    fun buttonFacts(label: String): JSONObject {
        val geometry = geometry()
        val bounds = checkNotNull(buttonBounds(label)) { "No $label button in search window" }
        val minimum = 48 * instrumentation.targetContext.resources.displayMetrics.density
        check(bounds.width() >= minimum - 1 && bounds.height() >= minimum - 1 && geometry.usable.contains(bounds)) {
            "$label full clickable bounds=$bounds must fit IME-free viewport=${geometry.usable}; IME=${geometry.ime}"
        }
        return JSONObject().put("button", label).put("clickableBoundsScreen", bounds.flattenToString())
            .put("usableScreen", geometry.usable.flattenToString()).put("imeTouchableScreen", geometry.ime.flattenToString())
            .put("windowGeometry", geometry.window)
    }

    fun tapButton(label: String, reveal: Boolean = false): JSONObject {
        if (reveal) {
            for (attempt in 0 until 5) {
                val geometry = geometry()
                val bounds = buttonBounds(label)
                if (bounds != null && geometry.usable.contains(bounds)) break
                val root = searchRoot()
                val scroll = try { findNode(root) { it.isScrollable && it.className == "android.widget.ScrollView" } }
                    finally { root.recycle() }
                checkNotNull(scroll) { "No outer scroll viewport for $label" }
                val viewport = try { Rect().also(scroll::getBoundsInScreen) } finally { scroll.recycle() }
                check(viewport.intersect(geometry.usable)) { "Scroll viewport is behind IME" }
                viewport.inset(12, 12)
                check(viewport.height() > 40 && viewport.width() > 24) { "No safe swipe area" }
                val towardTop = bounds == null || bounds.top < geometry.usable.top
                val fromY = if (towardTop) viewport.top.toFloat() else viewport.bottom.toFloat()
                val toY = if (towardTop) viewport.bottom.toFloat() else viewport.top.toFloat()
                gesture(viewport.exactCenterX(), fromY, toY)
            }
        }
        val facts = buttonFacts(label) // Fresh full-bounds check immediately before input.
        val bounds = checkNotNull(Rect.unflattenFromString(facts.getString("clickableBoundsScreen")))
        gesture(bounds.exactCenterX(), bounds.exactCenterY(), bounds.exactCenterY())
        return facts.put("input", "screen ACTION_DOWN/ACTION_UP")
    }

    private fun gesture(x: Float, fromY: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) { "Screen input was rejected" } }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, fromY)
        if (fromY != toY) for (step in 1..8) {
            SystemClock.sleep(30)
            inject(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * step / 8)
        }
        SystemClock.sleep(60)
        inject(MotionEvent.ACTION_UP, toY)
        instrumentation.waitForIdleSync()
    }
}
