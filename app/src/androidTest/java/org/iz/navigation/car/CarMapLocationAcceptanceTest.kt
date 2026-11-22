package org.iz.navigation.car

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.media.ImageReader
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.OnDoneCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.activity.ComponentActivity
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.tracking.CarMapLocation
import org.iz.navigation.tracking.CarMapLocationService
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class CarMapLocationAcceptanceTest {
    @Test
    fun offlineMovingMapDoesNotWriteDiaryWithAutomaticDetectionOnOrOff() = runBlocking<Unit> {
        withSyntheticGps { app, source, gps ->
            val repository = DiaryRepository(app)
            val before = repository.snapshot()
            val settings = TrackerSettings(app)
            val previouslyEnabled = settings.enabled
            val previousNavigationFix = app.navigation.state.value.fix
            try {
                withContext(Dispatchers.Main) { source.setVisible(true) }
                for ((index, automatic) in listOf(false, true).withIndex()) {
                    settings.enabled = automatic
                    assertEquals(automatic, settings.enabled)
                    val newPoint = point(10 + index)
                    val fix = gps.awaitFix(source, newPoint, 14f + index)
                    assertEquals(14f + index, checkNotNull(fix.speedMps), .01f)
                    assertEquals(before, repository.snapshot())
                    assertNull(repository.activeJourney())
                    assertNull(app.navigation.state.value.sessionId)
                    assertEquals(previousNavigationFix, app.navigation.state.value.fix)
                    assertFalse("Display GPS reached navigation", app.navigation.state.value.fix?.coordinate == newPoint)
                    assertFalse(TrackingService.isRunning)
                }
            } finally {
                settings.enabled = previouslyEnabled
            }
        }
    }

    @Test
    fun hiddenAndClosedDisplaysDiscardLateGpsAndReopenedDisplayNeedsFreshFix() = runBlocking<Unit> {
        withSyntheticGps { _, source, gps ->
            withContext(Dispatchers.Main) { source.setVisible(true) }
            gps.awaitFix(source, point(0), 10f)
            withContext(Dispatchers.Main) { source.setVisible(false) }
            assertNull(source.fix.value)
            gps.send(point(1), 13f)
            assertNoFix(source)

            withContext(Dispatchers.Main) { source.setVisible(true) }
            gps.send(point(2), 18f, ageMillis = 20_000L)
            assertNoFix(source)
            gps.awaitFix(source, point(3), 20f)

            withContext(Dispatchers.Main) {
                source.close()
                source.setVisible(true)
            }
            gps.send(point(4), 25f)
            assertNoFix(source)
        }
    }

    @Test
    fun registeredDisplayKeepsFreshGpsWhenTheEmulatorLocks() = runBlocking<Unit> {
        withSyntheticGps { app, source, gps ->
            val keyguard = checkNotNull(app.getSystemService(KeyguardManager::class.java))
            check(!keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) {
                "Use the unlocked synthetic emulator with a non-secure keyguard"
            }
            withContext(Dispatchers.Main) { source.setVisible(true) }
            gps.awaitFix(source, point(0), 12f)
            val wasDisabled = shell("locksettings get-disabled").toBooleanStrict()
            val stayOn = shell("settings get global stay_on_while_plugged_in")
            check(stayOn == "null" || stayOn.toIntOrNull() != null)
            try {
                shell("locksettings set-disabled false")
                assertEquals("false", shell("locksettings get-disabled"))
                shell("settings put global stay_on_while_plugged_in 0")
                assertEquals("0", shell("settings get global stay_on_while_plugged_in"))
                shell("input keyevent KEYCODE_SLEEP")
                await("Synthetic test device never entered keyguard") { keyguard.isKeyguardLocked }
                val fix = gps.awaitFix(source, point(1), 20f)
                assertTrue("Fixture woke before the new fix reached the display", keyguard.isKeyguardLocked)
                assertEquals(20f, checkNotNull(fix.speedMps), .01f)
            } finally {
                try {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    await("Synthetic test device did not exit keyguard") { !keyguard.isKeyguardLocked }
                } finally {
                    if (stayOn == "null") shell("settings delete global stay_on_while_plugged_in")
                    else shell("settings put global stay_on_while_plugged_in $stayOn")
                    shell("locksettings set-disabled $wasDisabled")
                    assertEquals(stayOn, shell("settings get global stay_on_while_plugged_in"))
                    assertEquals(wasDisabled.toString(), shell("locksettings get-disabled"))
                }
            }
        }
    }

    @Test
    fun projectedSessionRequiresBothVisibilityAndSurfaceForDisplayLocation() = runBlocking<Unit> {
        withSyntheticGps { app, _, gps ->
            val manager = checkNotNull(app.getSystemService(ActivityManager::class.java))
            @Suppress("DEPRECATION")
            fun hasDisplayLocation() =
                manager.getRunningServices(Int.MAX_VALUE).any {
                    it.foreground && it.service == android.content.ComponentName(app, CarMapLocationService::class.java)
                }

            val before = DiaryRepository(app).snapshot()
            val context = withContext(Dispatchers.Main) { TestCarContext.createCarContext(app) }
            val session = withContext(Dispatchers.Main) { SessionController(IzCarSession(), context, Intent()) }
            ImageReader.newInstance(640, 400, PixelFormat.RGBA_8888, 2).use { reader ->
                val frames = MarkerFrames()
                reader.setOnImageAvailableListener(frames::read, Handler(Looper.getMainLooper()))
                val frame = SurfaceContainer(reader.surface, 640, 400, 160)
                try {
                    val home = withContext(Dispatchers.Main) {
                        session.moveToState(Lifecycle.State.STARTED)
                        assertTrue(session.session.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        (context.getCarService(TestScreenManager::class.java).top as CarHomeScreen).also {
                            ScreenController(it).moveToState(Lifecycle.State.STARTED)
                            assertTrue(it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        }
                    }
                    val projectedGps = CarHomeScreen::class.java.getDeclaredField("mapLocation")
                        .apply { isAccessible = true }.get(home) as CarMapLocation
                    delay(500)
                    assertFalse("Session alone must not start GPS", hasDisplayLocation())
                    assertNull(projectedGps.fix.value)
                    assertTrue("ImageReader surface is invalid", reader.surface.isValid)
                    val callback = withContext(Dispatchers.Main) {
                        checkNotNull(context.getCarService(TestAppManager::class.java).surfaceCallback)
                    }
                    withContext(Dispatchers.Main) {
                        callback.onSurfaceAvailable(frame)
                        callback.onStableAreaChanged(Rect(0, 0, 640, 400))
                        callback.onVisibleAreaChanged(Rect(0, 0, 640, 400))
                    }
                    await("Started session, started home and valid surface did not start the display FGS",
                        ::hasDisplayLocation)
                    val nativeSurface = callback as CarMapSurface
                    for (index in 8..9) {
                        val coordinate = point(index)
                        withContext(Dispatchers.Main) { frames.reset() }
                        gps.awaitFix(projectedGps, coordinate, (index + 2).toFloat())
                        awaitNativeMapPosition(nativeSurface, coordinate, gps, frames)
                    }
                    assertEquals(before, DiaryRepository(app).snapshot())

                    withContext(Dispatchers.Main) { session.moveToState(Lifecycle.State.CREATED) }
                    await("Stopped session retained GPS") { !hasDisplayLocation() && projectedGps.fix.value == null }
                    withContext(Dispatchers.Main) {
                        session.moveToState(Lifecycle.State.STARTED)
                        ScreenController(home).moveToState(Lifecycle.State.STARTED)
                    }
                    await("Restarted session did not restore display GPS", ::hasDisplayLocation)
                    gps.awaitFix(projectedGps, point(10), 12f)

                    withContext(Dispatchers.Main) { callback.onSurfaceDestroyed(frame) }
                    await("Lost surface retained GPS") { !hasDisplayLocation() && projectedGps.fix.value == null }
                } finally {
                    withContext(Dispatchers.Main) { session.moveToState(Lifecycle.State.DESTROYED) }
                }
            }
            assertEquals(before, DiaryRepository(app).snapshot())
        }
    }

    @Test
    fun recordingOwnsLocationUntilItEndsAndDisplayThenNeedsNewGps() = runBlocking<Unit> {
        withSyntheticGps { app, source, gps ->
            val repository = DiaryRepository(app)
            val before = repository.snapshot()
            withContext(Dispatchers.Main) { source.setVisible(true) }
            gps.awaitFix(source, point(0), 12f)
            val session = app.navigation.startFreeDrive(Transport.CAR)
            val journey = checkNotNull(app.navigation.state.value.journey)
            try {
                await("Real recording service did not take over location") {
                    TrackingService.isRunning && TrackingService.runningJourneyId == journey.id &&
                        app.navigation.state.value.recording && source.fix.value == null
                }
                gps.send(point(1), 19f)
                assertNoFix(source)
                assertEquals(journey.id, repository.activeJourney()?.id)

                app.navigation.finishSession(session)
                await("Recording did not release location") {
                    !TrackingService.isRunning && app.navigation.state.value.sessionId == null
                }
                gps.awaitFix(source, point(2), 22f)
                assertNull(repository.activeJourney())
                assertNotNull(repository.getJourney(journey.id))
            } finally {
                app.navigation.finishSession(session)
                await("QA recording remained active after cleanup") { !TrackingService.isRunning }
                if (repository.getJourney(journey.id) != null) repository.deleteJourney(journey.id)
            }
            assertEquals(before, repository.snapshot())
        }
    }

    @Test
    fun automaticTransitionHonorsPreferenceAndCarSavePromotesTheSameJourney() = runBlocking<Unit> {
        withSyntheticGps { app, source, gps ->
            val repository = DiaryRepository(app)
            val before = repository.snapshot()
            val settings = TrackerSettings(app)
            val enabledBefore = settings.enabled
            var sessionId: String? = null
            var ownedId: String? = null
            fun detected() {
                ContextCompat.startForegroundService(app, Intent(app, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_DETECTED).putExtra("suggestedTransport", Transport.CAR.name))
            }
            try {
                settings.enabled = false
                withContext(Dispatchers.Main) { source.setVisible(true) }
                gps.awaitFix(source, point(13), 13f)
                detected()
                delay(1_000)
                await("Disabled detection left the recorder active") { !TrackingService.isRunning }
                assertNull(repository.activeJourney())
                assertEquals(before, repository.snapshot())
                gps.awaitFix(source, point(14), 14f)

                settings.enabled = true
                detected()
                await("Enabled detection did not park a candidate and release display GPS") {
                    TrackingService.runningJourneyId != null && source.fix.value == null &&
                        app.navigation.state.value.journey?.status == JourneyStatus.TEMPORARY
                }
                val candidate = checkNotNull(repository.activeJourney())
                ownedId = candidate.id
                assertEquals(candidate.id, TrackingService.runningJourneyId)
                assertEquals(JourneyStatus.TEMPORARY, candidate.status)
                val request = checkNotNull(carRecordingRequest(app.navigation.state.value, Transport.CAR))
                assertEquals(CarRecordingAction.SAVE, request.action)
                assertEquals(candidate.id, request.journeyId)
                sessionId = app.navigation.startCarRecording(request.mode, request.journeyId) {
                    app.navigation.state.value.journey?.id == candidate.id
                }
                assertEquals(candidate.id, repository.activeJourney()?.id)
                assertEquals(JourneyStatus.CONFIRMED, repository.getJourney(candidate.id)?.status)
                assertEquals(before.journeys.size + 1, repository.snapshot().journeys.size)
                assertNoFix(source)

                app.navigation.finishSession(checkNotNull(sessionId))
                await("Finished recorder did not release GPS") { !TrackingService.isRunning }
                gps.awaitFix(source, point(15), 15f)
            } finally {
                settings.enabled = enabledBefore
                sessionId?.let { app.navigation.finishSession(it) }
                val pending = repository.activeJourney()
                if (pending != null && before.journeys.none { it.id == pending.id }) {
                    ownedId = pending.id
                    app.navigation.finishJourney(pending.id)
                }
                await("Synthetic recorder remained active after cleanup") { !TrackingService.isRunning }
                ownedId?.let { if (repository.getJourney(it) != null) repository.deleteJourney(it) }
            }
            assertEquals(before, repository.snapshot())
        }
    }

    @Test
    fun manualCameraAndRecordingSurviveMenuSurfaceReplacementThenRecenter() = runBlocking<Unit> {
        withSyntheticGps { app, _, _ ->
            val repository = DiaryRepository(app)
            val before = repository.snapshot()
            val navigation = app.navigation
            val recordingSession = navigation.startFreeDrive(Transport.CAR)
            val journeyId = checkNotNull(navigation.state.value.journey).id
            try {
                await("Synthetic recording service did not bind the journey") {
                    TrackingService.runningJourneyId == journeyId && navigation.state.value.recording
                }
                val context = withContext(Dispatchers.Main) { TestCarContext.createCarContext(app) }
                val host = withContext(Dispatchers.Main) { SessionController(IzCarSession(), context, Intent()) }
                ImageReader.newInstance(640, 400, PixelFormat.RGBA_8888, 2).use { oldReader ->
                    ImageReader.newInstance(800, 480, PixelFormat.RGBA_8888, 2).use { newReader ->
                        val handler = Handler(Looper.getMainLooper())
                        oldReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                        newReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                        val first = SurfaceContainer(oldReader.surface, 640, 400, 160)
                        val replacement = SurfaceContainer(newReader.surface, 800, 480, 160)
                        try {
                            val (screens, home, surface) = withContext(Dispatchers.Main) {
                                host.moveToState(Lifecycle.State.STARTED)
                                val screens = context.getCarService(TestScreenManager::class.java)
                                val home = screens.top as CarHomeScreen
                                ScreenController(home).moveToState(Lifecycle.State.STARTED)
                                val surface = context.getCarService(TestAppManager::class.java).surfaceCallback as CarMapSurface
                                assertTrue(oldReader.surface.isValid)
                                surface.onSurfaceAvailable(first)
                                surface.onStableAreaChanged(Rect(0, 0, 640, 400))
                                surface.onVisibleAreaChanged(Rect(0, 0, 640, 400))
                                Triple(screens, home, surface)
                            }
                            val origin = point(20)
                            navigation.onAcceptedLocation(journeyId, NavigationFix(origin, System.currentTimeMillis(), 5f, 12f))
                            val following = awaitCamera(surface, "initial GPS follow") { camera ->
                                camera.target?.let { matches(it.latitude, it.longitude, origin) } == true
                            }
                            val original = checkNotNull(following.target)
                            withContext(Dispatchers.Main) {
                                surface.panMode(true)
                                surface.onScroll(168f, -96f)
                                surface.zoom(1.0)
                            }
                            val manual = awaitCamera(surface, "manual pan and zoom") { camera ->
                                val target = camera.target
                                target != null && abs(camera.zoom - following.zoom) > .25 &&
                                    abs(target.latitude - original.latitude) + abs(target.longitude - original.longitude) > 1e-5
                            }
                            val manualTarget = checkNotNull(manual.target)
                            withContext(Dispatchers.Main) {
                                val action = (home.onGetTemplate() as NavigationTemplate).actionStrip!!.actions
                                    .single { it.title.toString() == "Yol tarifi" }
                                requireNotNull(action.onClickDelegate).sendClick(object : OnDoneCallback {})
                                val menu = screens.top as CarMenuScreen
                                ScreenController(menu).moveToState(Lifecycle.State.STARTED)
                                assertTrue(newReader.surface.isValid)
                                surface.onSurfaceDestroyed(first)
                                surface.onSurfaceAvailable(replacement)
                                surface.onStableAreaChanged(Rect(0, 0, 800, 480))
                                surface.onVisibleAreaChanged(Rect(0, 0, 800, 480))
                                screens.pop()
                                assertSame(home, screens.top)
                            }
                            val restored = awaitCamera(surface, "manual camera after menu surface replacement") { camera ->
                                val target = camera.target
                                target != null && abs(target.latitude - manualTarget.latitude) < 1e-6 &&
                                    abs(target.longitude - manualTarget.longitude) < 1e-6 && abs(camera.zoom - manual.zoom) < .01
                            }
                            assertEquals(recordingSession, navigation.state.value.sessionId)
                            assertTrue(navigation.state.value.recording)
                            assertEquals(journeyId, TrackingService.runningJourneyId)
                            assertEquals(journeyId, repository.activeJourney()?.id)
                            assertEquals(before.journeys.size + 1, repository.snapshot().journeys.size)

                            withContext(Dispatchers.Main) { home.recenter() }
                            val next = point(21)
                            navigation.onAcceptedLocation(journeyId, NavigationFix(next, System.currentTimeMillis(), 5f, 13f))
                            val centered = awaitCamera(surface, "new GPS follow after recenter") { camera ->
                                camera.target?.let { matches(it.latitude, it.longitude, next) } == true
                            }
                            assertEquals(journeyId, TrackingService.runningJourneyId)
                            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                                putString("carManualCameraEvidence", "manual=$manual restored=$restored recentered=$centered")
                            })
                        } finally {
                            withContext(Dispatchers.Main) { host.moveToState(Lifecycle.State.DESTROYED) }
                        }
                    }
                }
            } finally {
                navigation.finishSession(recordingSession)
                await("Synthetic recording did not finish during cleanup") { !TrackingService.isRunning }
                if (repository.getJourney(journeyId) != null) repository.deleteJourney(journeyId)
            }
            assertEquals(before, repository.snapshot())
        }
    }

    private suspend fun awaitCamera(
        surface: CarMapSurface,
        phase: String,
        expected: (CameraPosition) -> Boolean,
    ): CameraPosition {
        val field = CarMapSurface::class.java.getDeclaredField("map").apply { isAccessible = true }
        var latest: CameraPosition? = null
        val match = withTimeoutOrNull(12_000) {
            do {
                latest = withContext(Dispatchers.Main) {
                    val map = field.get(surface) as? MapLibreMap
                    map?.cameraPosition?.takeIf { map.style?.isFullyLoaded == true }
                }
                val camera = latest
                if (camera != null && expected(camera)) return@withTimeoutOrNull camera
                delay(100)
            } while (true)
            null
        }
        return checkNotNull(match) { "$phase did not settle; latest=$latest" }
    }

    private suspend fun withSyntheticGps(test: suspend (IzApplication, CarMapLocation, SyntheticGps) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<IzApplication>()
        val expectedPackage = InstrumentationRegistry.getArguments().getString("izAutoQaPackage")
        check(expectedPackage == app.packageName && app.packageName.startsWith("org.iz.navigation.") &&
            app.packageName != "org.iz.navigation.batterycheck" &&
            instrumentation.context.packageName == "${app.packageName}.test" &&
            app.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED) {
            "Use the verified INTERNET-free Android Auto QA package and izAutoQaPackage argument"
        }
        check(Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE in setOf("goldfish", "ranchu"))
        check(checkNotNull(app.getSystemService(ConnectivityManager::class.java)).activeNetwork == null)
        check(DiaryRepository(app).activeJourney() == null && app.navigation.state.value.sessionId == null &&
            !TrackingService.isRunning)

        val automation = instrumentation.uiAutomation
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            automation.grantRuntimePermission(app.packageName, permission)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        fun appOp(mode: String) = shell("appops set ${app.packageName} android:mock_location $mode")
        val gps = SyntheticGps(app)
        appOp("allow")
        try {
            gps.install()
            ActivityScenario.launch(ComponentActivity::class.java).use {
                val source = withContext(Dispatchers.Main) { CarMapLocation(app) }
                try {
                    test(app, source, gps)
                } finally {
                    withContext(Dispatchers.Main) { source.close() }
                }
            }
        } finally {
            try {
                gps.close()
            } finally {
                appOp("default")
            }
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        }

    private suspend fun awaitNativeMapPosition(
        surface: CarMapSurface, expected: WeatherCoordinate, gps: SyntheticGps, frames: MarkerFrames,
    ) {
        val mapField = CarMapSurface::class.java.getDeclaredField("map").apply { isAccessible = true }
        val submittedField = CarMapSurface::class.java.getDeclaredField("paintedFixJson").apply { isAccessible = true }
        var observation = "map unavailable"
        var nextGps = 0L
        val followed = withTimeoutOrNull(10_000) {
            var matched: Boolean
            do {
                val now = SystemClock.uptimeMillis()
                if (now >= nextGps) {
                    gps.send(expected, 10f)
                    nextGps = now + 1_000L
                }
                matched = withContext(Dispatchers.Main) {
                    val map = mapField.get(surface) as? MapLibreMap
                    val style = map?.style
                    val target = map?.cameraPosition?.target
                    val features = style?.getSourceAs<GeoJsonSource>("car-fix")
                        ?.querySourceFeatures(null).orEmpty()
                    val positions = features.mapNotNull { it.geometry() as? Point }
                        .map { it.latitude() to it.longitude() }.distinct()
                    val visibleLayer = listOf("fix", "heading").any {
                        style?.getLayer(it)?.visibility?.value == Property.VISIBLE
                    }
                    val submitted = (submittedField.get(surface) as? String)?.let {
                        JSONObject(it).optJSONArray("features")?.optJSONObject(0)
                            ?.optJSONObject("geometry")?.optJSONArray("coordinates")
                    }
                    val ready = style?.isFullyLoaded == true && target != null && matches(target.latitude, target.longitude, expected) &&
                        submitted != null && matches(submitted.optDouble(1), submitted.optDouble(0), expected) && visibleLayer
                    if (ready && !frames.armed) {
                        frames.arm(checkNotNull(map).projection.toScreenLocation(LatLng(expected.latitude, expected.longitude)))
                    }
                    observation = "styleLoaded=${style?.isFullyLoaded} target=$target sourceFeatures=${features.size} " +
                        "sourceCoordinates=$positions visibleLayer=$visibleLayer submitted=$submitted image=${frames.evidence}"
                    ready && frames.matchesCurrentImage()
                }
                if (!matched) delay(100)
            } while (!matched)
            true
        }
        assertTrue("Native camera and fresh marker pixels did not follow $expected: $observation", followed == true)
    }

    private fun matches(latitude: Double, longitude: Double, expected: WeatherCoordinate) =
        abs(latitude - expected.latitude) < 1e-6 && abs(longitude - expected.longitude) < 1e-6

    private class MarkerFrames {
        private var center: PointF? = null
        private var last = Long.MIN_VALUE
        private var baseline = Long.MIN_VALUE
        private var sampled = Long.MIN_VALUE
        private var glassPixels = 0
        val armed get() = center != null
        val evidence get() = "baseline=$baseline timestamp=$sampled glassPixels=$glassPixels"

        fun reset() {
            center = null
            glassPixels = 0
        }

        fun arm(projected: PointF) {
            center = projected
            baseline = last
            sampled = Long.MIN_VALUE
            glassPixels = 0
        }

        fun matchesCurrentImage() = sampled > baseline && glassPixels >= 8

        fun read(reader: ImageReader) {
            reader.acquireLatestImage()?.use { image ->
                val timestamp = image.timestamp
                if (timestamp <= last) return@use
                last = timestamp
                val point = center ?: return@use
                if (timestamp <= baseline) return@use
                val plane = image.planes.first()
                if (plane.pixelStride < 4) return@use
                val pixels = plane.buffer
                var count = 0
                for (y in (point.y.toInt() - 24).coerceAtLeast(0)..(point.y.toInt() + 24).coerceAtMost(image.height - 1)) {
                    for (x in (point.x.toInt() - 24).coerceAtLeast(0)..(point.x.toInt() + 24).coerceAtMost(image.width - 1)) {
                        val offset = y * plane.rowStride + x * plane.pixelStride
                        if (offset + 2 >= pixels.limit()) continue
                        val rgb = ((pixels.get(offset).toInt() and 255) shl 16) or
                            ((pixels.get(offset + 1).toInt() and 255) shl 8) or (pixels.get(offset + 2).toInt() and 255)
                        if (rgb == 0xb3dfee || rgb == 0x263d50) count++
                    }
                }
                sampled = timestamp
                glassPixels = count
            }
        }
    }

    private suspend fun assertNoFix(source: CarMapLocation) {
        assertNull("An inactive or stale GPS fix reached the display",
            withTimeoutOrNull(1_500) { source.fix.first { it != null } })
    }

    private suspend fun await(message: String, condition: () -> Boolean) {
        assertTrue(message, withTimeoutOrNull(12_000) {
            while (!condition()) delay(100)
            true
        } == true)
    }

    private fun point(index: Int) = WeatherCoordinate(0.25 + index * .001, -140.0)

    private class SyntheticGps(context: Context) {
        private val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
        private val provider = LocationManager.GPS_PROVIDER
        private var installed = false

        fun install() {
            manager.addTestProvider(provider, ProviderProperties.Builder()
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .build())
            installed = true
            manager.setTestProviderEnabled(provider, true)
        }

        fun send(point: WeatherCoordinate, metersPerSecond: Float, ageMillis: Long = 0) {
            manager.setTestProviderLocation(provider, Location(provider).apply {
                latitude = point.latitude
                longitude = point.longitude
                accuracy = 5f
                speed = metersPerSecond
                time = System.currentTimeMillis() - ageMillis
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMillis * 1_000_000
            })
        }

        suspend fun awaitFix(source: CarMapLocation, point: WeatherCoordinate, speed: Float): NavigationFix {
            return checkNotNull(withTimeoutOrNull(12_000) {
                var result = source.fix.value
                while (result?.coordinate != point) {
                    send(point, speed)
                    delay(300)
                    result = source.fix.value
                }
                result
            }) { "Visible GPS did not reach the isolated map location owner" }
        }

        fun close() {
            if (installed) manager.removeTestProvider(provider)
        }
    }
}
