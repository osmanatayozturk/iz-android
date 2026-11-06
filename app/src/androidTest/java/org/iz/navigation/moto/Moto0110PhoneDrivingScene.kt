package org.iz.navigation.moto

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.iz.navigation.IzApplication
import org.iz.navigation.data.Transport
import org.iz.navigation.data.DestinationShortcut
import org.iz.navigation.data.DestinationShortcutKind
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Place
import org.iz.navigation.health.HealthPrivacyActivity
import org.iz.navigation.integration.OsmServiceSettings
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.NavigationHomeContent
import org.iz.navigation.ui.PhoneDirectionsContent
import org.iz.navigation.ui.PhoneDirectionsState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.LibraryLoaderProvider
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProvider
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpResponder
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * IZ-UX-0111 synthetic native PHONE component scene, normal font only. Not MainActivity, a real
 * recorder/GNSS session, a ride, full-app navigation, real Auto/DHU or accuracy/energy evidence.
 * Fresh test-owned existing QA HealthPrivacyActivity rehosts the scene; no health reads.
 * Actual NavigationHomeContent + PhoneDirectionsContent remain mounted around one actual MapView.
 * Public MapLibre13.6.1 contracts: android-v13.6.1/.../ModuleProvider.java,
 * http/{HttpRequest,HttpResponder,NativeHttpRequest}.java. No native-pointer/JNI access.
 * Module transport isolation applies only to this SDK path, not all application networking.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Moto0110PhoneDrivingScene {
    @get:Rule val compose = createEmptyComposeRule()
    private val showScene = mutableStateOf(true)
    private val planning = mutableStateOf(false)
    private val shortcuts = mutableStateOf(emptyList<DestinationShortcut>())
    private val layouts = JSONArray()
    private val containerHeight = mutableStateOf<Dp?>(null)
    private val a = WeatherCoordinate(41.0, 29.0)
    private val coveredFix = WeatherCoordinate(41.0005, 29.0005)
    private val b = WeatherCoordinate(41.0008, 29.0009)
    private val nav = mutableStateOf(NavigationState())
    private val disposed = AtomicBoolean(false)
    private val mounts = AtomicInteger(0)
    private val ownedHost = AtomicReference<HealthPrivacyActivity?>()
    private val mapRef = AtomicReference<MapLibreMap?>()
    private val requestedView = AtomicReference<MapView?>()
    private val poses = JSONArray()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var density = 0f
    @Volatile private var fontScale = 0f
    private var lastFixAt = 0L
    private var sceneDeadline = 0L
    private var captures = 0
    private var adaptiveExpected = true
    private var transport: MemoryTiles? = null

    /** Run only with a fresh source/package/target lease and izExpectedOrientation=portrait|landscape.
     * Observe the allocated orientation; never rotate the device or override font/GPS/system settings.
     * This exercises settled measured native layout, not an assertion about every first render frame.
     */
    @Test(timeout = 90_000) fun nativeUx0111HeaderShortcutsAndManualHoldAcrossActualPlanner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Dedicated QA only", "org.iz.navigation.batterycheck", context.packageName)
        val expectedOrientation = InstrumentationRegistry.getArguments().getString("izExpectedOrientation")
        check(expectedOrientation in setOf("portrait", "landscape")) { "Explicit allocated portrait/landscape argument required" }
        assertFalse("No actual tracker", TrackingService.isRunning)
        val actual = (context.applicationContext as IzApplication).navigation.state.value
        assertFalse("No actual navigation/location session", actual.guidance || actual.recording || actual.locationActive || actual.loading)
        val directory = File(checkNotNull(context.getExternalFilesDir(null)),
            "qa/ux-0111-phone-scene/${SystemClock.uptimeMillis()}").apply { check(mkdirs()) }
        val evidence = JSONObject().put("status", "STARTED").put("evidenceClass", "SYNTHETIC_NATIVE_PHONE_COMPONENT")
            .put("fullAppOrRealGnssOrAutoHost", false).put("enlargedFont", false)
            .put("expectedOrientation", expectedOrientation)
            .put("sourceCommitArgument", InstrumentationRegistry.getArguments().getString("izCandidateCommit"))
            .put("hostClass", HealthPrivacyActivity::class.java.name).put("hostPackage", context.packageName)
            .put("hostOwnership", "FRESH_TEST_OWNED_EXISTING_QA_PRIVACY_ACTIVITY_REHOST")
        var savedProvider: ModuleProvider? = null
        var shell: HealthPrivacyActivity? = null
        var ownedScenario: ActivityScenario<HealthPrivacyActivity>? = null
        var hostScenarioClosed = false
        var primaryFailure: Throwable? = null
        var providerRestored = false
        var nativeViewsRemoved = false
        sceneDeadline = SystemClock.elapsedRealtime() + 65_000
        try {
            onMain {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val existing = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                    .flatMap { monitor.getActivitiesInStage(it) }.distinct()
                check(existing.none { it.packageName == context.packageName && !it.isDestroyed }) {
                    "Do not replace or clear another pre-existing QA Activity"
                }
                check(existing.all { it.window?.decorView?.maps().orEmpty().isEmpty() }) {
                    "Existing native map: do not replace another component's global transport"
                }
            }
            val launched = ActivityScenario.launch<HealthPrivacyActivity>(Intent().setComponent(
                ComponentName(context.packageName, HealthPrivacyActivity::class.java.name)))
            ownedScenario = launched
            launched.onActivity { activity ->
                check(ownedHost.compareAndSet(null, activity))
                shell = activity
            }
            val activity = checkNotNull(shell)
            onMain {
                check(activity.javaClass == HealthPrivacyActivity::class.java && ownedHost.get() === activity)
                check(activity.componentName == ComponentName(context.packageName, HealthPrivacyActivity::class.java.name))
                check(activity.packageName == context.packageName && activity.application === context.applicationContext)
                check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                check(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() === activity)
                check(activity.window.decorView.maps().isEmpty())
            }
            savedProvider = onMain { MapLibre.getModuleProvider() }
            val tiles = MemoryTiles(checkNotNull(savedProvider), OsmServiceSettings(context).read().tileUrl)
            transport = tiles
            onMain {
                check(MapLibre.getModuleProvider() === savedProvider)
                MapLibre.setModuleProvider(tiles)
                check(MapLibre.getModuleProvider() === tiles)
            }
            val start = RouteStop("Synthetic origin", a)
            val end = RouteStop("Synthetic destination", WeatherCoordinate(41.004, 29.004))
            val route = PlannedRoute("qa-moto-native-phone-scene", listOf(start, end),
                listOf(RouteVertex(start.coordinate, 0.0), RouteVertex(end.coordinate, 120.0)),
                600.0, 120.0, System.currentTimeMillis(), transport = Transport.MOTORCYCLE)
            val ui = PhoneDirectionsState(transport = Transport.MOTORCYCLE, origin = start, destination = end,
                preview = route, recordJourney = false)
            nav.value = NavigationState(route = route, guidance = true, recording = false, journey = null,
                simulation = false, sessionId = "qa-moto-native-phone-scene", sessionTransport = Transport.MOTORCYCLE,
                gpsStale = false, locationActive = true, fix = fix(a),
                roadSpeed = RoadSpeedState(ownKmh = 48.0, visible = true))
            lastFixAt = SystemClock.elapsedRealtime()
            onMain { activity.setContent {
                val observedDensity = LocalDensity.current
                SideEffect { density = observedDensity.density; fontScale = observedDensity.fontScale }
                IzTheme {
                    if (showScene.value) {
                        DisposableEffect(Unit) {
                            mounts.incrementAndGet()
                            onDispose { disposed.set(true) }
                        }
                        Box(Modifier.fillMaxSize()) {
                            val size = containerHeight.value
                            Box(Modifier.fillMaxWidth().then(if (size == null) Modifier.fillMaxHeight() else Modifier.height(size))
                                .testTag("qa-driving-container")) {
                                val open = { planning.value = true }
                                NavigationHomeContent(ui, nav.value, emptyList(), emptyList(), planning.value,
                                    "Synthetic QA", open, {}, {}, {}, {}, {}, open, {}, {},
                                    shortcuts = shortcuts.value, onShortcut = {}, planner = {
                                        PhoneDirectionsContent(ui = ui, nav = nav.value, points = emptyList(), commandBusy = false,
                                            onClose = { planning.value = false }, chooseOrigin = {}, chooseDestination = {},
                                            currentOrigin = {}, transport = {}, preview = {}, start = {}, freeDrive = {},
                                            finish = {}, mute = {}, stopGuidance = {}, showLive = {}, trafficSettings = {},
                                            lastDestination = null, permissions = {}, showMap = false)
                                    })
                            }
                        }
                    }
                }
            } }
            compose.waitForIdle()
            assertEquals("Observe normal font; never override it", 1f, fontScale, .01f)
            // Actual-header RED: old guidance UI contains this Surface, not just a helper result.
            compose.onNodeWithTag("open-navigation").assertDoesNotExist()
            val home = bounds("navigation-home")
            val observedOrientation = if (home.width > home.height) "landscape" else "portrait"
            assertEquals("Actual allocated viewport orientation", expectedOrientation, observedOrientation)
            evidence.put("observedOrientation", observedOrientation)
            adaptiveExpected = hasSafeAdaptiveArea(exposedGeometry())
            val readyStarted = SystemClock.elapsedRealtime()
            await("one resumed, nonzero, style-ready native map and adaptive camera", 20_000) {
                onMain {
                    val views = activity.window.decorView.maps()
                    check(views.size <= 1) { "More than one native map" }
                    views.singleOrNull()?.let { view ->
                        if (requestedView.get() !== view) {
                            requestedView.set(view)
                            view.getMapAsync { mapRef.set(it) }
                        }
                    }
                    val view = requestedView.get()
                    val map = mapRef.get()
                    view != null && view.width > 0 && view.height > 0 &&
                        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        map != null && map.style?.isFullyLoaded == true && adaptive(pose(map), a)
                }
            }
            val view = checkNotNull(requestedView.get())
            val map = checkNotNull(mapRef.get())
            evidence.put("readyMillis", SystemClock.elapsedRealtime() - readyStarted)
                .put("mapViewIdentity", System.identityHashCode(view)).put("mapIdentity", System.identityHashCode(map))
                .put("density", density.toDouble()).put("fontScale", fontScale.toDouble())
                .put("window", onMain {
                    val decor = activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
                    JSONObject().put("widthPx", decor.width).put("heightPx", decor.height)
                        .put("systemInsetsPx", insets?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
                        .put("activityClass", activity.javaClass.name).put("activityResumed", true)
                })
            awaitSource(map, a)
            assertGuidanceLayout(map, "guidance-only-empty")
            val initial = snapshot(map, "initial-adaptive")
            assertAdaptive(initial, a)
            val exposed = exposedGeometry()
            val projected = onMain { map.projection.toScreenLocation(LatLng(a.latitude, a.longitude)) }
            val expectedY = exposed.top + (if (adaptiveExpected) .65f else .5f) * exposed.height
            assertEquals("Fix anchor uses measured exposed height", expectedY.toDouble(), projected.y.toDouble(), 8.0)
            assertTrue("Fix must be in the exposed horizontal region", projected.x > exposed.left && projected.x < exposed.right)
            val mapBounds = bounds("navigation-home-map")
            val rootPoint = Offset(projected.x + mapBounds.left, projected.y + mapBounds.top)
            for (tag in listOf("live-guidance-card", "home-weather", "home-record", "home-group", "home-menu", "directions-recenter", "map-layers")) {
                assertFalse("Projected fix is covered by $tag", bounds(tag).contains(rootPoint))
            }
            evidence.put("initialExposedPx", exposed.json()).put("initialProjectionPx", JSONArray(listOf(projected.x, projected.y)))
            capture(activity, directory, "01-guidance-header-hidden-empty")

            val populated = listOf(
                DestinationShortcut(DestinationShortcutKind.HOME, Place(id = "qa-home", name = "Synthetic home", latitude = 41.002, longitude = 29.002)),
                DestinationShortcut(DestinationShortcutKind.WORK, Place(id = "qa-work", name = "Synthetic work", latitude = 41.003, longitude = 29.003)),
            )
            compose.runOnIdle { shortcuts.value = populated }
            assertGuidanceLayout(map, "guidance-only-populated")
            compose.runOnIdle { nav.value = nav.value.copy(recording = true, journey = Journey(id = "qa-ux0111-local-journey")) }
            assertGuidanceLayout(map, "combined-populated")
            // Only synthetic component state changes; never start or fake a tracker/GPS/service.
            for ((stage, state) in listOf(
                "rerouting" to nav.value.copy(loading = true, message = "Rota dışındasın. Yeni rota hesaplanıyor."),
                "stale-gps" to nav.value.copy(gpsStale = true, locationActive = false, message = "Sentetik GPS uyarısı"),
            )) {
                compose.runOnIdle { nav.value = state }
                assertGuidanceLayout(map, stage, checkNativePadding = false)
                sameMap(activity, view, map)
            }
            compose.runOnIdle { nav.value = nav.value.copy(guidance = false, arrived = true, loading = false,
                message = "Hedefe vardın. Yolculuk kaydı devam ediyor.") }
            compose.onNodeWithTag("open-navigation").assertIsDisplayed()
            compose.onNodeWithTag("destination-shortcut-HOME").assertIsDisplayed()
            compose.onNodeWithTag("destination-shortcut-WORK").assertIsDisplayed()
            compose.onNodeWithTag("home-finish-session").assertIsDisplayed()
            assertTrue("Arrival component transition must keep recording", nav.value.recording)
            layouts.put(JSONObject().put("stage", "arrival-record-only-header-restored").put("recording", true)
                .put("headerPx", bounds("open-navigation").json()))
            sameMap(activity, view, map)
            compose.runOnIdle { nav.value = nav.value.copy(guidance = true, arrived = false, gpsStale = false,
                locationActive = true, loading = false, message = null, fix = fix(a)); shortcuts.value = emptyList() }
            adaptiveExpected = hasSafeAdaptiveArea(exposedGeometry())
            await("current-layout follow after guidance returns", 5_000) { adaptive(onMain { pose(map) }, a) }
            assertGuidanceLayout(map, "guidance-return-empty")

            val beforeOcclusion = snapshot(map, "current-layout-before-occlusion")
            // The actual native viewport shrinks inside the small container while follow is active.
            compose.runOnIdle { containerHeight.value = 160.dp }
            compose.waitForIdle()
            val coveredMap = bounds("navigation-home-map")
            await("reduced nonzero native viewport", 3_000) { onMain {
                view.height > 0 && view.height < 160f * density && abs(view.height - coveredMap.height) <= 2f
            } }
            val topPanelsBottom = bounds("compact-guidance-header").bottom
            assertTrue("External header ends before the native map", topPanelsBottom <= coveredMap.top + 1f)
            assertTrue("Map-local insets leave no usable vertical gap", coveredMap.height <= (24f + 112f) * density)
            evidence.put("occlusionMeasuredPx", JSONObject().put("map", coveredMap.json())
                .put("topPanelsBottom", topPanelsBottom.toDouble()).put("bottomActionsTop", bounds("home-record").top.toDouble()))
            publishFix(coveredFix)
            awaitSource(map, coveredFix)
            await("covered native camera is actually flat", 3_000) { flat(onMain { pose(map) }) }
            assertHeld(map, beforeOcclusion, "covered-follow-new-fix", 1_200, preservePadding = true, requireFlat = true)
            compose.runOnIdle { containerHeight.value = null }
            compose.waitForIdle()
            adaptiveExpected = hasSafeAdaptiveArea(exposedGeometry())
            await("reopened follow resumes at the current synthetic fix", 5_000) { adaptive(onMain { pose(map) }, coveredFix) }
            sameMap(activity, view, map)

            val beforePan = snapshot(map, "before-single-native-pan")
            if (adaptiveExpected) {
                val interruptedFix = WeatherCoordinate(coveredFix.latitude + .0001, coveredFix.longitude + .0001)
                lastFixAt = SystemClock.elapsedRealtime()
                publishFix(interruptedFix)
                await("adaptive ease is in flight before the native gesture", 500) {
                    val current = onMain { pose(map) }
                    !sameTarget(current, beforePan) && !atCoordinate(current, interruptedFix)
                }
            }
            swipeNative(view, exposedGeometry())
            await("native gesture moves the camera", 4_000) { !sameTarget(onMain { pose(map) }, beforePan) }
            settleCamera(map)
            val manual = snapshot(map, "after-single-native-pan")
            compose.runOnIdle {
                shortcuts.value = populated
                nav.value = nav.value.copy(roadSpeed = nav.value.roadSpeed.copy(ownKmh = 101.0))
            }
            assertGuidanceLayout(map, "manual-held-populated", checkNativePadding = false)
            assertHeld(map, manual, "shortcut-and-speed-updates-must-not-release-manual-hold", 400)
            sameMap(activity, view, map)
            // Actual Home Actions opens the actual planner; no stand-in planner composable.
            compose.onNodeWithTag("home-finish-session").assertIsDisplayed().performClick()
            compose.onNodeWithTag("navigation-screen").assertIsDisplayed()
            assertTrue(planning.value)
            compose.onNodeWithTag("open-navigation").assertDoesNotExist()
            compose.onNodeWithTag("destination-shortcut-HOME").assertDoesNotExist()
            compose.onNodeWithTag("destination-shortcut-WORK").assertDoesNotExist()
            sameMap(activity, view, map)
            compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("close-directions-planner"))
            compose.onNodeWithTag("close-directions-planner").performClick()
            compose.onNodeWithTag("live-guidance-card").assertIsDisplayed()
            sameMap(activity, view, map)
            settleCamera(map)
            assertGuidanceLayout(map, "planner-return-populated", checkNativePadding = false)
            val postReturn = snapshot(map, "post-actual-planner-return-before-B")
            val since = publishFix(b)
            evidence.put("freshBElapsedSincePreviousFixMillis", since)
            assertTrue("Use real elapsed time, not a clock/GPS override", since >= 1_100)
            awaitSource(map, b)
            assertHeld(map, postReturn, "manual-held-native-source-B", 1_200)
            capture(activity, directory, "02-header-hidden-populated-manual-held-B")
            adaptiveExpected = hasSafeAdaptiveArea(exposedGeometry())
            compose.onNodeWithTag("directions-recenter").assertIsDisplayed().performClick()
            await("only actual Konumum releases hold and follows B", 5_000) { adaptive(onMain { pose(map) }, b) }
            awaitSource(map, b)
            snapshot(map, "actual-Konumum-follows-B")
            compose.runOnIdle { nav.value = nav.value.copy(fix = fix(b).copy(speedMps = 0f)) }
            assertGuidanceLayout(map, "stationary-header-still-hidden", checkNativePadding = false)
            verifySpeedProfile(activity, view, map)
            sameMap(activity, view, map)
            assertEquals(1, mounts.get())
            assertFalse(disposed.get())
            tiles.assertHealthy()
            assertTrue("Exact synthetic provider must remain installed during the scene", onMain { MapLibre.getModuleProvider() === tiles })
            evidence.put("providerIdentityDuringScene", System.identityHashCode(tiles))
                .put("originalProviderIdentity", System.identityHashCode(savedProvider))
                .put("status", "SCENE_ASSERTIONS_PASS_CLEANUP_PENDING")
        } catch (failure: Throwable) {
            primaryFailure = failure
            evidence.put("status", "SCENE_NONPASS").put("failureClass", failure.javaClass.name)
                .put("failureMessage", failure.message?.take(800))
            throw failure
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            val wasInterrupted = Thread.interrupted()
            var cleanupFailure: Throwable? = null
            try {
                transport?.closeAdmission()
                fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { cleanupErrors += error } }
                cleanup { onMain { showScene.value = false }; compose.waitForIdle() }
                cleanup {
                    val deadline = SystemClock.elapsedRealtime() + 3_000
                    var empty = false
                    while (!empty && SystemClock.elapsedRealtime() < deadline) {
                        empty = onMain { shell?.window?.decorView?.maps().orEmpty().isEmpty() }
                        if (!empty) SystemClock.sleep(25)
                    }
                    nativeViewsRemoved = empty
                    check(empty && (mounts.get() == 0 || disposed.get())) { "Actual map composition disposal unproven; stop before any later map" }
                }
                // Never block UI/native cancellation on a worker join. Restore identity even on failures.
                try { cleanup { transport?.cancelAndDrain() } }
                finally {
                    cleanup {
                        savedProvider?.let { previous -> onMain {
                            MapLibre.setModuleProvider(previous)
                            check(MapLibre.getModuleProvider() === previous) { "Original provider identity was not restored" }
                            providerRestored = true
                        } }
                    }
                }
                cleanup { transport?.assertHealthy() }
                evidence.put("captureRequests", captures).put("poses", poses).put("layouts", layouts).put("mapCompositionMounts", mounts.get())
                    .put("mapCompositionDisposed", disposed.get()).put("nativeViewsRemoved", nativeViewsRemoved)
                    .put("providerIdentityRestored", providerRestored).put("transport", transport?.stats())
                    .put("cleanupFailureCount", cleanupErrors.size).put("interruptedAtCleanup", wasInterrupted)
                    .put("wholeApplicationNetworkSilenceProven", false)
                    .put("scope", "Synthetic native Phone component only; no real app/GNSS/ride/Auto/physical lean/energy acceptance")
                if (cleanupErrors.isNotEmpty()) evidence.put("status", "CLEANUP_NONPASS_STOP_NO_FURTHER_MAPS")
                else if (primaryFailure == null) evidence.put("status", "SCENE_AND_TRANSPORT_CLEANUP_PASS")
                if (cleanupErrors.isNotEmpty()) {
                    val error = AssertionError("Scene cleanup failed; stop, do not run further maps", cleanupErrors.first())
                    cleanupErrors.drop(1).forEach(error::addSuppressed)
                    if (primaryFailure != null) primaryFailure.addSuppressed(error) else throw error
                }
            } catch (error: Throwable) {
                cleanupFailure = error
                throw error
            } finally {
                val closeErrors = mutableListOf<Throwable>()
                try {
                    ownedScenario?.let { scenario ->
                        check(ownedHost.get() === shell)
                        scenario.close()
                        check(scenario.state == Lifecycle.State.DESTROYED)
                        onMain { check(checkNotNull(shell).isDestroyed) }
                        hostScenarioClosed = true
                        ownedHost.set(null)
                    }
                } catch (error: Throwable) { closeErrors += error }
                evidence.put("hostScenarioAcquired", ownedScenario != null)
                    .put("hostScenarioClosed", if (ownedScenario == null) JSONObject.NULL else hostScenarioClosed)
                    .put("hostClosureFailureCount", closeErrors.size)
                    .put("privacyContentRestoration", "Only fresh owned instance replaced; closing disposes it. Next ordinary onCreate restores unchanged static privacy UI.")
                if (closeErrors.isNotEmpty()) evidence.put("status", "HOST_CLOSURE_NONPASS_STOP_NO_FURTHER_MAPS")
                try { File(directory, "scene.json").writeText(evidence.toString(2)) }
                catch (error: Throwable) { closeErrors += error }
                if (wasInterrupted) Thread.currentThread().interrupt()
                if (closeErrors.isNotEmpty()) {
                    val error = AssertionError("Owned host closure/evidence failed; stop, no further maps", closeErrors.first())
                    closeErrors.drop(1).forEach(error::addSuppressed)
                    val previousFailure = cleanupFailure ?: primaryFailure
                    if (previousFailure != null) previousFailure.addSuppressed(error) else throw error
                }
            }
        }
    }

    private fun fix(coordinate: WeatherCoordinate, speedKmh: Int = 30) =
        NavigationFix(coordinate, System.currentTimeMillis(), 5f, (speedKmh / 3.6).toFloat(), 90f)

    private fun publishFix(coordinate: WeatherCoordinate, speedKmh: Int? = null): Long {
        val remaining = lastFixAt + 1_100 - SystemClock.elapsedRealtime()
        if (remaining > 0) SystemClock.sleep(remaining)
        val elapsed = SystemClock.elapsedRealtime() - lastFixAt
        compose.runOnIdle {
            val speed = if (speedKmh == null) nav.value.roadSpeed else nav.value.roadSpeed.copy(ownKmh = speedKmh.toDouble())
            nav.value = nav.value.copy(fix = fix(coordinate, speedKmh ?: 30), gpsStale = false, roadSpeed = speed)
        }
        lastFixAt = SystemClock.elapsedRealtime()
        compose.waitForIdle()
        return elapsed
    }

    private fun verifySpeedProfile(activity: Activity, view: MapView, map: MapLibreMap) {
        val anchors = mutableListOf<Double>()
        val zooms = mutableListOf<Double>()
        lastFixAt = SystemClock.elapsedRealtime()
        listOf(0, 30, 60, 90, 110, 60, 0).forEachIndexed { index, speed ->
            val offset = (index + 1) * .00001
            val coordinate = WeatherCoordinate(b.latitude + offset, b.longitude + offset)
            assertTrue(publishFix(coordinate, speed) >= 1_100)
            await("speed profile target settles at $speed km/sa", 4_000) {
                SystemClock.elapsedRealtime() - lastFixAt >= 500 && atCoordinate(onMain { pose(map) }, coordinate)
            }
            val exposed = exposedGeometry()
            val target = snapshot(map, "speed-profile-$index-$speed")
            sameMap(activity, view, map)
            val padding = target.padding
            assertEquals(4, padding.size)
            val anchor = .5 + (padding[1] - exposed.top) / (2 * exposed.height)
            anchors += anchor
            zooms += target.zoom
            assertTrue("Speed zoom stays within the filtered bounds", target.zoom in 15.49..17.51)
            if (adaptiveExpected) {
                assertTrue("Adaptive anchor stays between 65 and 70 percent", anchor in 0.64..0.71)
                assertEquals(45.0, target.pitch, 1.0)
            } else {
                assertEquals(.5, anchor, .015)
                assertTrue("Undersized viewport stays flat", flat(target))
            }
        }
        if (adaptiveExpected) {
            assertTrue("Higher speed pushes the anchor down", anchors[4] > anchors[2] + .003)
            assertTrue("Slowing down returns the anchor upward", anchors[6] < anchors[4] - .003)
        }
        assertTrue("The retained zoom curve eases out and back in", zooms[4] < zooms[2] && zooms[6] > zooms[4])
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun Rect.json() = JSONArray(listOf(left, top, right, bottom))
    /** Assert actual tags/positions and native camera padding, never a mirrored visibility helper. */
    private fun assertGuidanceLayout(map: MapLibreMap, stage: String, checkNativePadding: Boolean = true) {
        compose.waitForIdle()
        assertTrue(nav.value.guidance)
        compose.onNodeWithTag("open-navigation").assertDoesNotExist()
        compose.onNodeWithTag("live-guidance-card").assertIsDisplayed()
        val mapBounds = bounds("navigation-home-map")
        compose.onNodeWithTag("destination-shortcut-HOME").assertDoesNotExist()
        compose.onNodeWithTag("destination-shortcut-WORK").assertDoesNotExist()
        val headerBottom = bounds("compact-guidance-header").bottom
        assertTrue("$stage header must end above the actual map", headerBottom <= mapBounds.top + 1f)
        for (tag in listOf("directions-recenter", "map-layers")) {
            assertTrue("$stage $tag must stay outside the actual map", bounds(tag).bottom <= mapBounds.top + 1f)
        }
        val actualTop = bounds("directions-recenter").top - mapBounds.top
        if (checkNativePadding) {
            val exposed = exposedGeometry()
            adaptiveExpected = hasSafeAdaptiveArea(exposed)
            val coordinate = checkNotNull(nav.value.fix).coordinate
            val expectedY = exposed.top + (if (adaptiveExpected) .65f else .5f) * exposed.height
            // Native forward anchoring adds padding beyond the Compose inset; check its
            // projected visible result rather than comparing raw base inset with native padding.
            await("$stage native anchor uses map-local insets", 5_000) {
                onMain {
                    val value = pose(map)
                    val point = map.projection.toScreenLocation(LatLng(coordinate.latitude, coordinate.longitude))
                    value.padding.size == 4 && adaptive(value, coordinate) && abs(point.y - expectedY) <= 8f &&
                        abs(value.padding[0] - 28f * density) <= 2.0 && abs(value.padding[2] - 28f * density) <= 2.0 &&
                        abs(value.padding[3] - 112f * density) <= 2.0
                }
            }
        }
        check(layouts.length() < 32)
        layouts.put(JSONObject().put("stage", stage).put("headerExists", false).put("shortcuts", 0)
            .put("controlsTopPx", actualTop.toDouble()).put("headerBottomPx", headerBottom.toDouble())
            .put("mapBoundsPx", mapBounds.json()).put("nativePadding", JSONArray(onMain { pose(map).padding })))
    }
    /** Insets inside the native map. The already-measured external header is excluded. */
    private fun exposedGeometry(): Rect {
        val map = bounds("navigation-home-map")
        return Rect(28f * density, 24f * density,
            map.width - 28f * density, map.height - 112f * density).also {
            assertTrue("Allocated native viewport must have a positive measured exposed extent", it.width > 0 && it.height > 0)
        }
    }
    private fun hasSafeAdaptiveArea(exposed: Rect) = exposed.width >= 160f && exposed.height >= 160f
    private fun sameMap(activity: Activity, view: MapView, map: MapLibreMap) = onMain {
        check(activity.window.decorView.maps().single() === view)
        check(requestedView.get() === view && mapRef.get() === map && mounts.get() == 1 && !disposed.get())
    }
    private data class Pose(val lat: Double, val lon: Double, val zoom: Double, val pitch: Double, val bearing: Double, val padding: List<Double>) {
        fun json() = JSONObject().put("target", JSONArray(listOf(lat, lon))).put("zoom", zoom)
            .put("pitch", pitch).put("bearing", bearing).put("padding", JSONArray(padding))
    }
    private fun pose(map: MapLibreMap): Pose {
        val current = map.cameraPosition
        val target = checkNotNull(current.target)
        return Pose(target.latitude, target.longitude, current.zoom, current.tilt, current.bearing,
            current.padding?.toList().orEmpty())
    }
    private fun snapshot(map: MapLibreMap, stage: String): Pose {
        check(poses.length() < 64)
        val value = onMain { pose(map) }
        poses.put(value.json().put("stage", stage).put("elapsedRealtime", SystemClock.elapsedRealtime()))
        return value
    }
    private fun sameTarget(value: Pose, expected: Pose) = abs(value.lat - expected.lat) <= .000001 && abs(value.lon - expected.lon) <= .000001
    private fun atCoordinate(value: Pose, coordinate: WeatherCoordinate) =
        abs(value.lat - coordinate.latitude) <= .000001 && abs(value.lon - coordinate.longitude) <= .000001
    private fun adaptive(value: Pose, coordinate: WeatherCoordinate) = atCoordinate(value, coordinate) && abs(value.zoom - 17.0) <= .1 &&
        abs(value.pitch - (if (adaptiveExpected) 45.0 else 0.0)) <= 1 &&
        value.bearing.isFinite() && (if (adaptiveExpected) value.bearing in 0.0..360.0 else abs(value.bearing) <= .01)
    private fun assertAdaptive(value: Pose, coordinate: WeatherCoordinate) {
        assertTrue("Actual initial native follow camera: ${value.json()}", adaptive(value, coordinate))
        if (adaptiveExpected) assertTrue("Initial moving course is90degrees", abs(((value.bearing - 90.0 + 540.0) % 360.0) - 180.0) <= 2)
    }
    private fun flat(value: Pose) = abs(value.pitch) <= .01 && abs(((value.bearing + 540.0) % 360.0) - 180.0) <= .01
    private fun assertHeld(map: MapLibreMap, expected: Pose, stage: String, millis: Long,
        preservePadding: Boolean = false, requireFlat: Boolean = false) {
        val until = SystemClock.elapsedRealtime() + millis
        var samples = 0
        do {
            compose.waitForIdle()
            val value = onMain { pose(map) }
            assertTrue("$stage must retain native target", sameTarget(value, expected))
            assertEquals("$stage must retain native zoom", expected.zoom, value.zoom, .01)
            if (requireFlat) assertTrue("$stage must retain actual flat pitch and bearing", flat(value))
            if (preservePadding) {
                assertEquals(4, value.padding.size)
                assertEquals(4, expected.padding.size)
                assertTrue("Covered panels cannot clear padding", value.padding.any { it > 0 })
                value.padding.zip(expected.padding).forEach { (actual, prior) -> assertEquals(prior, actual, 1.0) }
            }
            samples++
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < until)
        val value = onMain { pose(map) }
        check(poses.length() < 64)
        poses.put(value.json().put("stage", stage).put("elapsedRealtime", SystemClock.elapsedRealtime()).put("holdSamples", samples))
    }
    private fun settleCamera(map: MapLibreMap) {
        var prior = onMain { pose(map) }
        var stableAt = SystemClock.elapsedRealtime()
        await("native pan/planner camera settles", 4_000) {
            val now = onMain { pose(map) }
            if (!sameTarget(now, prior) || abs(now.zoom - prior.zoom) > .001) stableAt = SystemClock.elapsedRealtime()
            prior = now
            SystemClock.elapsedRealtime() - stableAt >= 250
        }
    }
    private fun awaitSource(map: MapLibreMap, coordinate: WeatherCoordinate) {
        await("actual native current-fix source publishes synthetic fix", 5_000) {
            onMain {
                map.style?.getSourceAs<GeoJsonSource>("directions-current-fix")?.querySourceFeatures(null).orEmpty().any { feature ->
                    val geometry = JSONObject(feature.toJson()).optJSONObject("geometry")
                    val values = geometry?.optJSONArray("coordinates")
                    geometry?.optString("type") == "Point" && values != null &&
                        abs(values.optDouble(0) - coordinate.longitude) <= .000001 &&
                        abs(values.optDouble(1) - coordinate.latitude) <= .000001
                }
            }
        }
    }
    private fun swipeNative(view: MapView, exposed: Rect) {
        val location = IntArray(2)
        val width = onMain { view.getLocationOnScreen(location); view.width }
        val y = location[1] + exposed.top + exposed.height * .40f
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                location[0] + width * fraction, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Real native map touch injection", InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        var downSent = false
        var released = false
        try {
            touch(MotionEvent.ACTION_DOWN, .55f)
            downSent = true
            for (step in 1..10) { SystemClock.sleep(40); touch(MotionEvent.ACTION_MOVE, .55f + .12f * step / 10) }
            SystemClock.sleep(100)
            touch(MotionEvent.ACTION_UP, .67f)
            released = true
        } finally {
            if (downSent && !released) touch(MotionEvent.ACTION_CANCEL, .67f)
        }
    }
    private fun await(label: String, millis: Long, condition: () -> Boolean) {
        val deadline = minOf(sceneDeadline, SystemClock.elapsedRealtime() + millis)
        while (SystemClock.elapsedRealtime() < deadline) {
            transport?.assertHealthy()
            compose.waitForIdle()
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Bounded scene wait failed: $label")
    }
    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(Callable { action() })
        check(main.post(task))
        return try { task.get(5, TimeUnit.SECONDS) } catch (error: Throwable) { task.cancel(false); throw error }
    }
    private fun View.maps(): List<MapView> {
        val result = mutableListOf<MapView>()
        var visited = 0
        fun visit(view: View, depth: Int) {
            check(++visited <= 2_000 && depth <= 64)
            if (view is MapView) result += view
            else if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(this, 0)
        return result
    }
    /** Native GPU screenshot, guarded owned QA rehost, at most two one-shot captures. */
    private fun capture(activity: Activity, directory: File, name: String) {
        check(captures < 2)
        fun ownedFocusedScene(): IntArray = onMain {
            check(activity.javaClass == HealthPrivacyActivity::class.java && activity.packageName == "org.iz.navigation.batterycheck")
            check(ownedHost.get() === activity)
            check(activity.componentName == ComponentName("org.iz.navigation.batterycheck", HealthPrivacyActivity::class.java.name))
            check(!activity.isFinishing && !activity.isDestroyed)
            check((activity as HealthPrivacyActivity).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            check(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() === activity)
            check(activity.hasWindowFocus() && activity.window.decorView.hasWindowFocus()) { "Owned QA privacy rehost must have foreground window focus" }
            val view = checkNotNull(requestedView.get())
            val map = checkNotNull(mapRef.get())
            sameMap(activity, view, map)
            val decor = activity.window.decorView
            val offset = IntArray(2).also(decor::getLocationOnScreen)
            check(decor.width > 0 && decor.height > 0 && decor.width.toLong() * decor.height <= 8_000_000)
            intArrayOf(offset[0], offset[1], decor.width, decor.height)
        }
        val cropBefore = ownedFocusedScene()
        captures++
        val completed = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        val image = AtomicReference<Bitmap?>()
        val failure = AtomicReference<Throwable?>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Maximum one capture thread at a time; a timeout fails this scene, with no next capture.
        val worker = Thread({
            try {
                val bitmap = automation.takeScreenshot()
                if (abandoned.get()) bitmap?.recycle() else image.set(bitmap)
            } catch (error: Throwable) { failure.set(error) }
            finally {
                if (abandoned.get()) image.getAndSet(null)?.recycle()
                completed.countDown()
            }
        }, "iz-qa-native-screenshot-$captures").apply { isDaemon = true }
        var screen: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            worker.start()
            check(completed.await(5, TimeUnit.SECONDS)) { "Native screenshot timed out; stop, no retry or fallback" }
            failure.get()?.let { throw AssertionError("Native screenshot failed; no fallback", it) }
            val captured = checkNotNull(image.getAndSet(null)) { "Native screenshot returned null; no fallback" }
            screen = captured
            check(captured.width > 0 && captured.height > 0 && captured.width.toLong() * captured.height <= 8_000_000)
            val cropAfter = ownedFocusedScene()
            check(cropAfter.contentEquals(cropBefore)) { "Owned window moved during capture; no replacement image" }
            val (left, top, width, height) = cropBefore
            check(left >= 0 && top >= 0 && left.toLong() + width <= captured.width && top.toLong() + height <= captured.height)
            val pixels = Bitmap.createBitmap(captured, left, top, width, height)
            cropped = pixels
            val file = File(directory, "$name.png")
            check(file.createNewFile())
            file.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(file.length() in 1..(32L * 1024 * 1024))
            File(directory, "$name.capture.json").writeText(JSONObject()
                .put("method", "UiAutomation.takeScreenshot").put("elapsedRealtime", SystemClock.elapsedRealtime())
                .put("displayPixels", JSONArray(listOf(captured.width, captured.height)))
                .put("ownedDecorCropScreenPixels", JSONArray(cropBefore.toList()))
                .put("ownedRehostResumedFocusedBeforeAndAfter", true)
                .put("hostClass", activity.javaClass.name).put("hostComponent", activity.componentName.flattenToString())
                .put("sameMapViewIdentity", System.identityHashCode(requestedView.get()))
                .put("sameMapIdentity", System.identityHashCode(mapRef.get())).toString(2))
        } finally {
            // Late native results are recycled by the worker; never interrupt a native capture.
            abandoned.set(true)
            image.getAndSet(null)?.recycle()
            if (cropped !== screen) cropped?.recycle()
            screen?.recycle()
        }
    }

    /** Public module replacement: delegate ONLY the unchanged library loader, never HTTP/network. */
    private class MemoryTiles(private val previous: ModuleProvider, template: String) : ModuleProvider {
        private val closing = AtomicBoolean(false)
        private val gate = Any()
        private val active = ConcurrentHashMap.newKeySet<Request>()
        private val admitted = AtomicInteger(0)
        private val served = AtomicInteger(0)
        private val cancelled = AtomicInteger(0)
        private val unexpected = AtomicInteger(0)
        private val inflight = AtomicInteger(0)
        private val callbacks = AtomicInteger(0)
        private val saturation = AtomicInteger(0)
        private val deadlines = AtomicInteger(0)
        private val fatal = AtomicReference<String?>()
        private val loaderCalls = AtomicInteger(0)
        private val workers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
            { runnable -> Thread(runnable, "iz-qa-moto-map-http").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        private val tokenNames = mutableListOf<String>()
        private val knownRaster = Regex(buildString {
            append('^')
            var from = 0
            Regex("\\{([zxy])\\}").findAll(template).forEach { token ->
                append(Regex.escape(template.substring(from, token.range.first)))
                append("([0-9]{1,7})"); tokenNames += token.groupValues[1]; from = token.range.last + 1
            }
            append(Regex.escape(template.substring(from))); append('$')
        })
        private val png = syntheticPng()
        init { check(tokenNames.toSet() == setOf("z", "x", "y") && tokenNames.size == 3); check(png.size <= 256 * 1024) }
        override fun createLibraryLoaderProvider(): LibraryLoaderProvider { loaderCalls.incrementAndGet(); return previous.createLibraryLoaderProvider() }
        override fun createHttpRequest(): HttpRequest = Request()
        fun closeAdmission() { synchronized(gate) { closing.set(true) } }
        fun assertHealthy() { fatal.get()?.let { throw AssertionError("Synthetic map transport failure: $it") } }
        fun stats() = JSONObject().put("admit", admitted.get()).put("serve", served.get()).put("cancel", cancelled.get())
            .put("unexpected", unexpected.get()).put("inflight", inflight.get()).put("callbacks", callbacks.get())
            .put("saturation", saturation.get()).put("deadlineFailures", deadlines.get()).put("libraryLoaderDelegations", loaderCalls.get())
            .put("workerLimit", 1).put("queueLimit", 64).put("tileBytes", png.size).put("fatal", fatal.get())
            .put("networkDelegateCalls", 0).put("scope", "MapLibre module requests only; cache hits need not call this module")
        fun cancelAndDrain() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Never join workers on UI" }
            closeAdmission()
            active.toList().forEach { it.cancelRequest() }
            workers.shutdown()
            check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "Map request worker did not drain; no more maps" }
            check(inflight.get() == 0 && callbacks.get() == 0 && active.isEmpty()) { "Map callback/request still in flight" }
        }
        private fun isRaster(url: String): Boolean {
            val match = knownRaster.matchEntire(url) ?: return false
            val values = tokenNames.zip(match.groupValues.drop(1)).associate { (name, value) -> name to value.toIntOrNull() }
            val z = values["z"] ?: return false
            if (z !in 0..19) return false
            val limit = 1 shl z
            return (values["x"] ?: -1) in 0 until limit && (values["y"] ?: -1) in 0 until limit
        }
        private inner class Request : HttpRequest {
            private val lock = Any()
            private val started = AtomicBoolean(false)
            private val cancelledOnce = AtomicBoolean(false)
            private val responded = AtomicBoolean(false)
            private val settled = AtomicBoolean(false)
            private var registered = false
            private var running = false
            private var future: FutureTask<Unit>? = null
            override fun executeRequest(responder: HttpResponder, ignoredNativePointer: Long, resourceUrl: String,
                dataRange: String?, etag: String?, modified: String?, offlineUsage: Boolean) {
                if (!started.compareAndSet(false, true)) { fatal.compareAndSet(null, "request executed twice"); return }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                val task = FutureTask(Callable {
                    synchronized(lock) { if (!cancelledOnce.get() && !closing.get()) running = true }
                    try {
                        if (!running || cancelledOnce.get() || closing.get()) return@Callable Unit
                        val known = isRaster(resourceUrl)
                        val expired = SystemClock.elapsedRealtime() > deadline
                        if (expired) { deadlines.incrementAndGet(); fatal.compareAndSet(null, "bounded request deadline exceeded") }
                        if (!known) { unexpected.incrementAndGet(); fatal.compareAndSet(null, "unexpected map resource") }
                        if (cancelledOnce.get() || closing.get() || !responded.compareAndSet(false, true)) return@Callable Unit
                        callbacks.incrementAndGet()
                        try {
                            if (!cancelledOnce.get() && !closing.get()) {
                                if (expired || !known) responder.handleFailure(HttpRequest.PERMANENT_ERROR, "Synthetic QA map resource unavailable")
                                else { responder.onResponse(200, null, null, "no-store", null, null, null, png); served.incrementAndGet() }
                            }
                        } finally { callbacks.decrementAndGet() }
                    } catch (_: Throwable) { fatal.compareAndSet(null, "synthetic responder callback failed") }
                    finally { synchronized(lock) { running = false; finish() } }
                    Unit
                })
                synchronized(gate) {
                    synchronized(lock) {
                        if (closing.get() || cancelledOnce.get()) return
                        registered = true; active += this; admitted.incrementAndGet(); inflight.incrementAndGet(); future = task
                        try { workers.execute(task) }
                        catch (_: RejectedExecutionException) {
                            // No synchronous responder reentry or extra thread when the one bounded queue is full.
                            saturation.incrementAndGet(); fatal.compareAndSet(null, "bounded map request queue saturated")
                            cancelledOnce.set(true); task.cancel(false); finish()
                        }
                    }
                }
            }
            override fun cancelRequest() {
                synchronized(lock) {
                    if (cancelledOnce.compareAndSet(false, true)) cancelled.incrementAndGet()
                    future?.cancel(false)
                    if (!running) finish()
                }
            }
            private fun finish() {
                if (registered && settled.compareAndSet(false, true)) { active.remove(this); inflight.decrementAndGet() }
            }
        }
        private fun syntheticPng(): ByteArray {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(225, 233, 226))
                val paint = Paint().apply { color = Color.rgb(181, 198, 185); strokeWidth = 2f }
                for (p in 0..256 step 64) {
                    canvas.drawLine(p.toFloat(), 0f, p.toFloat(), 256f, paint)
                    canvas.drawLine(0f, p.toFloat(), 256f, p.toFloat(), paint)
                }
                return ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
    }
}
