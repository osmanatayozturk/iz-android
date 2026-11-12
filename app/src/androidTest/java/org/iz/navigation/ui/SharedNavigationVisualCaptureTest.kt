@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package org.iz.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as FloatRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.Transport
import org.iz.navigation.health.HealthPrivacyActivity
import org.iz.navigation.integration.LocalOsmMapPresentation
import org.iz.navigation.integration.OsmMapPresentation
import org.iz.navigation.navigation.*
import org.iz.navigation.speed.RoadSpeedLimit
import org.iz.navigation.speed.RoadSpeedSource
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.weather.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * SOURCE PREPARATION ONLY. Later Root admission and exact test/APK/source bindings are mandatory.
 * No global screenshot, production observer, global SDK override, GPS or recorder is used.
 * Window PixelCopy may omit a MapLibre SurfaceView: compare against that owned surface and fail
 * integrated capture if inclusion cannot be demonstrated; never paint a synthetic map over the PNG.
 */
@RunWith(AndroidJUnit4::class)
class SharedNavigationVisualCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = RouteStop("Sentetik hedef", WeatherCoordinate(41.01, 29.01))
    private val longTarget = RouteStop(
        "Sentetik uzun hedef: sahil caddesindeki ikinci kavşaktan sonraki erişilebilir giriş ve buluşma alanı",
        target.coordinate)
    private val instruction = "Sentetik kavşaktan sağa dön ve uzun sahil caddesini izleyerek ikinci yol ayrımından şehir merkezi yönünde devam et"
    private val nativeMap = AtomicReference<MapLibreMap?>()
    private val plans = AtomicInteger()
    private val locations = AtomicInteger()
    private val activations = AtomicInteger()

    @Test fun sharedVehiclePortraitFontOneDefaultRecord() = frame(
        "01-shared-vehicle-portrait-font1", "sharedVehiclePortraitFontOneDefaultRecord", false, 1f
    ) { host, metadata ->
        shared(host, target, 1f)
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag("quick-record"))
        compose.onNodeWithTag("quick-record").assertIsOn()
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").assertIsSelected()
        compose.onNodeWithTag("quick-mode-selected-CAR", useUnmergedTree = true).assertIsDisplayed()
        for (mode in Transport.entries.filter { it != Transport.CAR }) {
            compose.onNodeWithTag("quick-mode-selected-" + mode.name, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("shared-preview").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("shared-start").assertDoesNotExist()
        assertEquals(0, plans.get()); assertEquals(0, locations.get()); assertEquals(0, activations.get())
        metadata.put("scrollPosition", scrollInfo("quick-route-options"))
            .put("recordChoiceAssertedOn", true)
            .put("selectionMarker", JSONObject().put("selectedSemantics", true)
                .put("visibleMark", "quick-mode-selected-CAR")
                .put("markBoundsRootPixels", rect(bounds("quick-mode-selected-CAR"))))
            .put("fieldVisibility", JSONObject().put("target", visibleText(target.label))
                .put("record", visibleTag("quick-record")).put("car", visibleTag("quick-mode-CAR"))
                .put("preview", visibleTag("shared-preview")))
            .put("offscreenFieldsAreOpen", true)
        geometry(listOf("quick-directions", "quick-route-options", "quick-record", "quick-mode-CAR",
            "quick-mode-selected-CAR", "shared-preview"))
    }

    @Test fun sharedPreviewLandscapeFontTwoNativeMapAndStart() = frame(
        "02-shared-preview-landscape-font2", "sharedPreviewLandscapeFontTwoNativeMapAndStart", true, 2f
    ) { host, metadata ->
        shared(host, longTarget, 2f)
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithTag("shared-start").assertIsDisplayed().assertIsEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertEquals(1, plans.get()); assertEquals(1, locations.get()); assertEquals(0, activations.get())
        metadata.put("previewMapOverride", false).put("recordChoice", true)
        val bounds = geometry(listOf("quick-directions", "shared-preview-content", "shared-start"))
        val content = bounds.getJSONObject("shared-preview-content")
        val start = bounds.getJSONObject("shared-start")
        check(start.getDouble("bottom") <= content.getDouble("bottom") + 1) { "Start leaves real preview viewport" }
        bounds
    }

    @Test fun integratedHudPortraitFontOneWarnings() =
        hudFrame("03-integrated-hud-portrait-font1", "integratedHudPortraitFontOneWarnings", false, 1f)

    @Test fun integratedHudLandscapeFontTwoWarnings() =
        hudFrame("04-integrated-hud-landscape-font2", "integratedHudLandscapeFontTwoWarnings", true, 2f)

    private fun hudFrame(state: String, method: String, landscape: Boolean, font: Float) =
        frame(state, method, landscape, font) { host, metadata ->
            val initial = guided()
            val nav = mutableStateOf(initial)
            content(host, font) {
                NavigationHomeContent(
                    ui = PhoneDirectionsState(), nav = nav.value, points = emptyList(), members = emptyList(),
                    showPlanner = false, weatherLabel = if (landscape) "Bilinmiyor" else "18°",
                    onSearch = {}, onRecord = {}, onGroup = {}, onMenu = {}, onWeather = {}, onMute = {},
                    onManageSession = {}, onLocate = {}, onPin = {}, planner = {})
            }
            compose.onNodeWithTag("compact-guidance-header").assertIsDisplayed()
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
            val before = bounds("compact-guidance-header")
            val displayedBefore = textInfo("hud-instruction")
            val longLayout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("hud-instruction", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(longLayout) }
            check(longLayout.single().lineCount <= 2)
            assertEquals(instruction, descriptions("hud-instruction"))
            compose.runOnIdle {
                nav.value = initial.copy(gpsStale = true, recordingPending = true,
                    approximateOrigin = initial.fix?.coordinate)
            }
            compose.onNodeWithText("GPS bekleniyor", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Kayıt bekleniyor", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
            val after = bounds("compact-guidance-header")
            assertEquals(before.height, after.height, 1f)
            for (tag in listOf("compact-card-mute", "home-finish-session", "hud-weather", "directions-recenter",
                "home-record", "home-group", "home-menu")) {
                compose.onNodeWithTag(tag).assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertHasClickAction()
            }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("hud-instruction", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            check(layouts.single().lineCount <= 2)
            for (message in listOf("GPS bekleniyor", "Kayıt bekleniyor")) {
                val measured = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(message, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(measured) }
                check(!measured.single().hasVisualOverflow) {
                    val layout = measured.single()
                    val kind = if (message == "GPS bekleniyor") "gps" else "recording"
                    "HUD_WARNING_OVERFLOW kind=$kind size=${layout.size} lineCount=${layout.lineCount} " +
                        "didOverflowWidth=${layout.didOverflowWidth} didOverflowHeight=${layout.didOverflowHeight} " +
                        "constraints=${layout.layoutInput.constraints}"
                }
            }
            metadata.put("headerBefore", rect(before)).put("headerAfter", rect(after))
                .put("instructionBefore", displayedBefore).put("instructionAfter", textInfo("hud-instruction"))
                .put("instructionBeforeLineCount", longLayout.single().lineCount)
                .put("instructionFullDescriptionBefore", instruction)
                .put("longManeuverFixture", instruction)
                .put("staleInstructionLimitation", "Product navigationInstruction replaces long maneuver with reliable-GPS waiting text while gpsStale. No production override.")
                .put("warningDescriptions", descriptions("hud-reserved-status"))
                .put("remaining", textInfo("hud-remaining"))
                .put("syntheticNavigation", JSONObject().put("session", initial.sessionId)
                    .put("guidance", true).put("actualRecorder", false).put("gpsStale", true)
                    .put("recordingPending", true).put("breakSeconds", 120))
            geometry(listOf("navigation-home", "navigation-home-map", "compact-guidance-header",
                "live-guidance-card", "hud-reserved-status", "navigation-map-controls-row",
                "compact-card-mute", "home-finish-session", "hud-weather", "directions-recenter",
                "home-record", "home-group", "home-menu"))
        }

    private fun shared(host: HealthPrivacyActivity, destination: RouteStop, font: Float) {
        val navigation = MutableStateFlow(NavigationState())
        val origin = QuickNavigationOrigin(NavigationFix(WeatherCoordinate(41.0, 29.0), 1000, 200f),
            false, 0, null, false)
        content(host, font) {
            QuickDirectionsDialog(emptyList(), navigation,
                locate = { locations.incrementAndGet(); origin }, validateOrigin = {},
                plan = { _, stop, mode, _ -> plans.incrementAndGet(); route(stop).copy(transport = mode) },
                activate = { _, _, _ -> activations.incrementAndGet(); error("Capture may not activate") },
                onDismiss = {}, onStarted = { error("Capture may not start") },
                hasLocationPermission = { true }, initialDestination = destination, sharedPreview = true)
            // previewMap intentionally omitted: production default WeatherRouteMap is required.
        }
    }

    private fun content(host: HealthPrivacyActivity, font: Float, body: @Composable () -> Unit) {
        instrumentation.runOnMainSync { host.setContent {
            val actualDensity = LocalDensity.current.density
            val presentation = remember { OsmMapPresentation(::localStyle, { nativeMap.set(it) }) }
            CompositionLocalProvider(LocalDensity provides Density(actualDensity, font),
                LocalOsmMapPresentation provides presentation) {
                IzTheme { Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("visual-owned-viewport")) { body() } }
            }
        } }
        compose.waitForIdle()
    }

    private fun route(destination: RouteStop = target): PlannedRoute {
        val a = RouteStop("Sentetik başlangıç", WeatherCoordinate(41.0, 29.0))
        val via = RouteStop("Sentetik mola", WeatherCoordinate(41.005, 29.005), 120)
        return PlannedRoute("shared-visual-synthetic-route", listOf(a, via, destination),
            listOf(RouteVertex(a.coordinate, 0.0), RouteVertex(via.coordinate, 45.0),
                RouteVertex(destination.coordinate, 90.0)), 1500.0, 90.0, 1000,
            stopElapsedSeconds = listOf(0.0, 45.0, 90.0), transport = Transport.MOTORCYCLE,
            maneuvers = listOf(RouteManeuver(1, instruction, instruction, emptyList(), 0, 2, 0.0, 90.0)))
    }

    private fun guided() = NavigationState(
        sessionId = "synthetic-visual-session", sessionTransport = Transport.MOTORCYCLE, guidance = true,
        route = route(), routeRevision = 1, gpsStale = false, locationActive = true,
        fix = NavigationFix(WeatherCoordinate(41.002, 29.002), 1000, 5f, 8f, 45f),
        progress = NavigationProgress(10.0, 80.0, 1300.0, 0.0, 0, 200.0),
        tripTiming = RouteTripTiming(80.0, 120.0, 200.0, 201000, 1000),
        roadSpeed = RoadSpeedState(30.0, RoadSpeedLimit(50.0, RoadSpeedSource.OSM, false,
            1000, WeatherCoordinate(41.002, 29.002), Transport.MOTORCYCLE, "synthetic-road"), true))

    private fun localStyle(raw: String): String = JSONObject(raw).apply {
        val sources = getJSONObject("sources")
        sources.remove("osm-tiles")
        val original = getJSONArray("layers")
        put("layers", JSONArray().put(JSONObject().put("id", "qa-local-visual-background")
            .put("type", "background").put("paint", JSONObject().put("background-color", "#E4EEE6"))).apply {
            for (i in 0 until original.length()) {
                val layer = original.getJSONObject(i)
                if (layer.optString("source") != "osm-tiles" && layer.optString("type") !in setOf("symbol", "background")) put(layer)
            }
        })
        remove("glyphs"); remove("sprite")
        val finalStyle = toString()
        check(!finalStyle.contains("https://") && !finalStyle.contains("http://")) { "Local style retains external URL" }
    }.toString()

    private fun frame(state: String, method: String, landscape: Boolean, font: Float,
        setup: (HealthPrivacyActivity, JSONObject) -> JSONObject) {
        val pins = admission() // All guards precede Activity launch and output directory creation.
        val directory = namespace(pins.getString("runUuid"))
        val png = File(directory, state + ".png")
        val json = File(directory, state + ".json")
        check(!png.exists() && !json.exists()) { "This state namespace is already consumed" }
        val metadata = JSONObject().put("schemaVersion", 1).put("state", state)
            .put("method", "org.iz.navigation.ui.SharedNavigationVisualCaptureTest#" + method)
            .put("pins", pins).put("syntheticInputs", true).put("actualGpsAcquisition", false)
            .put("actualRecording", false).put("captureStatus", "INCOMPLETE")
            .put("localFontOverride", font).put("virtualViewportOverride", JSONObject.NULL)
            .put("requestedActivityOrientation", if (landscape) "LANDSCAPE" else "PORTRAIT")
            .put("nativeTileAvailability", "REMOTE_TILES_REMOVED_LOCAL_BACKGROUND_ONLY")
            .put("settleCriteria", "Compose idle and required visible semantics; map states additionally require a rendered weather-route-layer feature within20s")
            .put("hostByteShaReadback", "PENDING_ROOT_RETRIEVAL")
            .put("independentUiuxPixelInspection", "PENDING")
            .put("nativeBasemapAcceptance", "NOT_RUN_LOCAL_STYLE_ONLY")
            .put("talkBackContrastFocusLiveServiceAcceptance", "NOT_RUN")
        var scenario: ActivityScenario<HealthPrivacyActivity>? = null
        var failure: Throwable? = null
        try {
            val context = instrumentation.targetContext
            val launched = ActivityScenario.launch<HealthPrivacyActivity>(Intent(context, HealthPrivacyActivity::class.java))
            scenario = launched
            val requested = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            launched.onActivity { it.requestedOrientation = requested }
            var host: HealthPrivacyActivity? = null
            compose.waitUntil(10_000) {
                launched.onActivity { host = it }
                host?.resources?.configuration?.orientation ==
                    if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            val activity = checkNotNull(host)
            instrumentation.runOnMainSync {
                check(activity.packageName == QA_PACKAGE && activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            }
            val geometry = AtomicReference<JSONObject>()
            // setContent must execute on the Activity's main thread; setup test actions stay on test thread.
            val result = setup(activity, metadata)
            geometry.set(result)
            metadata.put("geometryInComposeRootPixels", geometry.get())
                .put("capturedAt", System.currentTimeMillis())
                .put("counters", JSONObject().put("plans", plans.get()).put("locations", locations.get()).put("activations", activations.get()))
            check(activations.get() == 0)
            if (!state.startsWith("01")) awaitRenderedRoute()
            val owned = ownedWindow(activity, dialog = state.startsWith("01") || state.startsWith("02"))
            val window = windowInfo(activity, owned)
            metadata.put("window", window)
                .put("geometryWindowPixels", windowGeometry(result, window.getJSONArray("composeRootOffsetInWindowPixels")))
            for (key in listOf("headerBefore", "headerAfter")) if (metadata.has(key)) {
                metadata.put(key + "WindowPixels", offsetRect(metadata.getJSONObject(key),
                    window.getJSONArray("composeRootOffsetInWindowPixels")))
            }
            val bitmap = copyWindow(owned)
            try {
                metadata.put("native", nativeInclusion(owned, bitmap, state.startsWith("01")))
                Files.newOutputStream(png.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { stream ->
                    CappedOutput(stream, MAX_PNG).use { capped -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, capped)) }
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(png.absolutePath, options)
                check(options.outWidth == bitmap.width && options.outHeight == bitmap.height)
                metadata.put("width", bitmap.width).put("height", bitmap.height)
                    .put("pngBytes", png.length()).put("pngSha256", hash(png))
                    .put("captureMethod", "UIAUTOMATION_TAKESCREENSHOT_EXACT_OWNED_WINDOW_API34_NO_COMPOSITING")
                val native = metadata.getJSONObject("native")
                val valid = state.startsWith("01") || native.optString("windowInclusion") == "MATCHES_OWNED_NATIVE_PIXELS"
                metadata.put("captureStatus", if (valid) "CAPTURED_PENDING_HOST_READBACK_UIUX" else "NONPASS_NATIVE_INCLUSION_NOT_PROVEN")
                check(valid) { "Owned window image does not prove inclusion of the actual native map surface" }
            } finally { bitmap.recycle() }
        } catch (caught: Throwable) {
            failure = caught
            if (metadata.optString("captureStatus") == "INCOMPLETE") metadata.put("captureStatus", "NONPASS")
            metadata.put("failureType", caught.javaClass.name).put("failureMessage", caught.message?.take(400))
            if (png.exists()) metadata.put("availablePngBytes", png.length()).put("availablePngSha256", hash(png))
        } finally {
            try {
                scenario?.onActivity { it.setContent { } }
                compose.waitForIdle()
                scenario?.close()
                assertRealApplicationIdle()
            } catch (cleanupFailure: Throwable) {
                failure?.addSuppressed(cleanupFailure) ?: run { failure = cleanupFailure }
                metadata.put("cleanupFailure", cleanupFailure.javaClass.name).put("captureStatus", "NONPASS")
            }
            val bytes = metadata.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_JSON)
            Files.newOutputStream(json.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(bytes) }
        }
        failure?.let { throw it }
    }

    private fun admission(): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Separate Root visual capture allocation required",
            args.getString("izSharedNavigationVisual") == "isolated-offline")
        val context = instrumentation.targetContext
        check(QA_PACKAGE != "org.iz.navigation" && QA_PACKAGE in setOf(
            "org.iz.navigation.placeinfoqa20261112p01", "org.iz.navigation.placeinfoqa20261112p02"))
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == QA_PACKAGE && context.packageName == QA_PACKAGE)
        check(instrumentation.context.packageName == QA_PACKAGE + ".test")
        val strings = listOf("izCandidateCommit", "izCandidateTree", "izFixtureSha256", "izQaConfigSha256",
            "izAppApkSha256", "izTestApkSha256", "izSignerSha256", "izVisualRunUuid")
            .associateWith { checkNotNull(args.getString(it)) }
        check(args.getString("izExpectedPackage") == QA_PACKAGE)
        for (key in listOf("izCandidateCommit", "izCandidateTree")) check(strings.getValue(key).matches(Regex("[0-9a-f]{40}")))
        for ((key, value) in strings) if (key.endsWith("Sha256")) check(value.matches(Regex("[0-9a-f]{64}")))
        check(strings.getValue("izVisualRunUuid").matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        check(BuildConfig.OSM_CLIENT_ID.isEmpty() && BuildConfig.GROUP_SUPABASE_URL.isEmpty() && BuildConfig.GROUP_SUPABASE_KEY.isEmpty())
        val output = JSONObject().put("sourceCommitArgument", strings.getValue("izCandidateCommit"))
            .put("sourceTreeArgument", strings.getValue("izCandidateTree"))
            .put("fixtureSourceSha256Argument", strings.getValue("izFixtureSha256"))
            .put("qaConfigurationSha256Argument", strings.getValue("izQaConfigSha256"))
            .put("sourceArgumentBinding", "REQUIRES_ROOT_HOST_SOURCE_TEST_APK_READBACK")
            .put("runUuid", strings.getValue("izVisualRunUuid"))
        for ((appContext, key) in listOf(context to "izAppApkSha256", instrumentation.context to "izTestApkSha256")) {
            val name = appContext.packageName
            check(context.packageManager.checkPermission(Manifest.permission.INTERNET, name) == PackageManager.PERMISSION_DENIED)
            val apk = File(appContext.applicationInfo.sourceDir)
            check(hash(apk) == strings.getValue(key))
            val info = context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = checkNotNull(info.signingInfo).apkContentsSigners.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
            }
            check(signers == listOf(strings.getValue("izSignerSha256")))
            output.put(if (key == "izAppApkSha256") "actualApp" else "actualTest",
                JSONObject().put("package", name).put("versionCode", info.longVersionCode)
                    .put("baseApkBytes", apk.length()).put("baseApkSha256", hash(apk)).put("signerSha256", signers.single()))
        }
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)) check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        check(connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true })
        check(!TrackerSettings(context).enabled)
        assertRealApplicationIdle()
        return output
    }

    private fun assertRealApplicationIdle() {
        val context = instrumentation.targetContext
        check(!TrackingService.isRunning)
        val nav = (context.applicationContext as IzApplication).navigation.state.value
        check(nav.sessionId == null && nav.journey == null && nav.route == null &&
            !nav.guidance && !nav.recording && !nav.recordingPending && !nav.locationActive)
        check(runBlocking { DiaryRepository(context).snapshot() } == DiarySnapshot())
    }

    private fun namespace(uuid: String): File {
        val cache = instrumentation.targetContext.cacheDir.canonicalFile
        val base = File(cache, "shared-navigation-visual-v1")
        check(base.canonicalFile == base.absoluteFile)
        check(base.isDirectory || base.mkdir())
        val directory = File(base, uuid)
        check(directory.canonicalFile == directory.absoluteFile)
        check(directory.isDirectory || directory.mkdir())
        val allowed = STATES.flatMap { listOf(it + ".png", it + ".json") }.toSet()
        check(directory.listFiles().orEmpty().all { it.isFile && it.name in allowed })
        check(directory.listFiles().orEmpty().size <= 8)
        return directory
    }

    private fun awaitRenderedRoute() {
        compose.waitUntil(20_000) {
            var rendered = false
            instrumentation.runOnMainSync {
                nativeMap.get()?.let { map ->
                    val point = map.projection.toScreenLocation(LatLng(41.005, 29.005))
                    rendered = map.queryRenderedFeatures(point, "weather-route-layer").isNotEmpty()
                }
            }
            rendered
        }
    }

    private fun ownedWindow(host: Activity, dialog: Boolean): Window {
        var result: Window? = null
        instrumentation.runOnMainSync {
            val candidates = (listOf(host.window) + WindowInspector.getGlobalWindowViews()
                .flatMap { descendants(it) }.filterIsInstance<DialogWindowProvider>().map { it.window })
                .distinctBy { it.decorView }
                .filter { it.decorView.context.activity() === host && it.decorView.isShown && it.decorView.hasWindowFocus() }
            val window = candidates.single()
            check((window !== host.window) == dialog)
            check(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            result = window
        }
        return checkNotNull(result)
    }

    private fun copyWindow(window: Window): Bitmap {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (android.os.Build.VERSION.SDK_INT < 34) error("Owned-window capture requires API34")
        val view = window.decorView
        check(view.width in 1..4096 && view.height in 1..4096)
        val captured = checkNotNull(instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        ).takeScreenshot(window)) { "Owned-window SurfaceFlinger capture returned null" }
        var result: Bitmap? = null
        try {
            check(captured.width in 1..4096 && captured.height in 1..4096)
            check(captured.width == view.width && captured.height == view.height) {
                "Owned-window capture geometry does not match DecorView"
            }
            val image = if (captured.config == Bitmap.Config.ARGB_8888) captured
                else checkNotNull(captured.copy(Bitmap.Config.ARGB_8888, false)) {
                    "Owned-window software format copy failed"
                }
            result = image
            check(image.config == Bitmap.Config.ARGB_8888 && image.width == view.width && image.height == view.height)
            return image
        } catch (failure: Throwable) {
            result?.recycle()
            throw failure
        } finally {
            if (result !== captured) captured.recycle()
        }
    }

    private fun copyPixels(bitmap: Bitmap, start: (PixelCopy.OnPixelCopyFinishedListener) -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val result = AtomicInteger(-1)
        instrumentation.runOnMainSync { start(PixelCopy.OnPixelCopyFinishedListener { code -> result.set(code); latch.countDown() }) }
        check(latch.await(5, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS) { "Bounded owned PixelCopy failed: " + result.get() }
        check(bitmap.width in 1..4096 && bitmap.height in 1..4096)
    }

    private fun nativeInclusion(window: Window, image: Bitmap, noMapExpected: Boolean): JSONObject {
        var maps = emptyList<MapView>()
        instrumentation.runOnMainSync { maps = descendants(window.decorView).filterIsInstance<MapView>() }
        if (noMapExpected) { check(maps.isEmpty()); return JSONObject().put("nativeViews", 0).put("windowInclusion", "NOT_APPLICABLE") }
        val map = maps.single()
        var views = emptyList<View>()
        instrumentation.runOnMainSync {
            views = descendants(map).filter { it is SurfaceView || it is TextureView }
        }
        val renderView = views.single()
        check(renderView.width in 1..4096 && renderView.height in 1..4096)
        val surface = if (renderView is SurfaceView) {
            val bitmap = Bitmap.createBitmap(renderView.width, renderView.height, Bitmap.Config.ARGB_8888)
            try {
                copyPixels(bitmap) { listener -> PixelCopy.request(renderView, bitmap, listener, Handler(Looper.getMainLooper())) }
                bitmap
            } catch (failure: Throwable) { bitmap.recycle(); throw failure }
        } else {
            val value = AtomicReference<Bitmap?>()
            instrumentation.runOnMainSync { value.set((renderView as TextureView).bitmap) }
            checkNotNull(value.get())
        }
        try {
            val location = IntArray(2)
            instrumentation.runOnMainSync { renderView.getLocationInWindow(location) }
            var compared = 0; var matches = 0; var visiblePixels = 0
            for (iy in 1..20) for (ix in 1..20) {
                val x = ix * (surface.width - 1) / 21
                val y = iy * (surface.height - 1) / 21
                val wx = location[0] + x; val wy = location[1] + y
                if (wx !in 0 until image.width || wy !in 0 until image.height) continue
                val a = surface.getPixel(x, y); val b = image.getPixel(wx, wy)
                if (Color.alpha(a) == 0) continue
                compared++
                if (Color.red(a) > 20 || Color.green(a) > 20 || Color.blue(a) > 20) visiblePixels++
                if (abs(Color.red(a) - Color.red(b)) <= 12 && abs(Color.green(a) - Color.green(b)) <= 12 &&
                    abs(Color.blue(a) - Color.blue(b)) <= 12) matches++
            }
            val included = compared >= 100 && visiblePixels >= compared * .8 && matches >= compared * .95
            return JSONObject().put("nativeViews", 1).put("rendererViewClass", renderView.javaClass.name)
                .put("nativeViewBoundsWindowPixels", JSONObject().put("left", location[0]).put("top", location[1])
                    .put("right", location[0] + surface.width).put("bottom", location[1] + surface.height))
                .put("actualRenderedRouteQuery", true).put("ownedNativeSnapshotKeptInMemoryOnly", true)
                .put("samples", compared).put("matchingSamples", matches).put("visibleNativeSamples", visiblePixels)
                .put("windowInclusion", if (included) "MATCHES_OWNED_NATIVE_PIXELS" else "NOT_PROVEN")
                .put("comparisonLimit", "Separate frame samples and Compose overlays may cause a conservative nonpass; no compositing or global fallback.")
        } finally { surface.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun windowInfo(host: Activity, window: Window): JSONObject {
        val actual = host.resources.configuration
        val metrics = DisplayMetrics()
        host.windowManager.defaultDisplay.getRealMetrics(metrics)
        val decor = window.decorView
        val insets = decor.rootWindowInsets
        var composeOffset = IntArray(2)
        instrumentation.runOnMainSync {
            val roots = descendants(decor).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            roots.single().getLocationInWindow(composeOffset)
        }
        return JSONObject().put("ownedActivity", host.javaClass.name).put("package", host.packageName)
            .put("dialogWindow", window !== host.window).put("secureFlag", false)
            .put("windowWidthPx", decor.width).put("windowHeightPx", decor.height)
            .put("actualScreenWidthPx", metrics.widthPixels).put("actualScreenHeightPx", metrics.heightPixels)
            .put("actualDensity", host.resources.displayMetrics.density).put("actualFontScale", actual.fontScale)
            .put("actualOrientation", actual.orientation).put("actualScreenWidthDp", actual.screenWidthDp)
            .put("actualScreenHeightDp", actual.screenHeightDp)
            .put("composeRootOffsetInWindowPixels", JSONArray(composeOffset.toList()))
            .put("composeToWindowMapping", "Add recorded root offset to each boundsInRoot coordinate")
            .put("systemInsetsPx", JSONObject().put("left", insets?.systemWindowInsetLeft ?: 0)
                .put("top", insets?.systemWindowInsetTop ?: 0).put("right", insets?.systemWindowInsetRight ?: 0)
                .put("bottom", insets?.systemWindowInsetBottom ?: 0))
    }

    private fun bounds(tag: String): FloatRect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun geometry(tags: List<String>) = JSONObject().apply {
        for (tag in tags) try { put(tag, rect(bounds(tag))) } catch (_: AssertionError) { put(tag, JSONObject().put("visible", false).put("status", "OPEN_OR_ABSENT")) }
    }
    private fun rect(rect: FloatRect) = JSONObject().put("left", rect.left).put("top", rect.top)
        .put("right", rect.right).put("bottom", rect.bottom).put("width", rect.width).put("height", rect.height)
    private fun offsetRect(rect: JSONObject, offset: JSONArray) = JSONObject(rect.toString()).apply {
        for (key in listOf("left", "right")) put(key, rect.getDouble(key) + offset.getInt(0))
        for (key in listOf("top", "bottom")) put(key, rect.getDouble(key) + offset.getInt(1))
    }
    private fun windowGeometry(geometry: JSONObject, offset: JSONArray) = JSONObject().apply {
        val keys = geometry.keys()
        while (keys.hasNext()) {
            val key = keys.next(); val value = geometry.getJSONObject(key)
            put(key, if (value.has("left")) offsetRect(value, offset) else value)
        }
    }
    private fun textInfo(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
    private fun descriptions(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
    private fun visibleTag(tag: String) = try { compose.onNodeWithTag(tag).assertIsDisplayed(); true } catch (_: AssertionError) { false }
    private fun visibleText(text: String) = try { compose.onNodeWithText(text).assertIsDisplayed(); true } catch (_: AssertionError) { false }
    private fun scrollInfo(tag: String): JSONObject {
        val axis = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        return JSONObject().put("semanticsValue", axis?.value()?.toDouble() ?: 0.0)
            .put("maxValue", axis?.maxValue()?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
            .put("unit", "SEMANTICS_VALUE_NOT_ASSERTED_PIXELS")
    }
    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(descendants(root.getChildAt(i)))
    }
    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
        else -> null
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private class CappedOutput(output: OutputStream, private val cap: Long) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) { check(count + 1 <= cap); out.write(value); count++ }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            check(count + length <= cap); out.write(value, offset, length); count += length
        }
    }
    private companion object {
        val QA_PACKAGE = BuildConfig.APPLICATION_ID
        const val MAX_PNG = 16L * 1024 * 1024
        const val MAX_JSON = 64 * 1024
        val STATES = listOf("01-shared-vehicle-portrait-font1", "02-shared-preview-landscape-font2",
            "03-integrated-hud-portrait-font1", "04-integrated-hud-landscape-font2")
    }
}

