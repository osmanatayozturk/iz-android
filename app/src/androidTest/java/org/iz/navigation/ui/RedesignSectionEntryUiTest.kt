package org.iz.navigation.ui

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import androidx.room.withTransaction
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class RedesignSectionEntryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun phoneSectionsReturnToMapWithoutCreatingASession() {
        val app = requireIsolatedApp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
            captureWindowIfAllocated(scenario, "home-synthetic.png")
            compose.onNodeWithTag("home-record").assertIsDisplayed().performClick()
            compose.onNodeWithText("Vazgeç").assertIsDisplayed().performClick()
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
            visit(scenario, "menu-route-plan") { compose.onNodeWithTag("navigation-screen").assertIsDisplayed() }
            visit(scenario, "menu-journeys") { compose.onNode(hasText("Yolculuklar") and !hasClickAction()).assertIsDisplayed() }
            visit(scenario, "menu-osm") { compose.onNodeWithTag("osm-contribution-hub").assertIsDisplayed() }
            visit(scenario, "menu-weather") { compose.onNodeWithTag("weather_planner").assertIsDisplayed() }
            visit(scenario, "menu-settings") {
                compose.onNodeWithTag("settings").assertIsDisplayed()
                compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-detection"))
                compose.onNodeWithTag("settings-open-detection").performClick()
                compose.onNodeWithTag("settings-view-detection").assertIsDisplayed()
                dispatchActivityBack(scenario)
                compose.onNodeWithTag("settings").assertIsDisplayed()
            }
            compose.runOnIdle { assertIdleNavigation(app.navigation.state.value) }
        }
    }

    @Test fun voiceLayersReturnsToVoiceAndSharesTheSavedRouteChoiceWithTheMapMenu() {
        val app = requireIsolatedApp()
        val preferences = app.getSharedPreferences("navigation_map_view", Context.MODE_PRIVATE)
        val original = preferences.all["show_route"] as? Boolean
        val saved = !(original ?: true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
                compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-voice"))
                compose.onNodeWithTag("settings-open-voice").performClick()
                compose.onNodeWithText("Harita katmanları").performScrollTo().performClick()
                compose.onNodeWithTag("map-layers-settings").assertIsDisplayed()
                compose.onNodeWithTag("map-layer-route").performScrollTo().performClick()
                compose.onNodeWithTag("map-layer-dismiss").performClick()
                compose.onNodeWithTag("settings-view-voice").assertIsDisplayed()

                compose.onNodeWithText("Harita katmanları").performScrollTo().performClick()
                val route = compose.onNodeWithTag("map-layer-route").performScrollTo()
                if (saved) route.assertIsOn() else route.assertIsOff()
                pressModalBack()
                compose.onNodeWithTag("map-layers-settings").assertDoesNotExist()
                compose.onNodeWithTag("settings-view-voice").assertIsDisplayed()

                dispatchActivityBack(scenario)
                compose.onNodeWithTag("settings").assertIsDisplayed()
                dispatchActivityBack(scenario)
                compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-map-layers").performScrollTo().performClick()
                compose.onNodeWithText(if (saved) "✓ Planlanan rota" else "Planlanan rota").assertIsDisplayed()
                pressModalBack()
                compose.runOnIdle { assertIdleNavigation(app.navigation.state.value) }
            }
        } finally {
            val reset = preferences.edit()
            if (original == null) reset.remove("show_route") else reset.putBoolean("show_route", original)
            reset.apply()
        }
    }

    @Test fun journalSubpagesReturnToTheSameCompletedSyntheticJourney() {
        val app = requireIsolatedApp()
        val repository = DiaryRepository(app)
        val now = System.currentTimeMillis()
        val journey = Journey(id = UUID.randomUUID().toString(), title = "Sentetik tamamlanmış yolculuk",
            transport = Transport.MOTORCYCLE, status = JourneyStatus.CONFIRMED,
            startedAt = now - 60_000L, endedAt = now, interrupted = true)
        assertEquals(DiarySnapshot(), runBlocking { repository.snapshot() })
        runBlocking { repository.saveJourney(journey) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-journeys").performScrollTo().performClick()
                visitJournalLink(journey.title)
                compose.onNode(hasText(journey.title) and !hasClickAction()).assertIsDisplayed()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText("Mesafe ölçülemedi", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                captureWindowIfAllocated(scenario, "journal-synthetic.png")

                val subpages = listOf(
                    "İzi haritada aç" to "Konum kaydında kesinti var.",
                    "Süre ve mesafe" to "Yolculuk istatistikleri",
                    "Ziyaretler" to "Bu yolculukta kayıtlı ziyaret yok.",
                    "Fotoğraflar" to "Bu yolculukta fotoğraf yok.",
                    "Raporlar" to "Viraj ve yatış raporu",
                )
                for ((entry, heading) in subpages) {
                    visitJournalLink(entry)
                    compose.onNodeWithText(heading).assertIsDisplayed()
                    dispatchActivityBack(scenario)
                    val pageTitle = hasText(journey.title) and !hasClickAction()
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(pageTitle)
                    compose.onNode(pageTitle).assertIsDisplayed()
                }
                assertIdleNavigation(app.navigation.state.value)
                assertEquals(journey, runBlocking { repository.getJourney(journey.id) })
            }
        } finally {
            runBlocking {
                assertEquals(journey, repository.getJourney(journey.id))
                repository.deleteJourney(journey.id)
                assertEquals(DiarySnapshot(), repository.snapshot())
            }
        }
    }

    @Test fun settingsSectionsAndDiagnosticsReturnWithoutChangingPermissionsOrAccounts() {
        val app = requireIsolatedApp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
            for (section in listOf("theme", "voice", "detection", "calibrate", "health", "osmsettings", "backup", "licenses")) {
                val entry = "settings-open-$section"
                compose.onNodeWithTag("settings").performScrollToNode(hasTestTag(entry))
                compose.onNodeWithTag(entry).performClick()
                compose.onNodeWithTag("settings-view-$section").assertIsDisplayed()
                when (section) {
                    "voice" -> {
                        compose.onNodeWithTag("settings-open-Trafik ve hız sınırı").performScrollTo().performClick()
                        compose.onNodeWithText("Kapat").performClick()
                        compose.onNodeWithTag("settings-view-voice").performScrollTo().assertIsDisplayed()
                    }
                    "detection" -> {
                        compose.onNodeWithTag("settings-open-Algılama tanısı").performScrollTo().performClick()
                        compose.onNodeWithTag("settings-view-diags").assertIsDisplayed()
                        dispatchActivityBack(scenario)
                        compose.onNodeWithTag("settings-view-detection").performScrollTo().assertIsDisplayed()
                    }
                    "licenses" -> compose.onNodeWithText("© OpenStreetMap katkıcıları").performScrollTo().assertIsDisplayed()
                }
                dispatchActivityBack(scenario)
                compose.onNodeWithTag("settings").assertIsDisplayed()
            }
            dispatchActivityBack(scenario)
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
            compose.runOnIdle { assertIdleNavigation(app.navigation.state.value) }
        }
    }

    @Test fun statisticsOpensComparisonAndReturnsToItsCallerWithAnEmptyDiary() {
        val app = requireIsolatedApp()
        assertEquals(DiarySnapshot(), runBlocking { DiaryRepository(app).snapshot() })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-statistics").performScrollTo().performClick()
            val compare = hasText("Karşılaştır") and hasClickAction()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(compare)
            compose.onNode(compare).performClick()
            compose.onNodeWithText("Karşılaştırmak için iki tamamlanmış yolculuk gerekiyor.").assertIsDisplayed()
            dispatchActivityBack(scenario)
            compose.onNode(compare).assertIsDisplayed()
            dispatchActivityBack(scenario)
            compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        }
    }

    @Test fun routePreferencesCanBeOpenedAndCancelledWithoutStartingANavigationSession() {
        val app = requireIsolatedApp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("home-menu").performClick()
            compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-voice"))
            compose.onNodeWithTag("settings-open-voice").performClick()
            compose.onNodeWithTag("settings-open-Vasıtaya göre rota tercihleri").performScrollTo().performClick()
            compose.onNodeWithText("Rota tercihleri").assertIsDisplayed()
            compose.onNodeWithText("Vazgeç").performClick()
            compose.onNodeWithTag("settings-view-voice").performScrollTo().assertIsDisplayed()
            dispatchActivityBack(scenario)
            compose.onNodeWithTag("settings").assertIsDisplayed()
            compose.runOnIdle { assertIdleNavigation(app.navigation.state.value) }
        }
    }

    @Test fun expiredWatchRequestExplainsItsStateAndCannotStartARecording() {
        val app = requireIsolatedApp()
        ActivityScenario.launch(org.iz.navigation.wear.WearStartActivity::class.java).use {
            val explanation = "Bu isteğin süresi dolmuş veya işlem tamamlanmış. Saatten tekrar deneyebilirsin."
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(explanation)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(explanation).assertIsDisplayed()
            compose.onNodeWithText("Başlat").assertDoesNotExist()
            compose.onNodeWithText("Kapat").performClick()
            compose.runOnIdle { assertIdleNavigation(app.navigation.state.value) }
        }
    }

    @Test fun captureExpiredWearRequest() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("izVisualEntryId") == "wearrequest")
        val app = requireIsolatedApp()
        ActivityScenario.launch(org.iz.navigation.wear.WearStartActivity::class.java).use {
            val expired = hasText("Bu isteğin süresi dolmuş", substring = true)
            compose.waitUntil(10_000) { compose.onAllNodes(expired).fetchSemanticsNodes().isNotEmpty() }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            try {
                val dir = File(app.cacheDir, "redesign-window-captures")
                check(dir.isDirectory || dir.mkdirs())
                val file = File(dir, "wearrequest.png")
                check(file.createNewFile())
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun captureAllocatedJournalMapOrExport() {
        val id = InstrumentationRegistry.getArguments().getString("izVisualJournalId")
        org.junit.Assume.assumeTrue(id == "trackmap" || id == "export")
        val app = requireIsolatedApp()
        val repository = DiaryRepository(app)
        val db = DiaryDatabase.get(app)
        val start = System.currentTimeMillis() - 300_000
        val row = Journey(title = "Örnek bisiklet parkuru", transport = Transport.BICYCLE, startedAt = start,
            endedAt = start + 50_000, interrupted = true)
        val points = (0..5).map { step ->
            TrackPoint(journeyId = row.id, latitude = 41.0 + step * .00010,
                longitude = 29.0 + step * .00008, recordedAt = start + step * 10_000, accuracy = 4f)
        }
        runBlocking {
            assertEquals(DiarySnapshot(), repository.snapshot())
            db.withTransaction {
                val dao = db.diaryDao()
                dao.save(row)
                points.forEach { dao.save(it) }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-journeys").performScrollTo().performClick()
                visitJournalLink(row.title)
                if (id == "trackmap") {
                    visitJournalLink("İzi haritada aç")
                    compose.onNodeWithText("Konum kaydında kesinti var.").assertIsDisplayed()
                    compose.waitUntil(20_000) {
                        compose.onAllNodes(hasTestTag("recorded-speed-legend")).fetchSemanticsNodes().isNotEmpty()
                    }
                } else {
                    visitJournalLink("GPX rotasını dışa aktar")
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(hasText("6 konum", substring = true)).fetchSemanticsNodes().isNotEmpty()
                    }
                }
                val middle = LatLng((points[2].latitude + points[3].latitude) / 2,
                    (points[2].longitude + points[3].longitude) / 2)
                captureJournalNativeWindow(scenario, requireNotNull(id), app, middle)
            }
        } finally {
            runBlocking {
                repository.deleteJourney(row.id)
                assertEquals(DiarySnapshot(), repository.snapshot())
            }
        }
    }

    private data class JournalCapture(val window: Window, val x: Int, val y: Int)

    private fun captureJournalNativeWindow(
        scenario: ActivityScenario<MainActivity>, id: String, app: IzApplication, middle: LatLng,
    ) {
        check(Build.VERSION.SDK_INT >= 34)
        val export = id == "export"
        val color = if (export) "#3989C9" else "#87939B"
        var viewedMap: MapView? = null
        var nativeMap: MapLibreMap? = null
        var settled: Bitmap? = null
        var diagnostic = "No visible owned map window"
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        try {
            compose.waitUntil(20_000) {
                var capture: JournalCapture? = null
                scenario.onActivity { activity ->
                    check(activity.packageName == app.packageName && !activity.isDestroyed)
                    val window = if (!export) activity.window else WindowInspector.getGlobalWindowViews()
                        .asSequence().flatMap(::journalDescendants).filterIsInstance<DialogWindowProvider>()
                        .map { it.window }.distinctBy { it.decorView }
                        .filter { it.context.findActivity() === activity && it.decorView.isShown }
                        .filter { dialog -> journalDescendants(dialog.decorView).any { it is MapView && it.isShown } }
                        .singleOrNull()
                    val view = window?.takeIf { it.decorView.isShown }?.let {
                        journalDescendants(it.decorView).filterIsInstance<MapView>().filter { map -> map.isShown }.singleOrNull()
                    }
                    if (view !== viewedMap) {
                        viewedMap = view
                        nativeMap = null
                        view?.getMapAsync { map -> if (viewedMap === view) nativeMap = map }
                    }
                    val map = nativeMap
                    if (window == null || !window.decorView.hasWindowFocus() ||
                        view == null || map == null || view.width <= 0 || view.height <= 0) {
                        diagnostic = "window=${window != null} view=${view != null} nativeMap=${map != null}"
                        return@onActivity
                    }
                    check(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
                    val source = map.style?.getSourceAs<GeoJsonSource>("routes")?.querySourceFeatures(null).orEmpty()
                    val point = map.projection.toScreenLocation(middle)
                    val inViewport = point.x.isFinite() && point.y.isFinite() &&
                        point.x in 0f..view.width.toFloat() && point.y in 0f..view.height.toFloat()
                    val hits = if (inViewport) map.queryRenderedFeatures(
                        RectF(point.x - 5, point.y - 5, point.x + 5, point.y + 5), "journey-lines",
                    ) else emptyList()
                    diagnostic = "routes=${source.size} centerInViewport=$inViewport " +
                        "rendered=${hits.size} zoom=${map.cameraPosition.zoom}"
                    if (source.isEmpty() || hits.none { it.getStringProperty("color") == color }) return@onActivity
                    val offset = IntArray(2).also(view::getLocationInWindow)
                    val origin = IntArray(2).also(window.decorView::getLocationInWindow)
                    capture = JournalCapture(window, offset[0] - origin[0] + point.x.roundToInt(),
                        offset[1] - origin[1] + point.y.roundToInt())
                }
                val frame = capture ?: return@waitUntil false
                val bitmap = automation.takeScreenshot(frame.window) ?: return@waitUntil false
                val pixels = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
                    try { checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) } finally { bitmap.recycle() }
                }
                try {
                    if (pixels.width == frame.window.decorView.width && pixels.height == frame.window.decorView.height &&
                        hasJournalLinePixel(pixels, frame.x, frame.y, Color.parseColor(color))) {
                        settled = pixels
                    } else {
                        diagnostic += " ownedWindow=${pixels.width}x${pixels.height} routePixelsMissing=true"
                    }
                    settled != null
                } finally {
                    if (settled !== pixels) pixels.recycle()
                }
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Journal native route not ready in the $id window. $diagnostic", timeout)
        }
        val pixels = checkNotNull(settled)
        try {
            val directory = File(app.cacheDir, "redesign-window-captures")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "$id.png")
            check(output.createNewFile())
            output.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            pixels.recycle()
        }
    }

    private fun journalDescendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(journalDescendants(view.getChildAt(index)))
    }

    private fun hasJournalLinePixel(bitmap: Bitmap, x: Int, y: Int, color: Int): Boolean {
        var matches = 0
        for (row in maxOf(0, y - 12)..minOf(bitmap.height - 1, y + 12)) {
            for (column in maxOf(0, x - 12)..minOf(bitmap.width - 1, x + 12)) {
                val pixel = bitmap.getPixel(column, row)
                if (Color.alpha(pixel) >= 224 && abs(Color.red(pixel) - Color.red(color)) <= 18 &&
                    abs(Color.green(pixel) - Color.green(color)) <= 18 &&
                    abs(Color.blue(pixel) - Color.blue(color)) <= 18 && ++matches >= 4) return true
            }
        }
        return false
    }

    @Test fun captureAllocatedMenuDestination() {
        val id = InstrumentationRegistry.getArguments().getString("izVisualEntryId")
        org.junit.Assume.assumeTrue(!id.isNullOrBlank())
        val destinations = mapOf(
            "group" to ("menu-group" to hasTestTag("group")),
            "contrib" to ("menu-osm" to hasTestTag("osm-contribution-hub")),
            "activity" to ("menu-osm" to hasText("Etkinlikler ve taslaklar")),
            "survey" to ("menu-osm" to hasText("Yerinde tamamlama görevleri")),
            "record" to ("" to hasText("Kaydı başlat")),
            "calibrate" to ("menu-settings" to hasTestTag("settings-view-calibrate")),
            "osmsettings" to ("menu-settings" to hasTestTag("settings-view-osmsettings")),
            "layers" to ("menu-settings" to hasTestTag("map-layers-settings")),
            "gpx" to ("menu-gpx-follow" to hasTestTag("gpx-import")),
            "routes" to ("menu-route-library" to hasTestTag("routes-list")),
            "trips" to ("menu-collections" to hasText("Yeni gezi", substring = true)),
            "stats" to ("menu-statistics" to hasText("Karşılaştır")),
            "places" to ("menu-places" to hasText("Yeni yer", substring = true)),
            "journeys" to ("menu-journeys" to (hasText("Yolculuklar") and !hasClickAction())),
            "heat" to ("menu-heatmap" to hasText("Yoğunluk haritası", substring = true)),
            "plan" to ("menu-route-plan" to hasTestTag("navigation-screen")),
        )
        val (entry, marker) = destinations.getValue(requireNotNull(id))
        val app = requireIsolatedApp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            if (id == "record") {
                compose.onNodeWithTag("home-record").performClick()
            } else {
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag(entry).performScrollTo().performClick()
            }
            if (id == "activity" || id == "survey") {
                val link = if (id == "activity") "osm-contribution-activities" else "osm-contribution-survey"
                compose.onNodeWithTag("osm-contribution-list").performScrollToNode(hasTestTag(link))
                compose.onNodeWithTag(link).performClick()
            }
            if (id == "layers") {
                compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-open-voice"))
                compose.onNodeWithTag("settings-open-voice").performClick()
                compose.onNodeWithText("Harita katmanları").performScrollTo().performClick()
            }
            if (id == "calibrate" || id == "osmsettings") {
                val link = "settings-open-$id"
                compose.onNodeWithTag("settings").performScrollToNode(hasTestTag(link))
                compose.onNodeWithTag(link).performClick()
            }
            compose.waitUntil(10_000) { compose.onAllNodes(marker).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val title = when (id) {
                "calibrate" -> "Motosiklet montajı ve kalibrasyon"
                "osmsettings" -> "OSM ve harita servisleri"
                else -> null
            }
            if (title != null) assertEquals(1, compose.onAllNodes(hasText(title)).fetchSemanticsNodes().size)
            if (id == "group") assertEquals(1, compose.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Geri")).fetchSemanticsNodes().size)
            captureEntryWindow(scenario, id, app)
        }
    }

    private fun captureEntryWindow(scenario: ActivityScenario<MainActivity>, id: String, app: IzApplication) {
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) {
            captureWindowIfAllocated(scenario, "$id.png")
            return
        }
        val out = File(app.cacheDir, "redesign-window-captures")
        check(out.isDirectory || out.mkdirs())
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        try {
            val file = File(out, "$id.png")
            check(file.createNewFile())
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun requireIsolatedApp(): IzApplication {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IzApplication
        assertEquals("org.iz.navigation.qaredesign20261119", app.packageName)
        assertTrue(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION)) {
            assertEquals(permission, PackageManager.PERMISSION_DENIED, app.checkSelfPermission(permission))
        }
        assertIdleNavigation(app.navigation.state.value)
        return app
    }

    private fun visitJournalLink(label: String) {
        val link = hasText(label) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(link)
        compose.onNode(link).assertIsDisplayed().performClick()
    }

    private fun visit(scenario: ActivityScenario<MainActivity>, menuTag: String, assertDestination: () -> Unit) {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag(menuTag).performScrollTo().assertIsDisplayed().performClick()
        assertDestination()
        dispatchActivityBack(scenario)
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
    }

    private fun dispatchActivityBack(scenario: ActivityScenario<MainActivity>) {
        if (useRedesignDispatcherBack()) {
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        } else {
            pressBack()
        }
    }

    private fun pressModalBack() {
        if (useRedesignDispatcherBack()) compose.runOnIdle { dispatchRedesignModalBack() }
        else pressBack()
    }

    private fun captureWindowIfAllocated(scenario: ActivityScenario<MainActivity>, name: String) {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("izRedesignWindowPng") != "true" && args.getString("izVisualEntryId") == null && args.getString("izVisualJournalId") == null) return
        val app = requireIsolatedApp()
        val copyDone = CountDownLatch(1)
        val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        lateinit var bitmap: Bitmap
        scenario.onActivity { activity ->
            check(activity.packageName == app.packageName && !activity.isDestroyed)
            val decor = activity.window.decorView
            check(decor.isShown && decor.width in 1..4096 && decor.height in 1..4096)
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap, { status ->
                result.set(status)
                copyDone.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        try {
            check(copyDone.await(5, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS) {
                "QA Activity PixelCopy failed with status ${result.get()}"
            }
            val directory = File(app.cacheDir, "redesign-window-captures")
            check(directory.mkdirs() || directory.isDirectory)
            val output = File(directory, name)
            check(output.createNewFile())
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertIdleNavigation(state: NavigationState) {
        assertNull(state.sessionId)
        assertNull(state.journey)
        assertNull(state.route)
        assertNull(state.trackFollow)
        assertFalse(state.guidance)
        assertFalse(state.recording)
    }
}

internal fun useRedesignDispatcherBack(): Boolean =
    InstrumentationRegistry.getInstrumentation().targetContext.packageName == "org.iz.navigation.qaredesign20261119" &&
        InstrumentationRegistry.getArguments().getString("izRedesignBack") == "dispatcher-no-focus"

// Dispatch the owned Compose dialog callback without claiming that Android delivered a Back input.
internal fun dispatchRedesignModalBack() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(useRedesignDispatcherBack())
    check(context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
    check(Looper.myLooper() == Looper.getMainLooper())
    val owner = visibleRedesignDialogWindow().callback as OnBackPressedDispatcherOwner
    owner.onBackPressedDispatcher.onBackPressed()
}

internal fun visibleRedesignDialogWindow(): Window {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == "org.iz.navigation.qaredesign20261119")
    check(Looper.myLooper() == Looper.getMainLooper())
    val windows = linkedSetOf<Window>()
    fun collect(view: View) {
        if (view is DialogWindowProvider && view.window.context.packageName == context.packageName &&
            view.window.decorView.isShown) windows += view.window
        if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
    }
    WindowInspector.getGlobalWindowViews().forEach(::collect)
    return windows.single()
}
