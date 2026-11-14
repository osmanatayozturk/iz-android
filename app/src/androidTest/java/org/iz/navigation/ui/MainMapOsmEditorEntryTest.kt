package org.iz.navigation.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditFieldAction
import org.iz.navigation.data.MapEditStage
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.integration.OsmServiceSettings
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProvider
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpResponder
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Actual MainActivity content and native MapView gesture. No setContent, injected entry callback,
 * standalone form, fake map, live tiles or public OSM write. Execute only under the later QA GO.
 */
@RunWith(AndroidJUnit4::class)
class MainMapOsmEditorEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var observedTapDownTime = Long.MIN_VALUE
    private var observedWindowTouches = JSONArray()
    private val unconsumedMapClicks = AtomicInteger()

    /** Real MainActivity, production cache/parser/owner and native map. All geographic data is
     * synthetic; these fixtures do not establish live Overpass or public-map correctness. */
    @Test(timeout = 90_000)
    fun nativeMultiSelectionKeepsInformationBackAndMapInteraction() = runBlocking {
        val app = readySelectionApp()
        val repository = DiaryRepository(app)
        val baseline = repository.snapshot()
        val baselineSession = session(app)
        withOfflineMainActivity(app) { host ->
            val (view, map) = readyMap(host)
            val fixture = discoverSynthetic(app, host, view, map)
            compose.onNodeWithTag("place-info-open-information").assertIsNotEnabled()
            compose.onNodeWithTag("place-info-discovery-choice-0").assertIsNotSelected()
            compose.onNodeWithTag("place-info-discovery-choice-1").assertIsNotSelected()
            OsmLightEditorNativeCapture.capture("map-01-many")
            compose.onNodeWithTag("place-info-discovery-choice-0").performClick().assertIsSelected()
            compose.onNodeWithTag("place-info-map-candidate-0").assertIsSelected()
            compose.onNodeWithTag("place-info-open-information").assertIsEnabled()
            OsmLightEditorNativeCapture.capture("map-02-point")
            compose.onNodeWithTag("place-info-open-information").performClick()
            compose.onNodeWithTag("place-information-card").assertExists()
            compose.onNodeWithText(fixture.nodeName).assertExists()
            OsmLightEditorNativeCapture.capture("map-03-details")
            Espresso.pressBack()
            compose.onNodeWithTag("place-info-discovery-choice-0").assertIsSelected()
            compose.onNodeWithTag("place-info-open-information").assertIsEnabled()
            OsmLightEditorNativeCapture.capture("map-04-back")
            assertExposedMapPans(app, view, map)
            // New geographic tap replaces the old list. No query is required on back or pan.
            val next = discoverSynthetic(app, host, view, map, count = 0)
            compose.onAllNodesWithTag("place-info-discovery-choice-0").assertCountEquals(0)
            compose.onNodeWithTag("place-info-open-information").assertIsNotEnabled()
            compose.onNodeWithTag("place-info-discovery-close").performClick()
            compose.onAllNodesWithTag("place-info-map-candidate-0").assertCountEquals(0)
            assertNotEquals(fixture.wayId, next.wayId)
            // A single way goes straight to the existing information window, without an outline.
            val single = discoverSynthetic(app, host, view, map, count = 1)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("place-information-card").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(single.wayName).assertExists()
            compose.onNodeWithTag("place-info-discovery").assertDoesNotExist()
            Espresso.pressBack()
            assertEquals(baselineSession, session(app))
        }
        assertEquals(baseline, repository.snapshot())
    }

    @Test(timeout = 90_000)
    fun nativeSelectedAreaDrawsRealRingAndMarkerLongPressKeepsEntry() = runBlocking {
        val app = readySelectionApp()
        val repository = DiaryRepository(app)
        val before = repository.snapshot()
        val baselineSession = session(app)
        var owned: MapEditDraft? = null
        try {
            withOfflineMainActivity(app) { host ->
                val (view, map) = readyMap(host)
                val fixture = discoverSynthetic(app, host, view, map, area = true)
                compose.onNodeWithTag("place-info-discovery-choice-1").performClick().assertIsSelected()
                compose.waitUntil(10_000) { onMain {
                    map.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "selected-place-area-fill")
                        .any { it.getNumberProperty("osmId")?.toLong() == fixture.wayId && it.geometry()?.type() == "Polygon" }
                } }
                compose.onNodeWithTag("place-info-map-candidate-1").assertIsSelected().assertIsDisplayed()
                OsmLightEditorNativeCapture.capture("map-05-area")
                compose.onNodeWithTag("place-info-map-candidate-1").performTouchInput { longClick() }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("osm-map-name").fetchSemanticsNodes().size == 1 }
                owned = repository.snapshot().mapEdits.filterNot { draft -> before.mapEdits.any { it.id == draft.id } }.single()
                compose.onNodeWithTag("osm-map-name").performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                compose.onNodeWithTag("osm-map-phone").performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                assertNull(requireNotNull(owned).presetId)
                assertEquals(baselineSession, session(app))
                Espresso.pressBack()
                Espresso.pressBack()
            }
            val after = repository.snapshot()
            assertEquals(before, after.copy(mapEdits = after.mapEdits.filterNot { it.id == owned?.id }))
        } finally { owned?.let { repository.deleteMapEditIfUnchanged(it) } }
    }

    @Test(timeout = 90_000)
    fun nativeSelectionPanelRemainsNonmodalInLandscape() = runBlocking {
        val app = readySelectionApp()
        check(InstrumentationRegistry.getArguments().getString("osmMapSelectionAllowLandscape") == "true") {
            "Owned QA Activity orientation change must be explicitly admitted"
        }
        val before = DiaryRepository(app).snapshot()
        val baselineSession = session(app)
        withOfflineMainActivity(app) { host ->
            var original = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            host.onActivity { original = it.requestedOrientation; it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            try {
                compose.waitUntil(10_000) { app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                OsmLightEditorQaTargetAdmission.requireAdmittedTarget(allowLandscape = true)
                val (view, map) = readyMap(host)
                discoverSynthetic(app, host, view, map)
                compose.onNodeWithTag("place-info-discovery-choice-0").performClick().assertIsSelected()
                compose.onNodeWithTag("place-info-open-information").assertIsEnabled().assertIsDisplayed()
                compose.onNodeWithTag("place-info-discovery-collapse").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
                OsmLightEditorNativeCapture.capture("map-06-landscape")
                assertExposedMapPans(app, view, map)
                compose.onNodeWithTag("place-info-discovery-collapse").performClick()
                compose.onNodeWithTag("place-info-open-information").assertIsDisplayed()
                compose.onNodeWithTag("place-info-discovery-close").performClick()
                assertEquals(baselineSession, session(app))
            } finally { host.onActivity { it.requestedOrientation = original } }
        }
        assertEquals(before, DiaryRepository(app).snapshot())
    }

    private suspend fun readySelectionApp(): IzApplication {
        val app = instrumentation.targetContext.applicationContext as IzApplication
        requireIsolatedOfflineQa(app)
        withTimeout(10_000) { app.osmReady.await() }
        check(!TrackingService.isRunning && !app.navigation.state.value.recording && !app.navigation.state.value.guidance)
        return app
    }

    private data class SyntheticSelection(val wayId: Long, val nodeName: String, val wayName: String)

    private fun discoverSynthetic(app: Context, host: ActivityScenario<MainActivity>, view: MapView, map: MapLibreMap, count: Int = 2, area: Boolean = false): SyntheticSelection {
        val nonce = UUID.randomUUID().mostSignificantBits and 0x7fffffff
        val id = 8_000_000_000_000L + nonce * 10
        val fixture = SyntheticSelection(id + 2, "Synthetic QA point $nonce", "Synthetic QA area $nonce")
        // Fresh, deliberately synthetic origin for each tap; never a device fix or recorded route.
        onMain { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.0 + nonce % 10000 / 100000.0, 25.0 + nonce % 7000 / 100000.0), 18.0)) }
        compose.waitForIdle()
        val point = PointF(view.width * .35f, view.height * .36f)
        onMain { observedTapDownTime = Long.MIN_VALUE; observedWindowTouches = JSONArray(); unconsumedMapClicks.set(0) }
        val touchSetup = mainMapTouchDiagnostics(view, map, point)
        val tapped = onMain { map.projection.fromScreenLocation(point) }
        val wayCenter = LatLng(tapped.latitude, tapped.longitude + .00030)
        fun tags(name: String, way: Boolean) = JSONObject().put("name", name).put(if (way) "building" else "amenity", if (way) "yes" else "bench")
        val elements = JSONArray()
        if (count == 2) elements.put(JSONObject().put("type", "node").put("id", id + 1)
            .put("lat", tapped.latitude).put("lon", tapped.longitude).put("tags", tags(fixture.nodeName, false)))
        if (count > 0) elements.put(JSONObject().put("type", "way").put("id", fixture.wayId)
            .put("center", JSONObject().put("lat", wayCenter.latitude).put("lon", wayCenter.longitude)).put("tags", tags(fixture.wayName, true)))
        val endpoint = OsmServiceSettings(app).read().overpass
        val key = "nearby:editor-v2:${OsmPresetCatalog.BUNDLED_SHA256}:$endpoint:${String.format(Locale.US, "%.4f", tapped.latitude)}:${String.format(Locale.US, "%.4f", tapped.longitude)}"
        MapSelectionCacheFixture.store(app, key, JSONObject().put("elements", elements).toString())
        if (area) {
            val vertices = listOf(-.00010 to -.00010, -.00010 to .00010, .00010 to .00010, .00010 to -.00010, -.00010 to -.00010)
            val geometry = JSONArray().also { a -> vertices.forEach { (lat, lon) -> a.put(JSONObject().put("lat", wayCenter.latitude + lat).put("lon", wayCenter.longitude + lon)) } }
            val body = JSONObject().put("elements", JSONArray().put(JSONObject().put("type", "way").put("id", fixture.wayId)
                .put("tags", tags(fixture.wayName, true)).put("nodes", JSONArray(listOf(id + 3, id + 4, id + 5, id + 6, id + 3))).put("geometry", geometry)))
            MapSelectionCacheFixture.store(app, "map-outline:simple-area-v1:${OsmPresetCatalog.BUNDLED_SHA256}:$endpoint:WAY/${fixture.wayId}", body.toString())
        }
        val screen = onMain { IntArray(2).also(view::getLocationOnScreen) }
        fun afterTouch() = runCatching { mainMapTouchDiagnostics(view, map, point) }
                .getOrElse { JSONObject().put("diagnosticFailureClass", it.javaClass.name) }
        observeMainInput(host, map).use {
            var after: JSONObject? = null
            try {
                tapNative(screen[0] + point.x, screen[1] + point.y)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(if (count == 1) "place-information-card" else "place-info-discovery").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                after = afterTouch()
                if (area && InstrumentationRegistry.getArguments().getString("osmMapSelectionCaptureFailure") == "true") {
                    runCatching { OsmLightEditorNativeCapture.capture("diagnostic-main-map-touch-failure") }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw AssertionError("Actual Main map selection did not appear; before=$touchSetup; after=$after", failure)
            } finally {
                val report = JSONObject().put("before", touchSetup).put("after", after ?: afterTouch())
                instrumentation.addResults(Bundle().apply { putString("osmMapSelectionTap_$nonce", report.toString()) })
            }
        }
        if (count == 2) compose.waitUntil(10_000) { compose.onAllNodesWithTag("place-info-discovery-choice-1").fetchSemanticsNodes().isNotEmpty() }
        if (count == 0) compose.waitUntil(10_000) { compose.onAllNodesWithTag("place-info-discovery-choice-0").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("Yakında bir OSM yeri veya bina bulunamadı. Yeni OSM mekânı için haritaya uzun basabilirsin.").fetchSemanticsNodes().isNotEmpty() }
        return fixture
    }

    /** Forward the actual Main window unchanged; never replace the SDK's existing listeners. */
    private fun observeMainInput(host: ActivityScenario<MainActivity>, map: MapLibreMap): AutoCloseable {
        lateinit var window: Window
        lateinit var previous: Window.Callback
        lateinit var observer: Window.Callback
        val unconsumed = MapLibreMap.OnMapClickListener { unconsumedMapClicks.incrementAndGet(); false }
        host.onActivity { activity ->
            window = activity.window
            previous = requireNotNull(window.callback)
            observer = object : Window.Callback by previous {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    val observation = if (event.downTime == observedTapDownTime && observedWindowTouches.length() < 4)
                        JSONObject().put("action", event.actionMasked).put("downTime", event.downTime).put("eventTime", event.eventTime)
                            .put("x", event.x.toDouble()).put("y", event.y.toDouble()) else null
                    val consumed = previous.dispatchTouchEvent(event)
                    observation?.let { observedWindowTouches.put(it.put("consumed", consumed)) }
                    return consumed
                }
            }
            window.callback = observer
            map.addOnMapClickListener(unconsumed)
        }
        return AutoCloseable { onMain {
            map.removeOnMapClickListener(unconsumed)
            check(window.callback === observer) { "Owned Main window callback changed during touch probe" }
            window.callback = previous
        } }
    }

    /** Bounded synthetic setup evidence only: keep the original gesture, owner and timeout. */
    private fun mainMapTouchDiagnostics(view: MapView, map: MapLibreMap, point: PointF): JSONObject {
        val origin = onMain { IntArray(2).also(view::getLocationInWindow) }
        val x = origin[0] + point.x
        val y = origin[1] + point.y
        val mapNodes = compose.onAllNodesWithTag("navigation-home-map").fetchSemanticsNodes()
        fun rootOf(node: SemanticsNode): SemanticsNode = generateSequence(node) { it.parent }.last()
        val mainRoot = mapNodes.singleOrNull()?.let { rootOf(it).layoutInfo }
        val covering = compose.onAllNodes(SemanticsMatcher("Owned Main semantic geometry") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes().filter { node ->
                val b = node.boundsInWindow
                mainRoot != null && rootOf(node).layoutInfo === mainRoot && x >= b.left && x < b.right && y >= b.top && y < b.bottom
            }
        val coveringFlags = covering.take(16).map { node -> JSONObject()
            .put("id", node.id).put("parentId", node.parent?.id)
            .put("tag", if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else "untagged")
            .put("boundsInWindow", node.boundsInWindow.toString())
            .put("hasClick", node.config.contains(SemanticsActions.OnClick))
            .put("hasText", node.config.contains(SemanticsProperties.Text))
            .put("hasTraversalGroupKey", node.config.contains(SemanticsProperties.IsTraversalGroup)) }
        val blockers = compose.onAllNodes(hasClickAction() or hasTestTag("home-brand") or hasTestTag("place-info-discovery"),
            useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
            val b = node.boundsInWindow
            if (x < b.left || x >= b.right || y < b.top || y >= b.bottom) null else JSONObject()
                .put("tag", if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else "untagged-clickable")
                .put("boundsInWindow", b.toString())
        }.take(8)
        val native = onMain {
            val camera = map.cameraPosition
            val gestures = map.gesturesManager
            val standard = gestures.standardGestureDetector
            val shownMaps = view.rootView.allMapViews().filter { it.isShown }
            val mapIdentities = shownMaps.take(4).map { candidate ->
                val position = IntArray(2).also(candidate::getLocationInWindow)
                var immediate: MapLibreMap? = null
                candidate.getMapAsync { immediate = it }
                JSONObject().put("viewIdentity", System.identityHashCode(candidate)).put("isProbedView", candidate === view)
                    .put("boundsInWindow", Rect(position[0], position[1], position[0] + candidate.width, position[1] + candidate.height).toShortString())
                    .put("enabled", candidate.isEnabled).put("focusedWindow", candidate.hasWindowFocus())
                    .put("mapCallbackImmediate", immediate != null).put("isProbedMap", immediate === map)
                    .put("mapIdentity", immediate?.let { System.identityHashCode(it) })
            }
            fun event(value: MotionEvent?): Any = value?.let {
                JSONObject().put("action", it.actionMasked).put("downTime", it.downTime).put("eventTime", it.eventTime)
                    .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("source", it.source)
                    .put("matchesInjectedDownTime", observedTapDownTime != Long.MIN_VALUE && it.downTime == observedTapDownTime)
            } ?: JSONObject.NULL
            JSONObject().put("windowFocused", view.hasWindowFocus()).put("attached", view.isAttachedToWindow)
                .put("viewBoundsInWindow", Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height).toShortString())
                .put("zoom", camera.zoom).put("bearing", camera.bearing).put("tilt", camera.tilt)
                .put("syntheticCameraLatitude", camera.target?.latitude).put("syntheticCameraLongitude", camera.target?.longitude)
                .put("styleFullyLoaded", map.style?.isFullyLoaded == true)
                .put("injectedDownTime", observedTapDownTime).put("windowTouches", JSONArray(observedWindowTouches.toString()))
                .put("sdkStandardEnabled", standard.isEnabled).put("sdkCurrentEvent", event(standard.currentEvent))
                .put("sdkPreviousEvent", event(standard.previousEvent)).put("unconsumedMapClickCount", unconsumedMapClicks.get())
                .put("viewIdentity", System.identityHashCode(view)).put("mapIdentity", System.identityHashCode(map))
                .put("gesturesIdentity", System.identityHashCode(gestures)).put("standardIdentity", System.identityHashCode(standard))
                .put("standardInDetectorList", gestures.detectors.any { it === standard })
                .put("shownMapViewCount", shownMaps.size).put("shownMapViews", JSONArray(mapIdentities))
        }
        return JSONObject().put("windowKind", "OWNED_MAIN_ACTIVITY").put("native", native)
            .put("nativeLocalX", point.x.toDouble()).put("nativeLocalY", point.y.toDouble())
            .put("mainMapBoundsInWindow", JSONArray(mapNodes.map { it.boundsInWindow.toString() }))
            .put("selectionPanelCount", compose.onAllNodesWithTag("place-info-discovery").fetchSemanticsNodes().size)
            .put("fullscreenCount", compose.onAllNodesWithTag("fullscreen_map").fetchSemanticsNodes().size)
            .put("coveringSemanticRegions", JSONArray(blockers))
            .put("coveringMainSemanticBoundsAndFlags", JSONArray(coveringFlags)).put("coveringMainSemanticsTruncated", covering.size > 16)
    }

    private fun View.allMapViews(): List<MapView> = if (this is MapView) listOf(this)
        else if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).allMapViews() } else emptyList()

    private fun assertExposedMapPans(app: Context, view: MapView, map: MapLibreMap) {
        val budget = app.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE).all.toMap()
        val old = onMain { map.cameraPosition.target }
        // A swipe is a native pan above the measured panel; it must not issue a discovery query.
        compose.onNodeWithTag("place-info-discovery-collapse").performClick()
        val screen = onMain { IntArray(2).also(view::getLocationOnScreen) }
        val x = screen[0] + view.width * .52f
        val y = screen[1] + view.height * .48f
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },
                x + step * 8, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(16)
        }
        compose.waitUntil(5_000) { onMain { map.cameraPosition.target != old } }
        compose.onNodeWithTag("place-info-discovery-choice-0").assertDoesNotExist()
        assertEquals(budget, app.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE).all.toMap())
        compose.onNodeWithTag("place-info-discovery-collapse").performClick()
        compose.onNodeWithTag("place-info-discovery-choice-0").assertExists()
    }

    private fun tapNative(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        onMain { observedTapDownTime = down }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    @Test(timeout = 60_000)
    fun nativeMainMapLongPressOpensBlankRequiredTypeFormAndReturnsWithoutChangingTheSession() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as IzApplication
        requireIsolatedOfflineQa(app)
        withTimeout(10_000) { app.osmReady.await() }
        check(!TrackingService.isRunning && !app.navigation.state.value.recording && !app.navigation.state.value.guidance) {
            "Existing recording/guidance must be idle; this test never resets or stops it."
        }
        val repository = DiaryRepository(app)
        val before = repository.snapshot()
        val beforeIds = before.mapEdits.map { it.id }.toSet()
        val baselineSession = session(app)
        var ownedDraft: MapEditDraft? = null
        try {
            withOfflineMainActivity(app) { host ->
                compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
                val (view, map) = readyMap(host)
                val touch = onMain {
                    val visible = Rect()
                    check(view.getGlobalVisibleRect(visible) && view.width > 0 && view.height > 0)
                    val screen = IntArray(2).also(view::getLocationOnScreen)
                    // Away from the top search panel, bottom navigation, and right-side controls.
                    val local = PointF((view.width * .32f).toInt().toFloat(), (view.height * .58f).toInt().toFloat())
                    check(visible.contains((screen[0] + local.x).toInt(), (screen[1] + local.y).toInt()))
                    Triple(screen[0] + local.x, screen[1] + local.y, map.projection.fromScreenLocation(local))
                }
                longPressNative(touch.first, touch.second)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("osm-map-name").fetchSemanticsNodes().size == 1 }
                val created = repository.snapshot().mapEdits.filterNot { it.id in beforeIds }.single()
                ownedDraft = created
                compose.onNodeWithTag("osm-map-name").performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                compose.onNodeWithTag("osm-map-type").performScrollTo().assertTextContains("Tür seç")
                compose.onNodeWithText("Tür (zorunlu)").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("osm-map-phone").performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                compose.onNodeWithText("Cadde / sokak (varsa)").assertDoesNotExist()
                compose.onNodeWithText("Kapı numarası (varsa)").assertDoesNotExist()
                compose.waitForIdle()
                val catalogueLoading = "Tür kataloğu hazırlanıyor…"
                val catalogueError = "Tür kataloğu yüklenemedi."
                fun typeEnabled(): Boolean = compose.onAllNodesWithTag("osm-map-type").fetchSemanticsNodes()
                    .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
                fun catalogErrorCount(): Int = compose.onAllNodesWithText(catalogueError).fetchSemanticsNodes().size
                val initialTypeEnabled = typeEnabled()
                val initialLoadingCount = compose.onAllNodesWithText(catalogueLoading).fetchSemanticsNodes().size
                val initialErrorCount = catalogErrorCount()
                val readinessStarted = SystemClock.elapsedRealtime()
                fun reportReadiness(stage: String, waitedMs: Long, failure: Throwable? = null) {
                    val args = InstrumentationRegistry.getArguments()
                    val report = JSONObject().put("stage", stage).put("initialTypeEnabled", initialTypeEnabled)
                        .put("initialLoadingCount", initialLoadingCount)
                        .put("initialErrorCount", initialErrorCount)
                        .put("currentLoadingCount", compose.onAllNodesWithText(catalogueLoading).fetchSemanticsNodes().size)
                        .put("currentErrorCount", catalogErrorCount())
                        .put("currentTypeEnabled", typeEnabled()).put("elapsedWaitMs", waitedMs)
                        .put("waitLimitMs", 15_000).put("presetId", "amenity/pharmacy").put("geometry", "point")
                        .put("runUUID", args.getString("osmLightEditorAdmissionRunUUID") ?: args.getString("osmLightEditorCaptureRunUUID"))
                        .put("source", args.getString("osmLightEditorAdmissionSource") ?: args.getString("osmLightEditorCaptureSource"))
                        .put("failureClass", failure?.javaClass?.name)
                    val encoded = report.toString()
                    check(encoded.toByteArray(Charsets.UTF_8).size <= 4096)
                    instrumentation.addResults(Bundle().apply { putString("osmLightEditorCatalogReadiness_$stage", encoded) })
                    // Nonterminal progress only: never emit a synthetic test success/status0.
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("class", this@MainMapOsmEditorEntryTest.javaClass.name)
                        putString("test", "nativeMainMapLongPressOpensBlankRequiredTypeFormAndReturnsWithoutChangingTheSession")
                        putString("osmLightEditorCatalogReadiness", encoded)
                    })
                }
                reportReadiness("INITIAL_OBSERVATION", 0)
                try {
                    compose.waitUntil(15_000) { typeEnabled() || catalogErrorCount() > 0 }
                } catch (failure: Throwable) {
                    runCatching { reportReadiness("NOT_READY", SystemClock.elapsedRealtime() - readinessStarted, failure) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
                val waitedMs = SystemClock.elapsedRealtime() - readinessStarted
                if (catalogErrorCount() > 0) {
                    val failure = AssertionError("Actual catalogue load error is displayed; no automatic retry attempted")
                    reportReadiness("LOAD_ERROR", waitedMs, failure)
                    throw failure
                }
                reportReadiness("READY", waitedMs)
                compose.onAllNodesWithText(catalogueLoading).assertCountEquals(0)
                compose.onAllNodesWithText(catalogueError).assertCountEquals(0)
                // Resolve only AFTER the real UI is ready; do not warm/preload its asynchronous asset load.
                val pinnedCatalog = OsmPresetCatalog.load(app.assets.open(OsmPresetCatalog.BUNDLED_ASSET))
                val pharmacy = requireNotNull(pinnedCatalog.resolve("amenity/pharmacy"))
                assertTrue(OsmPresetGeometry.POINT in pharmacy.geometry && pharmacy.selectable && !pharmacy.requiresLocationValidation)
                compose.onNodeWithTag("osm-map-type").performScrollTo().assertIsEnabled().performClick()
                compose.onNodeWithText("Tür ara").performTextInput("amenity=pharmacy")
                compose.onNodeWithText(pharmacy.name).assertIsDisplayed().assertIsNotSelected()
                val cancelPreset = hasText("Kapat") and hasClickAction() and hasAnyAncestor(
                    isDialog() and hasAnyDescendant(hasText("Tür ara")),
                )
                compose.onNode(cancelPreset).assertIsDisplayed().performClick()
                compose.onNodeWithText("Tür ara").assertDoesNotExist()
                compose.onNodeWithTag("osm-map-type").assertIsEnabled().assertTextContains("Tür seç")
                compose.onNodeWithText("Bir tür seç.").assertIsDisplayed()
                assertEquals(created, repository.getMapEdit(created.id))
                compose.onAllNodesWithText(catalogueLoading).assertCountEquals(0)
                compose.onAllNodesWithText(catalogueError).assertCountEquals(0)
                reportReadiness("SEARCH_CANCELLED_UNSELECTED", waitedMs)
                compose.waitForIdle()
                OsmLightEditorNativeCapture.capture("01-main-map-empty-type")

                assertEquals(2, created.editorVersion)
                assertNull(created.presetId)
                assertNull(created.presetSnapshot)
                assertNull(created.targetType)
                assertEquals("", created.name)
                assertEquals("", created.phone)
                assertEquals(MapEditFieldAction.UNTOUCHED, created.nameAction)
                assertEquals(MapEditFieldAction.UNTOUCHED, created.phoneAction)
                assertEquals(MapEditStatus.DRAFT, created.status)
                assertEquals(MapEditStage.NONE, created.stage)
                assertEquals(touch.third.latitude, created.latitude, .00001)
                assertEquals(touch.third.longitude, created.longitude, .00001)

                // With observation confirmed, type alone still blocks preview. Restore the original
                // unchecked value before returning so no unsaved-input dialog is bypassed.
                compose.onNodeWithTag("osm-map-survey").performScrollTo().assertIsOff().performClick().assertIsOn()
                compose.onNodeWithText("Önizle").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("osm-map-survey").performScrollTo().performClick().assertIsOff()
                Espresso.pressBack()
                compose.onNodeWithTag("osm-map-name").assertDoesNotExist()
                compose.onNodeWithText("OSM mekânlarım").assertIsDisplayed()
                Espresso.pressBack()
                compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
                assertEquals(created, repository.getMapEdit(created.id))
                val after = repository.snapshot()
                assertEquals(before, after.copy(mapEdits = after.mapEdits.filterNot { it.id == created.id }))
                assertEquals(baselineSession, session(app))
            }
        } finally {
            // Compare-and-delete only this test's exact newly created local draft, never a reset.
            ownedDraft?.let { repository.deleteMapEditIfUnchanged(it) }
        }
    }

    private fun requireIsolatedOfflineQa(app: Context) {
        assertEquals("org.iz.navigation.qalighteditor20261113", app.packageName)
        assertEquals(app.packageName, InstrumentationRegistry.getArguments().getString("osmLightEditorZipQaPackage"))
        OsmLightEditorQaTargetAdmission.requireAdmittedTarget()
        assertEquals(Configuration.ORIENTATION_PORTRAIT, app.resources.configuration.orientation)
        assertEquals("Normal font1 must already be configured", 1f, app.resources.configuration.fontScale, .01f)
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("QA merged manifest must remove INTERNET", Manifest.permission.INTERNET in permissions)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            assertEquals("No real device location is needed", PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(app, permission))
        }
    }

    private fun session(app: IzApplication): List<Any?> = app.navigation.state.value.let {
        listOf(it.sessionId, it.recording, it.guidance, it.journey?.id, it.route?.id)
    }

    private fun readyMap(host: ActivityScenario<MainActivity>): Pair<MapView, MapLibreMap> {
        var view: MapView? = null
        compose.waitUntil(10_000) {
            host.onActivity { view = it.window.decorView.findMap() }
            view?.let { candidate -> onMain { candidate.isShown && candidate.width > 0 && candidate.height > 0 } } == true
        }
        val actual = requireNotNull(view)
        val loaded = AtomicReference<MapLibreMap?>()
        onMain { actual.getMapAsync { loaded.set(it) } }
        compose.waitUntil(10_000) { onMain { loaded.get()?.style?.isFullyLoaded == true } }
        return actual to requireNotNull(loaded.get())
    }

    private fun longPressNative(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Native screen touch injection must succeed", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        var pressed = false
        var released = false
        try {
            send(MotionEvent.ACTION_DOWN); pressed = true
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 200)
            send(MotionEvent.ACTION_UP); released = true
        } finally { if (pressed && !released) send(MotionEvent.ACTION_CANCEL) }
    }

    private suspend fun withOfflineMainActivity(app: Context, block: suspend (ActivityScenario<MainActivity>) -> Unit) {
        lateinit var previous: ModuleProvider
        lateinit var offline: NoNetworkMaps
        onMain {
            MapLibre.getInstance(app)
            previous = MapLibre.getModuleProvider()
            offline = NoNetworkMaps(previous)
            MapLibre.setModuleProvider(offline)
            check(MapLibre.getModuleProvider() === offline)
        }
        try {
            val intent = Intent().setComponent(ComponentName(app.packageName, MainActivity::class.java.name))
            ActivityScenario.launch<MainActivity>(intent).use { host -> block(host) }
        } finally {
            try { offline.drain(); offline.assertHealthy() }
            finally { onMain { MapLibre.setModuleProvider(previous); check(MapLibre.getModuleProvider() === previous) } }
        }
    }

    private fun View.findMap(): MapView? {
        if (this is MapView) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).findMap()?.let { return it }
        return null
    }

    private fun <T> onMain(action: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    /** Same bounded, failure-only transport pattern as PlannedBreakScenarioTest; never delegates HTTP. */
    internal class NoNetworkMaps(private val previous: ModuleProvider) : ModuleProvider {
        private val closing = AtomicBoolean(false)
        private val gate = Any()
        private val callbacks = AtomicInteger()
        private val fatal = AtomicReference<Throwable?>()
        private val pending = ConcurrentHashMap.newKeySet<FutureTask<Unit>>()
        private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
            { runnable -> Thread(runnable, "iz-osm-entry-test-map").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        override fun createLibraryLoaderProvider() = previous.createLibraryLoaderProvider()
        override fun createHttpRequest(): HttpRequest = object : HttpRequest {
            private val cancelled = AtomicBoolean(false)
            private val future = AtomicReference<FutureTask<Unit>?>()
            override fun executeRequest(responder: HttpResponder, ignored: Long, url: String, range: String?, etag: String?, modified: String?, offline: Boolean) {
                val task = object : FutureTask<Unit>({
                    if (!cancelled.get() && !closing.get()) {
                        callbacks.incrementAndGet()
                        try { if (!cancelled.get() && !closing.get()) responder.handleFailure(HttpRequest.PERMANENT_ERROR, "QA map network disabled") }
                        catch (failure: Throwable) { fatal.compareAndSet(null, failure) }
                        finally { callbacks.decrementAndGet() }
                    }
                    Unit
                }) { override fun done() { pending.remove(this) } }
                check(future.compareAndSet(null, task))
                synchronized(gate) {
                    if (cancelled.get() || closing.get()) { task.cancel(false); return }
                    pending += task
                    try { worker.execute(task) } catch (failure: java.util.concurrent.RejectedExecutionException) {
                        task.cancel(false); fatal.compareAndSet(null, failure)
                    }
                }
            }
            override fun cancelRequest() { synchronized(gate) { cancelled.set(true); future.get()?.cancel(false) } }
        }
        fun assertHealthy() { fatal.get()?.let { throw AssertionError("QA map transport failed", it) } }
        fun drain() {
            check(Looper.myLooper() != Looper.getMainLooper())
            synchronized(gate) {
                closing.set(true)
                pending.toList().forEach { it.cancel(false) }
                worker.shutdown()
            }
            check(worker.awaitTermination(5, TimeUnit.SECONDS) && callbacks.get() == 0 && pending.isEmpty()) {
                "Map transport must drain before restoring the original provider"
            }
        }
    }
}

/** Additive, bounded synthetic cache admission. Existing cache files are never overwritten/deleted. */
internal object MapSelectionCacheFixture {
    fun store(app: Context, key: String, body: String) {
        val root = app.cacheDir.canonicalFile
        val directory = File(root, "osm_place_queries")
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == root)
        check(directory.isDirectory || directory.mkdir())
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val target = File(directory, name)
        check(body.toByteArray().size <= 512_000 && !target.exists())
        Files.newOutputStream(target.toPath(), CREATE_NEW, WRITE).use { it.write(body.toByteArray()) }
    }
}
