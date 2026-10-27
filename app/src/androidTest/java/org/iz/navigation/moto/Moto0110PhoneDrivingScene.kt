package org.iz.navigation.moto

import android.app.Activity
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
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.iz.navigation.IzApplication
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.OsmServiceSettings
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
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
import kotlin.math.max

/**
 * ONE synthetic native PHONE component scene, normal font only. Not MainActivity, a real
 * recorder/GNSS session, a ride, full-app navigation, real Auto/DHU or accuracy/energy evidence.
 * Actual NavigationHomeContent + PhoneDirectionsContent remain mounted around one actual MapView.
 * Public MapLibre13.6.1 contracts: android-v13.6.1/.../ModuleProvider.java,
 * http/{HttpRequest,HttpResponder,NativeHttpRequest}.java. No native-pointer/JNI access.
 * Module transport isolation applies only to this SDK path, not all application networking.
 */
@RunWith(AndroidJUnit4::class)
class Moto0110PhoneDrivingScene {
    @get:Rule val compose = createComposeRule()
    private val showScene = mutableStateOf(true)
    private val planning = mutableStateOf(false)
    private val containerHeight = mutableStateOf<Dp?>(null)
    private val a = WeatherCoordinate(41.0, 29.0)
    private val coveredFix = WeatherCoordinate(41.0005, 29.0005)
    private val b = WeatherCoordinate(41.0008, 29.0009)
    private val nav = mutableStateOf(NavigationState())
    private val disposed = AtomicBoolean(false)
    private val mounts = AtomicInteger(0)
    private val mapRef = AtomicReference<MapLibreMap?>()
    private val requestedView = AtomicReference<MapView?>()
    private val poses = JSONArray()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var density = 0f
    @Volatile private var fontScale = 0f
    private var lastFixAt = 0L
    private var sceneDeadline = 0L
    private var captures = 0
    private var transport: MemoryTiles? = null

    @Test(timeout = 90_000) fun nativePhoneDrivingSceneKeepsManualHoldAcrossActualPlanner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Dedicated QA only", "org.iz.navigation.batterycheck", context.packageName)
        assertFalse("No actual tracker", TrackingService.isRunning)
        val actual = (context.applicationContext as IzApplication).navigation.state.value
        assertFalse("No actual navigation/location session", actual.guidance || actual.recording || actual.locationActive || actual.loading)
        val directory = File(checkNotNull(context.getExternalFilesDir(null)),
            "qa/moto-0110-phone-scene/${SystemClock.uptimeMillis()}").apply { check(mkdirs()) }
        val evidence = JSONObject().put("status", "STARTED").put("evidenceClass", "SYNTHETIC_NATIVE_PHONE_COMPONENT")
            .put("fullAppOrRealGnssOrAutoHost", false).put("enlargedFont", false)
        var savedProvider: ModuleProvider? = null
        var shell: ComponentActivity? = null
        var primaryFailure: Throwable? = null
        var providerRestored = false
        var nativeViewsRemoved = false
        sceneDeadline = SystemClock.elapsedRealtime() + 65_000
        try {
            shell = onMain {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val activity = monitor.getActivitiesInStage(Stage.RESUMED).single()
                check(activity.javaClass == ComponentActivity::class.java) { "Only the blank Compose ComponentActivity is supported" }
                val existing = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                    .flatMap { monitor.getActivitiesInStage(it) }.distinct()
                check(existing.all { it.window?.decorView?.maps().orEmpty().isEmpty() }) {
                    "Existing native map: do not replace another component's global transport"
                }
                activity as ComponentActivity
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
                gpsStale = false, locationActive = true, fix = fix(a))
            lastFixAt = SystemClock.elapsedRealtime()
            compose.setContent {
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
                                    planner = {
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
            }
            compose.waitForIdle()
            assertEquals("Observe normal font; never override it", 1f, fontScale, .01f)
            val activity = checkNotNull(shell)
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
            val initial = snapshot(map, "initial-adaptive")
            assertAdaptive(initial, a)
            val exposed = exposedGeometry()
            val projected = onMain { map.projection.toScreenLocation(LatLng(a.latitude, a.longitude)) }
            val expectedY = exposed.top + .65f * (exposed.bottom - exposed.top)
            assertEquals("Fix anchor uses measured exposed height", expectedY.toDouble(), projected.y.toDouble(), 8.0)
            assertTrue("Fix must be in the exposed horizontal region", projected.x > exposed.left && projected.x < exposed.right)
            val mapBounds = bounds("navigation-home-map")
            val rootPoint = Offset(projected.x + mapBounds.left, projected.y + mapBounds.top)
            for (tag in listOf("open-navigation", "live-guidance-card", "home-weather", "home-record", "home-group", "home-menu", "directions-recenter", "map-layers")) {
                assertFalse("Projected fix is covered by $tag", bounds(tag).contains(rootPoint))
            }
            evidence.put("initialExposedPx", exposed.json()).put("initialProjectionPx", JSONArray(listOf(projected.x, projected.y)))
            capture(activity, directory, "01-initial-adaptive")

            // Actual measured overlays cover the small native viewport while follow is still active.
            compose.runOnIdle { containerHeight.value = 160.dp }
            compose.waitForIdle()
            await("occluded nonzero native viewport", 3_000) { onMain { abs(view.height - 160f * density) <= 2f } }
            val coveredMap = bounds("navigation-home-map")
            val topPanelsBottom = max(bounds("open-navigation").bottom, bounds("live-guidance-card").bottom)
            val bottomActionsTop = bounds("home-record").top
            assertTrue("Measured panels leave no exposed vertical gap", topPanelsBottom >= bottomActionsTop)
            evidence.put("occlusionMeasuredPx", JSONObject().put("map", coveredMap.json())
                .put("topPanelsBottom", topPanelsBottom.toDouble()).put("bottomActionsTop", bottomActionsTop.toDouble()))
            publishFix(coveredFix)
            awaitSource(map, coveredFix)
            await("covered native camera is actually flat", 3_000) { flat(onMain { pose(map) }) }
            assertHeld(map, initial, "covered-follow-new-fix", 1_200, preservePadding = true, requireFlat = true)
            compose.runOnIdle { containerHeight.value = null }
            compose.waitForIdle()
            await("reopened follow resumes at the current synthetic fix", 5_000) { adaptive(onMain { pose(map) }, coveredFix) }
            sameMap(activity, view, map)

            val beforePan = snapshot(map, "before-single-native-pan")
            swipeNative(view, exposedGeometry())
            await("native gesture moves the camera", 4_000) { !sameTarget(onMain { pose(map) }, beforePan) }
            settleCamera(map)
            snapshot(map, "after-single-native-pan")
            // Actual Home Actions opens the actual planner; no stand-in planner composable.
            compose.onNodeWithTag("home-finish-session").assertIsDisplayed().performClick()
            compose.onNodeWithTag("navigation-screen").assertIsDisplayed()
            assertTrue(planning.value)
            sameMap(activity, view, map)
            compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("close-directions-planner"))
            compose.onNodeWithTag("close-directions-planner").performClick()
            compose.onNodeWithTag("live-guidance-card").assertIsDisplayed()
            sameMap(activity, view, map)
            settleCamera(map)
            val postReturn = snapshot(map, "post-actual-planner-return-before-B")
            val since = publishFix(b)
            evidence.put("freshBElapsedSincePreviousFixMillis", since)
            assertTrue("Use real elapsed time, not a clock/GPS override", since >= 1_100)
            awaitSource(map, b)
            assertHeld(map, postReturn, "manual-held-native-source-B", 1_200)
            capture(activity, directory, "02-post-return-manual-held-B")
            compose.onNodeWithTag("directions-recenter").assertIsDisplayed().performClick()
            await("only actual Konumum releases hold and follows B", 5_000) { adaptive(onMain { pose(map) }, b) }
            awaitSource(map, b)
            snapshot(map, "actual-Konumum-follows-B")
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
            evidence.put("captureRequests", captures).put("poses", poses).put("mapCompositionMounts", mounts.get())
                .put("mapCompositionDisposed", disposed.get()).put("nativeViewsRemoved", nativeViewsRemoved)
                .put("providerIdentityRestored", providerRestored).put("transport", transport?.stats())
                .put("cleanupFailureCount", cleanupErrors.size).put("interruptedAtCleanup", wasInterrupted)
                .put("wholeApplicationNetworkSilenceProven", false)
                .put("scope", "Synthetic native Phone component only; no real app/GNSS/ride/Auto/physical lean/energy acceptance")
            if (cleanupErrors.isNotEmpty()) evidence.put("status", "CLEANUP_NONPASS_STOP_NO_FURTHER_MAPS")
            else if (primaryFailure == null) evidence.put("status", "SCENE_AND_TRANSPORT_CLEANUP_PASS")
            cleanup { File(directory, "scene.json").writeText(evidence.toString(2)) }
            if (wasInterrupted) Thread.currentThread().interrupt()
            if (cleanupErrors.isNotEmpty()) {
                val error = AssertionError("Scene cleanup/evidence failed; stop, do not run further maps", cleanupErrors.first())
                cleanupErrors.drop(1).forEach(error::addSuppressed)
                if (primaryFailure != null) primaryFailure.addSuppressed(error) else throw error
            }
        }
    }

    private fun fix(coordinate: WeatherCoordinate) = NavigationFix(coordinate, System.currentTimeMillis(), 5f, 8.3333f, 90f)
    private fun publishFix(coordinate: WeatherCoordinate): Long {
        val remaining = lastFixAt + 1_100 - SystemClock.elapsedRealtime()
        if (remaining > 0) SystemClock.sleep(remaining)
        val elapsed = SystemClock.elapsedRealtime() - lastFixAt
        compose.runOnIdle { nav.value = nav.value.copy(fix = fix(coordinate), gpsStale = false) }
        lastFixAt = SystemClock.elapsedRealtime()
        compose.waitForIdle()
        return elapsed
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun Rect.json() = JSONArray(listOf(left, top, right, bottom))
    /** Measured last panel plus its existing12dp outer gap; existing home safety bottom is112dp. */
    private fun exposedGeometry(): Rect {
        val map = bounds("navigation-home-map")
        val panelsBottom = max(max(bounds("open-navigation").bottom, bounds("live-guidance-card").bottom), bounds("home-weather").bottom)
        return Rect(28f * density, panelsBottom - map.top + 12f * density,
            map.width - 84f * density, map.height - 112f * density).also {
            assertTrue("Allocated native viewport must have adaptive exposed extent", it.width >= 160 && it.height >= 160)
        }
    }
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
    private fun adaptive(value: Pose, coordinate: WeatherCoordinate) = abs(value.lat - coordinate.latitude) <= .000001 &&
        abs(value.lon - coordinate.longitude) <= .000001 && abs(value.zoom - 17.0) <= .1 &&
        abs(value.pitch - 45.0) <= 1 && abs(((value.bearing - 90.0 + 540.0) % 360.0) - 180.0) <= 2
    private fun assertAdaptive(value: Pose, coordinate: WeatherCoordinate) { assertTrue("Actual native adaptive camera: ${value.json()}", adaptive(value, coordinate)) }
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
    /** Native GPU screenshot, guarded owned foreground scene, at most two one-shot captures. */
    private fun capture(activity: Activity, directory: File, name: String) {
        check(captures < 2)
        fun ownedFocusedScene(): IntArray = onMain {
            check(activity.javaClass == ComponentActivity::class.java && activity.packageName == "org.iz.navigation.batterycheck")
            check(!activity.isFinishing && !activity.isDestroyed)
            check((activity as ComponentActivity).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            check(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() === activity)
            check(activity.hasWindowFocus() && activity.window.decorView.hasWindowFocus()) { "Owned blank scene must have foreground window focus" }
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
                .put("ownedBlankResumedFocusedBeforeAndAfter", true)
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
