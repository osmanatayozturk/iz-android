package org.iz.navigation.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic card geometry and callback contracts; real session regression is in ApprovedPhoneUsabilityAcceptanceTest. */
@RunWith(AndroidJUnit4::class)
class CompactNavigationCardTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(guided())
    private val panel = mutableStateOf(false)
    private var density = 0f
    private var fontScale = 0f
    private var opens = 0
    private var mutes = 0

    @Test fun shortManeuverFitsCompactCardAt360Dp() {
        render(360.dp)
        assertHomeWidth(360)
        assertCompactHeight()
        val distance = textBounds("200 m sonra")
        val mute = bounds("compact-card-mute")
        val operations = bounds("home-finish-session")
        assertTrue("Distance and controls should share the first row at 360dp", distance.overlapsVertically(mute))
        assertTrue("Operations should share the first row at 360dp", mute.overlapsVertically(operations))
        assertControlsAndMapStaySeparate()
        capture("short-360")
    }

    @Test fun shortManeuverControlsWrapWithoutOverlapAt320Dp() {
        render(320.dp)
        assertHomeWidth(320)
        assertControlsAndMapStaySeparate()
        val distance = textBounds("200 m sonra")
        val operations = bounds("home-finish-session")
        assertTrue("Operations must flow below the distance when the row cannot fit", operations.top >= distance.bottom)
        capture("short-320")
    }

    @Test fun shortManeuverFitsAllocatedNormalWidth() {
        render()
        assertTrue("The allocated normal-width device must be at least 360dp", bounds("navigation-home").width / density >= 359f)
        assertCompactHeight()
        assertControlsAndMapStaySeparate()
        capture("short-allocated-width")
    }

    @Test fun longManeuverKeepsThreeLinesAndPanelHasFullInstruction() {
        val instruction = "Sentetik kavşaktan sağa dön ve uzun sahil caddesini izleyerek ikinci yol ayrımından şehir merkezi yönünde devam et"
        state.value = guided(instruction)
        render(320.dp)
        val node = compose.onNodeWithText(instruction, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("The existing three-line instruction must not be shortened", 3, layout.lineCount)
        assertEquals(24f, layout.layoutInput.style.fontSize.value, .01f)
        assertTrue("Card must grow for a long instruction", bounds("live-guidance-card").height / density > 136f)
        assertControlsAndMapStaySeparate()
        capture("long-320")
        compose.onNodeWithTag("home-finish-session").performClick()
        compose.onNodeWithTag("live-guidance-card").assertDoesNotExist()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasText(instruction))
        compose.onNodeWithText(instruction, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-route-summary"))
        compose.onNodeWithTag("directions-route-summary").assertIsDisplayed()
    }

    @Test fun warningsAndArrivalRecordingRemainVisible() {
        state.value = guided().copy(gpsStale = true, locationActive = false, message = "Sentetik konum hatası")
        render(320.dp)
        for (message in listOf("Sentetik konum hatası", "Rota dışındasın. Yeni rota hesaplanıyor.",
            "Trafik güncellenemedi. Mevcut rota korunuyor; son trafik bilgisi eski olabilir.")) {
            compose.runOnIdle { state.value = state.value.copy(message = message) }
            compose.onNodeWithText("GPS konumu bekleniyor", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Konum bağlantısı kuruluyor", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
            assertControlsAndMapStaySeparate()
        }
        capture("warnings-320")
        compose.runOnIdle {
            state.value = state.value.copy(guidance = false, arrived = true, recording = true,
                message = "Hedefe vardın. Yolculuk kaydı devam ediyor.")
        }
        compose.onNodeWithText("Hedefe vardın. Yolculuk kaydı devam ediyor.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("home-record").assertTextContains("Kayıt açık")
        compose.onNodeWithTag("home-finish-session").assertIsDisplayed()
        capture("arrival-recording-320")
    }

    @Test fun onlyExactSimulationRepeatIsHidden() {
        val repeat = "Deneme sürüşü · günlüğe kaydedilmez"
        state.value = guided().copy(simulation = true, message = repeat)
        render(360.dp)
        compose.onNodeWithText("Simülasyon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(repeat, useUnmergedTree = true).assertDoesNotExist()
        capture("simulation-360")
        for (message in listOf("$repeat. Sentetik uyarı", "GPS konumu bekleniyor", "Hedefe vardın. Yolculuk kaydı devam ediyor.")) {
            compose.runOnIdle { state.value = state.value.copy(message = message) }
            compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
        }
        compose.runOnIdle { state.value = state.value.copy(simulation = false, message = repeat) }
        compose.onNodeWithText(repeat, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Simülasyon", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun cardAndOperationsOpenPanelWhileMuteStaysIndependent() {
        render(360.dp)
        val original = state.value
        compose.onNodeWithTag("compact-card-mute").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, mutes)
            assertEquals(0, opens)
            assertFalse(panel.value)
            assertEquals(original.copy(muted = true), state.value)
        }
        compose.onNodeWithContentDescription("Sesi aç").assertIsDisplayed()
        compose.onNodeWithTag("compact-card-mute").performTouchInput { click() }
        compose.runOnIdle { assertEquals(original, state.value); assertEquals(2, mutes); assertEquals(0, opens) }
        assertEquals("Yolculuk işlemlerini aç", compose.onNodeWithTag("live-guidance-card")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        // Tap the visible instruction, not a child action or merged semantic center.
        val instruction = textBounds("Sağa dön")
        val card = bounds("live-guidance-card")
        compose.onNodeWithTag("live-guidance-card").performTouchInput { click(instruction.center - card.topLeft) }
        assertPanelAndReturn(original, 1)
        compose.onNodeWithTag("home-finish-session").assertTextEquals("İşlemler")
            .assertContentDescriptionEquals("Yolculuk işlemlerini aç").performTouchInput { click() }
        assertPanelAndReturn(original, 2)
    }

    @Test fun freeJourneyKeepsTitleAndOperationsWithoutMute() {
        state.value = guided().copy(guidance = false, recording = true, route = null, progress = null)
        render(320.dp)
        compose.onNodeWithText("Araba · Serbest yolculuk", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("compact-card-mute").assertDoesNotExist()
        compose.onNodeWithTag("home-record").assertTextContains("Kayıt açık")
        capture("free-journey-320")
        compose.onNodeWithTag("home-finish-session").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithTag("live-guidance-card").assertDoesNotExist()
        compose.onNodeWithTag("active-session-actions").assertExists()
        compose.runOnIdle { assertTrue(state.value.recording); assertEquals(1, opens); assertEquals(0, mutes) }
    }

    private fun assertPanelAndReturn(original: NavigationState, expectedOpens: Int) {
        compose.onNodeWithTag("live-guidance-card").assertDoesNotExist()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-route-summary"))
        compose.onNodeWithTag("directions-route-summary").assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, state.value); assertEquals(expectedOpens, opens) }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("close-directions-planner"))
        compose.onNodeWithTag("close-directions-planner").performClick()
        compose.onAllNodesWithTag("live-guidance-card").assertCountEquals(1)
        compose.runOnIdle { assertEquals(original, state.value) }
    }

    private fun render(width: Dp? = null) {
        compose.setContent { IzTheme {
            density = LocalDensity.current.density
            fontScale = LocalDensity.current.fontScale
            Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width).fillMaxSize()) {
                val open = { opens++; panel.value = true }
                NavigationHomeContent(PhoneDirectionsState(), state.value, emptyList(), emptyList(),
                    panel.value, "Hava", open, {}, {}, {}, {},
                    { mutes++; state.value = state.value.copy(muted = !state.value.muted) }, open, {}, {},
                    planner = {
                        PhoneDirectionsContent(ui = PhoneDirectionsState(), nav = state.value, points = emptyList(),
                            commandBusy = false, onClose = { panel.value = false }, chooseOrigin = {}, chooseDestination = {},
                            currentOrigin = {}, transport = {}, preview = {}, start = {}, freeDrive = {}, finish = {},
                            mute = { mutes++; state.value = state.value.copy(muted = !state.value.muted) },
                            stopGuidance = {}, showLive = {}, trafficSettings = {}, lastDestination = null,
                            permissions = {}, showMap = false)
                    })
            }
        } }
        compose.onNodeWithTag("live-guidance-card").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Run only the allocated normal-font scenario", 1f, fontScale, .01f) }
    }

    private fun assertHomeWidth(expected: Int) {
        assertEquals("The host must actually provide the requested width", expected.toFloat(), bounds("navigation-home").width / density, 1f)
    }

    private fun assertCompactHeight() {
        val height = bounds("live-guidance-card").height / density
        assertTrue("Short normal-font card height should be 104–136dp; was $height", height in 104f..136f)
        compose.onAllNodes(hasText("Kalan ·", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("Tahmini varış", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("200 m sonra", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Sağa dön", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertControlsAndMapStaySeparate() {
        val card = bounds("live-guidance-card")
        val actions = listOf("compact-card-mute", "home-finish-session").map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            bounds(tag).also {
                assertTrue("$tag must remain inside the card", it.left >= card.left && it.right <= card.right &&
                    it.top >= card.top && it.bottom <= card.bottom)
            }
        }
        assertFalse("Independent tap targets must not overlap", actions[0].overlaps(actions[1]))
        listOf("directions-recenter", "map-layers").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertFalse("Card covers map control $tag", card.overlaps(bounds(tag)))
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun textBounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun Rect.overlapsVertically(other: Rect) = top < other.bottom && bottom > other.top

    private fun capture(name: String) {
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "qa/compact-navigation-card/$name-${SystemClock.uptimeMillis()}").apply { check(mkdirs()) }
        File(directory, "screen.png").outputStream().use {
            check(compose.onNodeWithTag("navigation-home").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = checkNotNull(automation.rootInActiveWindow) { "No native UI tree for compact card evidence" }
        try {
            File(directory, "ui.xml").outputStream().use { output ->
                val xml = android.util.Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true) }
                var visited = 0
                fun writeNode(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    check(++visited <= 400 && depth <= 24) { "UI evidence tree exceeds the bounded synthetic fixture" }
                    val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                    xml.startTag(null, "node")
                    mapOf("class" to node.className, "text" to node.text, "description" to node.contentDescription,
                        "bounds" to rect.flattenToString(), "clickable" to node.isClickable.toString()).forEach { (key, value) ->
                        xml.attribute(null, key, value?.toString().orEmpty())
                    }
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { writeNode(child, depth + 1) } finally { child.recycle() }
                    }
                    xml.endTag(null, "node")
                }
                writeNode(root, 0)
                xml.endDocument()
            }
        } finally { root.recycle() }
        val map = bounds("navigation-home-map")
        val card = bounds("live-guidance-card")
        File(directory, "geometry.json").writeText(JSONObject().put("density", density).put("fontScale", fontScale)
            .put("homeWidthDp", bounds("navigation-home").width / density).put("cardWidthDp", card.width / density)
            .put("cardHeightDp", card.height / density).put("cardBoundsPx", card.toString())
            .put("mapBoundsPx", map.toString()).put("cardRectangleMapAreaFraction", card.width * card.height / (map.width * map.height))
            .put("sourceCommitArgument", InstrumentationRegistry.getArguments().getString("izCandidateCommit")).toString(2))
    }

    private fun guided(instruction: String = "Sağa dön"): NavigationState {
        val a = RouteStop("Sentetik başlangıç", WeatherCoordinate(41.0, 29.0))
        val b = RouteStop("Sentetik varış", WeatherCoordinate(41.01, 29.01))
        val route = PlannedRoute("compact-card-synthetic", listOf(a, b),
            listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(b.coordinate, 600.0)),
            1500.0, 600.0, 1L, transport = Transport.CAR,
            maneuvers = listOf(RouteManeuver(1, instruction, instruction, emptyList(), 0, 1, 0.0, 600.0)))
        return NavigationState(sessionId = "compact-card-synthetic", sessionTransport = Transport.CAR,
            route = route, guidance = true, gpsStale = false, locationActive = true,
            progress = NavigationProgress(0.0, 600.0, 1500.0, 0.0, 0, 200.0))
    }
}
