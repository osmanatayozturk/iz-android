package org.iz.navigation.car

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.media.ImageReader
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.SurfaceContainer
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
            try {
                withContext(Dispatchers.Main) { source.setVisible(true) }
                for ((index, automatic) in listOf(false, true).withIndex()) {
                    settings.enabled = automatic
                    assertEquals(automatic, settings.enabled)
                    val fix = gps.awaitFix(source, point(index), 14f + index)
                    assertEquals(14f + index, checkNotNull(fix.speedMps), .01f)
                    assertEquals(before, repository.snapshot())
                    assertNull(repository.activeJourney())
                    assertNull(app.navigation.state.value.sessionId)
                    assertNull(app.navigation.state.value.fix)
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
            try {
                shell("input keyevent KEYCODE_SLEEP")
                await("Synthetic test device never entered keyguard") { keyguard.isKeyguardLocked }
                val fix = gps.awaitFix(source, point(1), 20f)
                assertTrue("Fixture woke before the new fix reached the display", keyguard.isKeyguardLocked)
                assertEquals(20f, checkNotNull(fix.speedMps), .01f)
            } finally {
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                await("Synthetic test device did not exit keyguard") { !keyguard.isKeyguardLocked }
            }
        }
    }

    @Test
    fun projectedSessionRequiresBothVisibilityAndSurfaceForDisplayLocation() = runBlocking<Unit> {
        withSyntheticGps { app, _, _ ->
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
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                val frame = SurfaceContainer(reader.surface, 640, 400, 160)
                try {
                    withContext(Dispatchers.Main) { session.moveToState(Lifecycle.State.STARTED) }
                    delay(500)
                    assertFalse("Session alone must not start GPS", hasDisplayLocation())
                    val callback = withContext(Dispatchers.Main) {
                        checkNotNull(context.getCarService(TestAppManager::class.java).surfaceCallback)
                    }
                    withContext(Dispatchers.Main) {
                        callback.onSurfaceAvailable(frame)
                        callback.onStableAreaChanged(Rect(0, 0, 640, 400))
                        callback.onVisibleAreaChanged(Rect(0, 0, 640, 400))
                    }
                    await("Visible home and surface did not start display GPS", ::hasDisplayLocation)

                    withContext(Dispatchers.Main) { session.moveToState(Lifecycle.State.CREATED) }
                    await("Stopped session retained GPS") { !hasDisplayLocation() }
                    withContext(Dispatchers.Main) { session.moveToState(Lifecycle.State.STARTED) }
                    await("Restarted session did not restore display GPS", ::hasDisplayLocation)

                    withContext(Dispatchers.Main) { callback.onSurfaceDestroyed(frame) }
                    await("Lost surface retained GPS") { !hasDisplayLocation() }
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
            ActivityScenario.launch(MainActivity::class.java).use {
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

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
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
