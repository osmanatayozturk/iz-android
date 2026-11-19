package org.iz.navigation.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.content.ContextWrapper
import android.app.UiAutomation
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.data.Transport
import org.iz.navigation.BuildConfig
import org.iz.navigation.health.HealthPrivacyActivity
import org.iz.navigation.integration.LocalOsmMapPresentation
import org.iz.navigation.integration.OsmMapPresentation
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.*
import org.json.JSONObject
import org.json.JSONArray
import org.maplibre.android.maps.MapLibreMap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Synthetic card geometry and callback contracts; real session regression is in ApprovedPhoneUsabilityAcceptanceTest. */
@RunWith(AndroidJUnit4::class)
class CompactNavigationCardTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val uxHost = Ux0111ComposeHost(compose)
    @org.junit.After fun closeUxHost() = uxHost.close()
    private val state = mutableStateOf(guided())
    private val panel = mutableStateOf(false)
    private var density = 0f
    private var fontScale = 0f
    private var opens = 0
    private var mutes = 0
    private var weatherOpens = 0
    private var locationRequests = 0
    private val nativeMap = AtomicReference<MapLibreMap?>()

    @Test fun shortManeuverFitsCompactCardAt360Dp() {
        render(360.dp)
        assertHomeWidth(360)
        assertCompactTypography()
        val distance = bounds("hud-remaining")
        val mute = bounds("compact-card-mute")
        val operations = bounds("home-finish-session")
        assertTrue("Maneuver/remaining summary stays above the map-controls row", distance.bottom <= bounds("navigation-map-controls-row").top)
        assertTrue("Operations should share the controls row at 360dp", mute.overlapsVertically(operations))
        assertControlsAndMapStaySeparate()
        capture("short-360")
    }

    @Test fun shortManeuverControlsStaySeparateAt320Dp() {
        render(320.dp)
        assertHomeWidth(320)
        assertControlsAndMapStaySeparate()
        val distance = bounds("hud-remaining")
        val operations = bounds("home-finish-session")
        assertFalse("Operations must not cover the distance", operations.overlaps(distance))
        capture("short-320")
    }

    @Test fun shortManeuverFitsAllocatedNormalWidth() {
        render()
        assertTrue("The allocated normal-width device must be at least 360dp", bounds("navigation-home").width / density >= 359f)
        assertCompactTypography()
        assertControlsAndMapStaySeparate()
        capture("short-allocated-width")
    }

    @Test fun portrait384DpKeepsTwoManeuversStableAndReclaimsMapHeight() {
        verifyViewport(384, 832, 115f..134f, 240f, 106f,
            "İkinci kavşaktan sola dön ve sahil yoluna devam et", "portrait-384x832-compact")
    }

    @Test fun landscape832DpKeepsTwoManeuversStableAndReclaimsMapHeight() {
        verifyViewport(832, 384, 92f..105f, 128f, 23f,
            "İkinci ışıklardan sola dön ve sahil yolundan şehir merkezine devam et", "landscape-832x384-compact")
    }

    private fun verifyViewport(width: Int, height: Int, expectedHeight: ClosedFloatingPointRange<Float>,
        baselineHeight: Float, minGain: Float, next: String, evidence: String) {
        render(viewport = width to height)
        val short = readHudFrame()
        compose.runOnIdle { state.value = guided(next) }
        val natural = readHudFrame()
        val twoLine = "İkinci kavşaktan sola dön\nSahil yolundan devam et"
        compose.runOnIdle { state.value = guided(twoLine) }
        val explicitBreak = readHudFrame()
        val samples = JSONObject().put("short", short.diagnostics())
            .put("naturalWrap", natural.diagnostics())
            .put("explicitLineBreak", explicitBreak.diagnostics())
        println("COMPACT_HUD_LAYOUTS $evidence $samples")
        val captureFailure = capture(evidence, ownedWindowPng = true, textSamples = samples)

        assertHomeWidth(width)
        val home = bounds("navigation-home")
        assertEquals("Requested synthetic viewport height", height.toFloat(), home.height / density, 1f)
        assertEquals("Normal-font viewport", 1f, fontScale, .01f)
        val header = explicitBreak.header
        val map = explicitBreak.map
        val visibleMapBottom = minOf(map.bottom, bounds("home-bottom-actions").top)
        val heightDp = header.height / density
        assertTrue("Expected a header within $expectedHeight dp, got $heightDp", heightDp in expectedHeight)
        assertEquals("Real map starts immediately after HUD", header.bottom, map.top, 1f)
        assertTrue("At least ${minGain}dp of map reclaimed against 8e75446's ${baselineHeight}dp header",
            map.height / density - (home.height / density - baselineHeight) >= minGain)
        assertTrue("The gained area must also be visible above the bottom dock",
            (visibleMapBottom - map.top) / density -
                ((visibleMapBottom - home.top) / density - baselineHeight) >= minGain)
        assertMeasuredAction("hud-weather")
        if (width > height) {
            val instruction = bounds("hud-instruction")
            val summary = bounds("hud-remaining")
            assertTrue("Landscape maneuver and summary share one row", instruction.overlapsVertically(summary))
            assertTrue("Landscape summary does not cover the maneuver", instruction.right <= summary.left)
        }
        assertCompactTypography(twoLine)
        assertControlsAndMapStaySeparate()

        for ((label, frame) in listOf("naturalWrap" to natural, "explicitLineBreak" to explicitBreak)) {
            assertEquals("$label changed the header bounds", short.header, frame.header)
            assertEquals("$label changed the card bounds", short.card, frame.card)
            assertEquals("$label changed where the real map starts", short.map, frame.map)
        }
        compose.onNodeWithContentDescription(twoLine, useUnmergedTree = true).assertIsDisplayed()
        assertEquals("Explicit break must render two lines. $samples", 2, explicitBreak.instruction.lineCount)
        for ((label, frame) in listOf("short" to short, "naturalWrap" to natural, "explicitLineBreak" to explicitBreak)) {
            assertFullTextFits(label, frame.instruction, frame.instructionBounds, frame.card)
            assertFullTextFits("$label summary", frame.summary, frame.summaryBounds, frame.card)
        }
        compose.onNodeWithTag("hud-weather").assertHasClickAction().performTouchInput { click() }
        compose.onNodeWithTag("directions-recenter").assertHasClickAction().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, weatherOpens); assertEquals(1, locationRequests) }
        captureFailure?.let { throw it }
    }

    private data class HudFrame(
        val header: Rect, val card: Rect, val map: Rect,
        val instruction: TextLayoutResult, val instructionBounds: Rect,
        val summary: TextLayoutResult, val summaryBounds: Rect,
    ) {
        fun diagnostics() = JSONObject()
            .put("headerBoundsPx", header.toString()).put("cardBoundsPx", card.toString()).put("mapBoundsPx", map.toString())
            .put("instruction", instruction.diagnostics(instructionBounds))
            .put("summary", summary.diagnostics(summaryBounds))
    }

    private fun readHudFrame() = HudFrame(bounds("compact-guidance-header"), bounds("live-guidance-card"),
        bounds("navigation-home-map"), textLayout("hud-instruction"), bounds("hud-instruction"),
        textLayout("hud-remaining"), bounds("hud-remaining"))

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertFullTextFits(label: String, layout: TextLayoutResult, textBounds: Rect, card: Rect) {
        val details = "$label ${layout.diagnostics(textBounds)}"
        assertTrue(details, layout.lineCount in 1..2)
        assertFalse(details, layout.hasVisualOverflow)
        assertFalse(details, layout.multiParagraph.didExceedMaxLines)
        val last = layout.lineCount - 1
        assertEquals(details, layout.layoutInput.text.text.length, layout.getLineEnd(last, visibleEnd = true))
        for (line in 0..last) {
            assertFalse(details, layout.isLineEllipsized(line))
            assertTrue(details, layout.getLineLeft(line) >= -.5f &&
                layout.getLineRight(line) <= minOf(layout.size.width.toFloat(), textBounds.width) + 1f)
            assertTrue(details, layout.getLineTop(line) >= -.5f && layout.getLineBottom(line) <= layout.size.height + .5f)
            assertTrue(details, textBounds.top + layout.getLineTop(line) >= card.top - 1f &&
                textBounds.top + layout.getLineBottom(line) <= minOf(card.bottom, textBounds.bottom) + 1f)
        }
    }

    @Test fun longManeuverWrapsAndPanelHasFullInstruction() {
        val instruction = "Sentetik kavşaktan sağa dön ve uzun sahil caddesini izleyerek ikinci yol ayrımından şehir merkezi yönünde devam et"
        state.value = guided(instruction)
        render(320.dp)
        val node = compose.onNodeWithText(instruction, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("Long instructions use at most two visible lines", layout.lineCount in 1..2)
        node.assertContentDescriptionEquals(instruction)
        assertEquals(20f, layout.layoutInput.style.fontSize.value, .01f)
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
            compose.onNodeWithText("GPS bekleniyor", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("hud-reserved-status", useUnmergedTree = true)
                .assert(SemanticsMatcher("Full GPS warning remains accessible") {
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                        ?.any { description -> description.contains("GPS konumu bekleniyor") } == true
                })
            compose.onNodeWithText("Konum bağlantısı kuruluyor", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
            assertControlsAndMapStaySeparate()
        }
        capture("warnings-320")
        val fullWarning = requireNotNull(state.value.message)
        val beforePanel = state.value
        compose.onNodeWithTag("home-finish-session").performClick()
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasText(fullWarning))
        compose.onNodeWithText(fullWarning, useUnmergedTree = true).assertIsDisplayed()
        assertPanelAndReturn(beforePanel, 1)
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
        compose.onNodeWithTag("home-finish-session").assertHasClickAction()
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

    private fun render(width: Dp? = null, viewport: Pair<Int, Int>? = null) {
        uxHost.setContent { IzTheme {
            val scene: @Composable () -> Unit = {
                density = LocalDensity.current.density
                fontScale = LocalDensity.current.fontScale
                val open = { opens++; panel.value = true }
                NavigationHomeContent(PhoneDirectionsState(), state.value, emptyList(), emptyList(),
                    panel.value, if (viewport != null) "18°" else "Hava", open, {}, {}, {}, { weatherOpens++ },
                    { mutes++; state.value = state.value.copy(muted = !state.value.muted) }, open, { locationRequests++ }, {},
                    planner = {
                        PhoneDirectionsContent(ui = PhoneDirectionsState(), nav = state.value, points = emptyList(),
                            commandBusy = false, onClose = { panel.value = false }, chooseOrigin = {}, chooseDestination = {},
                            currentOrigin = {}, transport = {}, preview = {}, start = {}, freeDrive = {}, finish = {},
                            mute = { mutes++; state.value = state.value.copy(muted = !state.value.muted) },
                            stopGuidance = {}, showLive = {}, trafficSettings = {}, lastDestination = null,
                            permissions = {}, showMap = false)
                    })
            }
            if (viewport != null) BoxWithConstraints(Modifier.fillMaxSize()) {
                val physicalDensity = LocalDensity.current.density
                val (widthDp, heightDp) = viewport
                val fit = minOf(maxWidth.value / widthDp, maxHeight.value / heightDp)
                val localMap = remember { OsmMapPresentation(::compactLocalStyle, nativeMap::set) }
                // Fit the documented phone viewport into the owned window without changing device settings.
                CompositionLocalProvider(LocalDensity provides Density(physicalDensity * fit, 1f),
                    LocalOsmMapPresentation provides localMap) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) { scene() }
                }
            } else Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width).fillMaxSize()) { scene() }
        } }
        if (viewport == null) {
            compose.onNodeWithTag("live-guidance-card").assertIsDisplayed()
            compose.runOnIdle { assertEquals("Run only the allocated normal-font scenario", 1f, fontScale, .01f) }
        }
    }

    private fun assertHomeWidth(expected: Int) {
        assertEquals("The host must actually provide the requested width", expected.toFloat(), bounds("navigation-home").width / density, 1f)
    }

    private fun assertCompactTypography(instruction: String = "Sağa dön") {
        compose.onNodeWithTag("hud-remaining", useUnmergedTree = true).assertTextEquals("200 m · 1,5 km / 10 dk")
        compose.onAllNodes(hasText("Tahmini varış", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("hud-remaining", useUnmergedTree = true)
            .assertContentDescriptionEquals("Manevra 200 m · Kalan 1.5 km · 10 dk")
        compose.onNodeWithText(instruction, useUnmergedTree = true).assertIsDisplayed()
        fun assertSize(text: String, expected: Float, lineHeight: Float) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(expected, layouts.single().layoutInput.style.fontSize.value, .01f)
            assertEquals(lineHeight, layouts.single().layoutInput.style.lineHeight.value, .01f)
            assertEquals(2, layouts.single().layoutInput.maxLines)
        }
        assertSize(instruction, 20f, 22f)
        val summaryLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("hud-remaining", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(summaryLayouts) }
        assertEquals(15f, summaryLayouts.single().layoutInput.style.fontSize.value, .01f)
        assertEquals(17f, summaryLayouts.single().layoutInput.style.lineHeight.value, .01f)
        assertEquals(2, summaryLayouts.single().layoutInput.maxLines)
    }

    private fun assertControlsAndMapStaySeparate() {
        val card = bounds("live-guidance-card")
        val actions = listOf("compact-card-mute", "home-finish-session").map { tag ->
            assertMeasuredAction(tag)
            bounds(tag).also {
                val header = bounds("compact-guidance-header")
                assertTrue("$tag must remain inside the fixed guidance header", it.left >= header.left && it.right <= header.right &&
                    it.top >= header.top && it.bottom <= header.bottom)
            }
        }
        assertFalse("Independent tap targets must not overlap", actions[0].overlaps(actions[1]))
        val map = bounds("navigation-home-map")
        assertTrue("Real map starts below the guidance header", map.top >= bounds("compact-guidance-header").bottom - 1f)
        assertTrue("The card must end before the actual map", card.bottom <= map.top)
        compose.onNodeWithTag("map-layers").assertDoesNotExist()
        compose.onNodeWithTag("open-navigation-dashboard").assertDoesNotExist()
        listOf("directions-recenter").forEach { tag ->
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            assertMeasuredAction(tag)
            val control = bounds(tag)
            assertFalse("Card covers map control $tag", card.overlaps(control))
            assertTrue("$tag must be above the real map", control.bottom <= map.top)
        }
    }

    private fun assertMeasuredAction(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        val size = bounds(tag)
        assertTrue("$tag must occupy 48×48dp at the effective scene density",
            size.width >= 48f * density - 1f && size.height >= 48f * density - 1f)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun textBounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun Rect.overlapsVertically(other: Rect) = top < other.bottom && bottom > other.top

    private fun capture(name: String, ownedWindowPng: Boolean = false, textSamples: JSONObject? = null): Throwable? {
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "qa/compact-navigation-card/$name-${SystemClock.uptimeMillis()}").apply { check(mkdirs()) }
        val evidenceType = "OWNED_COMPOSE_SEMANTICS_NOT_PLATFORM_TALKBACK"
        val home = compose.onNodeWithTag("navigation-home", useUnmergedTree = true).fetchSemanticsNode()
        val (nodes, window) = compose.runOnIdle {
            runCatching {
                check(home.config.getOrNull(SemanticsProperties.TestTag) == "navigation-home")
                val rootView = checkNotNull((home.root as? ViewRootForTest)?.view)
                var ownerContext = rootView.context
                repeat(24) {
                    val wrapper = ownerContext as? ContextWrapper
                    if (ownerContext !is HealthPrivacyActivity && wrapper != null) {
                        val base = wrapper.baseContext
                        check(base !== ownerContext)
                        ownerContext = base
                    }
                }
                val owner = checkNotNull(ownerContext as? HealthPrivacyActivity)
                val target = InstrumentationRegistry.getInstrumentation().targetContext
                check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID != "org.iz.navigation" &&
                    owner.packageName == BuildConfig.APPLICATION_ID && owner.packageName == target.packageName)
                check(owner.applicationContext === target.applicationContext && !owner.isDestroyed && !owner.isFinishing)
                check(rootView.isAttachedToWindow && rootView.isShown && rootView.rootView === owner.window.decorView.rootView)
                val result = mutableListOf<Pair<Int, Map<String, String>>>()
                fun readNode(node: SemanticsNode, depth: Int) {
                    check(result.size < 400 && depth <= 24) { "Owned Compose evidence exceeds400nodes/depth24" }
                    check((node.root as? ViewRootForTest)?.view === rootView)
                    result += depth to mapOf("class" to "androidx.compose.ui.semantics.SemanticsNode",
                        "semanticsId" to node.id.toString(), "testTag" to node.config.getOrNull(SemanticsProperties.TestTag).orEmpty(),
                        "text" to node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text },
                        "description" to node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(),
                        "bounds" to node.boundsInRoot.toString(),
                        "hasClickAction" to (node.config.getOrNull(SemanticsActions.OnClick) != null).toString())
                    node.children.forEach { readNode(it, depth + 1) }
                }
                readNode(home, 0)
                result.toList() to owner.window
            }
        }.getOrThrow()
        File(directory, "ui.xml").outputStream().use { output ->
            val xml = android.util.Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true) }
            xml.startTag(null, "owned-compose-semantics")
            xml.attribute(null, "evidenceType", evidenceType)
            xml.attribute(null, "coordinateSpace", "COMPOSE_ROOT_PIXELS")
            var openDepth = -1
            for ((depth, attributes) in nodes) {
                while (openDepth >= depth) { xml.endTag(null, "node"); openDepth-- }
                xml.startTag(null, "node")
                attributes.forEach { (key, value) -> xml.attribute(null, key, value) }
                openDepth = depth
            }
            while (openDepth >= 0) { xml.endTag(null, "node"); openDepth-- }
            xml.endTag(null, "owned-compose-semantics")
            xml.endDocument()
        }
        val map = bounds("navigation-home-map")
        val card = bounds("live-guidance-card")
        val header = bounds("compact-guidance-header")
        val landscape = home.boundsInRoot.width > home.boundsInRoot.height
        val baselineHeight = if (landscape) 128f else 240f
        val visibleMap = Rect(map.left, map.top, map.right, minOf(map.bottom, bounds("home-bottom-actions").top))
        val metadata = JSONObject().put("density", density).put("fontScale", fontScale)
            .put("captureMethod", if (ownedWindowPng) "API34_OWNED_WINDOW_VIEWPORT_CROP" else "COMPOSE_NODE_CROP")
            .put("treeEvidenceType", evidenceType)
            .put("homeHeightDp", home.boundsInRoot.height / density)
            .put("headerHeightDp", header.height / density).put("headerBoundsPx", header.toString())
            .put("baselineHeightDp", baselineHeight).put("baselineSource", "8e75446232faa3f9ee83366d375f0030138b7c6b")
            .put("baselineInstructionSp", if (landscape) 11 else 15).put("baselineSummarySp", 12)
            .put("mapHeightGainDpAgainstBaseline", baselineHeight - (map.top - home.boundsInRoot.top) / density)
            .put("homeWidthDp", bounds("navigation-home").width / density).put("cardWidthDp", card.width / density)
            .put("cardHeightDp", card.height / density).put("cardBoundsPx", card.toString())
            .put("mapBoundsPx", map.toString()).put("cardOverlapsRealMap", card.overlaps(map))
            .put("visibleMapAboveDockBoundsPx", visibleMap.toString()).put("visibleMapAboveDockHeightDp", visibleMap.height / density)
            .put("sourceCommitArgument", InstrumentationRegistry.getArguments().getString("izCandidateCommit"))
        if (ownedWindowPng) {
            metadata.put("localRenderedRouteQuery", JSONObject.NULL).put("localMapPngBackground", "NOT_CAPTURED")
            metadata.put("textSamples", textSamples)
        }
        val geometry = File(directory, "geometry.json")
        geometry.writeText(metadata.toString(2))
        val png = File(directory, "screen.png")
        if (ownedWindowPng) {
            val route = runCatching { awaitLocalRoute() }
            metadata.put("localRenderedRouteQuery", route.isSuccess)
            route.exceptionOrNull()?.let { metadata.put("localRenderedRouteError", it.toString()) }
            geometry.writeText(metadata.toString(2))
            // A missing route must not prevent the owned HUD image from being saved.
            val image = runCatching { captureOwnedViewport(home, window, png) }
            image.getOrNull()?.let { matching ->
                metadata.put("localMapPngBackground", if (matching >= 2) "SAMPLED_BACKGROUND_VISIBLE" else "NOT_DETECTED")
                    .put("matchingLocalBackgroundSamples", matching).put("localBackgroundSamples", 4)
            }
            image.exceptionOrNull()?.let { metadata.put("pngError", it.toString()) }
            geometry.writeText(metadata.toString(2))
            return route.exceptionOrNull() ?: image.exceptionOrNull()
        } else png.outputStream().use {
            check(compose.onNodeWithTag("navigation-home").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return null
    }

    private fun compactLocalStyle(raw: String): String = JSONObject(raw).apply {
        getJSONObject("sources").remove("osm-tiles")
        val original = getJSONArray("layers")
        put("layers", JSONArray().put(JSONObject().put("id", "qa-compact-hud-background")
            .put("type", "background").put("paint", JSONObject().put("background-color", "#E4EEE6"))).apply {
            for (i in 0 until original.length()) {
                val layer = original.getJSONObject(i)
                if (layer.optString("source") != "osm-tiles" && layer.optString("type") !in setOf("symbol", "background")) put(layer)
            }
        })
        remove("glyphs")
        remove("sprite")
    }.toString().also { check("https://" !in it && "http://" !in it) }

    private fun awaitLocalRoute() {
        compose.waitUntil(20_000) {
            compose.runOnIdle {
                nativeMap.get()?.let { map ->
                    map.width > 0f && map.height > 0f &&
                        map.queryRenderedFeatures(RectF(0f, 0f, map.width, map.height), "weather-route-layer").isNotEmpty()
                } == true
            }
        }
    }

    private fun captureOwnedViewport(home: SemanticsNode, window: Window, file: File): Int {
        check(Build.VERSION.SDK_INT >= 34)
        val viewport = compose.runOnIdle {
            val view = (home.root as ViewRootForTest).view
            check(view.isAttachedToWindow && view.rootView === window.decorView.rootView)
            check(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            home.boundsInWindow
        }
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val windowBitmap = checkNotNull(automation.takeScreenshot(window))
        val captured = if (windowBitmap.config == Bitmap.Config.ARGB_8888) windowBitmap else try {
            checkNotNull(windowBitmap.copy(Bitmap.Config.ARGB_8888, false))
        } finally { windowBitmap.recycle() }
        return try {
            val left = viewport.left.roundToInt()
            val top = viewport.top.roundToInt()
            val width = viewport.right.roundToInt() - left
            val height = viewport.bottom.roundToInt() - top
            check(captured.width in 1..4096 && captured.height in 1..4096)
            check(width > 0 && height > 0 && left >= 0 && top >= 0 &&
                left + width <= captured.width && top + height <= captured.height)
            val crop = Bitmap.createBitmap(captured, left, top, width, height)
            try {
                file.outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                // Confirm that the local native map background reached the window capture.
                val expected = Color.rgb(228, 238, 230)
                val map = bounds("navigation-home-map")
                val sampleY = (map.top - home.boundsInRoot.top + map.height * .42f).roundToInt()
                check(sampleY in 0 until height)
                listOf(.18f, .32f, .68f, .82f).count { x ->
                    val pixel = crop.getPixel((width * x).roundToInt(), sampleY)
                    abs(Color.red(pixel) - Color.red(expected)) <= 12 &&
                        abs(Color.green(pixel) - Color.green(expected)) <= 12 &&
                        abs(Color.blue(pixel) - Color.blue(expected)) <= 12
                }
            } finally { if (crop !== captured) crop.recycle() }
        } finally { captured.recycle() }
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

private fun TextLayoutResult.diagnostics(bounds: Rect): JSONObject {
    val text = layoutInput.text.text
    val constraints = layoutInput.constraints
    val lines = JSONArray()
    for (line in 0 until lineCount) {
        val start = getLineStart(line)
        val visibleEnd = getLineEnd(line, visibleEnd = true)
        lines.put(JSONObject()
            .put("start", start).put("end", getLineEnd(line, visibleEnd = false)).put("visibleEnd", visibleEnd)
            .put("visibleText", text.substring(start, visibleEnd))
            .put("ellipsized", isLineEllipsized(line))
            .put("topPx", getLineTop(line)).put("bottomPx", getLineBottom(line))
            .put("leftPx", getLineLeft(line)).put("rightPx", getLineRight(line)))
    }
    return JSONObject().put("fullText", text).put("textLength", text.length).put("boundsPx", bounds.toString())
        .put("widthPx", size.width).put("heightPx", size.height)
        .put("availableWidthPx", constraints.maxWidth).put("availableHeightPx", constraints.maxHeight)
        .put("hasBoundedHeight", constraints.hasBoundedHeight).put("minimumHeightPx", constraints.minHeight)
        .put("fontSizeSp", layoutInput.style.fontSize.value).put("lineHeightSp", layoutInput.style.lineHeight.value)
        .put("maxLines", layoutInput.maxLines).put("renderedLines", lineCount)
        .put("didExceedMaxLines", multiParagraph.didExceedMaxLines)
        .put("didOverflowWidth", didOverflowWidth).put("didOverflowHeight", didOverflowHeight)
        .put("firstLine", lines.opt(0)).put("lastLine", lines.opt(lineCount - 1))
        .put("lines", lines)
}
