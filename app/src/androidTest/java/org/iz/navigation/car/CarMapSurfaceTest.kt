package org.iz.navigation.car

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.app.Presentation
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.car.app.SurfaceContainer
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.IzApplication
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.DriveCameraMode
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.speed.RoadSpeedLimit
import org.iz.navigation.speed.RoadSpeedSource
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.camera.CameraPosition
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import kotlin.math.abs

/** Isolated real GL/View -> VirtualDisplay -> host Surface test. It does not impersonate DHU. */
@RunWith(AndroidJUnit4::class)
class CarMapSurfaceTest {
    @Test fun routeAndMarkerReachHostSurfaceWithoutLiveTrailAndSurviveReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<IzApplication>()
        val thread = HandlerThread("car-surface-test").apply { start() }
        val handler = Handler(thread.looper)
        val readers = mutableListOf<ImageReader>()
        val captureFailure = AtomicReference<Throwable?>(null)
        val lastFrameCounts = AtomicReference("")
        var renderer: CarMapSurface? = null
        fun assertDeferredTrailAndAttribution(safe: Rect) {
            instrumentation.runOnMainSync {
                val field = CarMapSurface::class.java.getDeclaredField("trailLegend").apply { isAccessible = true }
                val label = field.get(renderer) as TextView
                assertTrue("Live trail speed scale must remain deferred during recording",
                    label.visibility == View.GONE && !label.isShown)
                val map = CarMapSurface::class.java.getDeclaredField("map").apply { isAccessible = true }
                    .get(renderer) as MapLibreMap
                val style = checkNotNull(map.style)
                assertNull("Live recorded trail geometry must not be uploaded during recording", style.getSource("car-trail"))
                val source = CarMapSurface::class.java.getDeclaredField("attribution").apply { isAccessible = true }.get(renderer) as TextView
                val sourceXy = IntArray(2).also { source.getLocationInWindow(it) }
                val sourceBounds = Rect(sourceXy[0], sourceXy[1], sourceXy[0] + source.width, sourceXy[1] + source.height)
                assertTrue("Attribution must remain visible in safe area: $sourceBounds in $safe", source.isShown &&
                    sourceBounds.width() > 0 && sourceBounds.height() > 0 && safe.contains(sourceBounds))
            }
        }
        fun viewDiagnostics(): String {
            var result = ""
            instrumentation.runOnMainSync {
                val field = CarMapSurface::class.java.getDeclaredField("presentation").apply { isAccessible = true }
                val presentation = renderer?.let { field.get(it) as? Presentation }
                val entries = mutableListOf<String>()
                fun inspect(view: View, depth: Int) {
                    val xy = IntArray(2).also { view.getLocationInWindow(it) }
                    entries.add("${" ".repeat(depth)}${view.javaClass.simpleName} xy=${xy.toList()} size=${view.width}x${view.height} measured=${view.measuredWidth}x${view.measuredHeight} visibility=${view.visibility} alpha=${view.alpha} elevation=${view.elevation} hardware=${view.isHardwareAccelerated}" +
                        if (view is TextView) " text=${view.text} color=${view.currentTextColor}" else "")
                    if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index), depth + 1)
                }
                presentation?.window?.let { window ->
                    entries.add("window size=${window.attributes.width}x${window.attributes.height} flags=${window.attributes.flags}")
                    inspect(window.decorView, 0)
                }
                result = entries.joinToString("\n")
            }
            return result
        }
        try {
            fun hostFrame(width: Int, height: Int, dpi: Int = 160): Pair<SurfaceContainer, CountDownLatch> {
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                readers.add(reader)
                val marker = CountDownLatch(1)
                reader.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes.first()
                        val buffer = plane.buffer
                        var markerPixels = 0
                        var routePixels = 0
                        var attributionPixels = 0
                        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
                            val offset = y * plane.rowStride + x * plane.pixelStride
                            if (offset + 2 < buffer.limit()) {
                                val red = buffer.get(offset).toInt() and 255
                                val green = buffer.get(offset + 1).toInt() and 255
                                val blue = buffer.get(offset + 2).toInt() and 255
                                // Production marker fill #00cbaa; attribution/background cannot satisfy this.
                                if (red < 35 && green in 175..225 && blue in 145..195) markerPixels++
                                if (red in 35..75 && green in 95..145 && blue in 210..255) routePixels++
                                if (x > image.width - 300 && y in image.height - 90 until image.height - 40) {
                                    val dayAttribution = red > 210 && green > 210 && blue > 210
                                    val nightAttribution = red in 23..38 && green in 27..43 && blue in 33..52 && blue > red + 3
                                    if (dayAttribution || nightAttribution) attributionPixels++
                                }
                            }
                        }
                        lastFrameCounts.set("${image.width}: marker=$markerPixels route=$routePixels attribution=$attributionPixels")
                        if (markerPixels >= 8 && routePixels >= 20 && attributionPixels >= 30 && marker.count > 0) {
                            try {
                                // Read each actual pixel: row padding and channel order must not leak into PNG.
                                val pixels = IntArray(image.width * image.height)
                                for (y in 0 until image.height) for (x in 0 until image.width) {
                                    val offset = y * plane.rowStride + x * plane.pixelStride
                                    pixels[y * image.width + x] = Color.argb(
                                        buffer.get(offset + 3).toInt() and 255,
                                        buffer.get(offset).toInt() and 255,
                                        buffer.get(offset + 1).toInt() and 255,
                                        buffer.get(offset + 2).toInt() and 255)
                                }
                                val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
                                try {
                                    File(checkNotNull(app.getExternalFilesDir(null)), "car-surface-$width.png").outputStream().use {
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                } finally { bitmap.recycle() }
                            } catch (failure: Throwable) { captureFailure.set(failure) }
                            finally { marker.countDown() }
                        }
                    }
                }, handler)
                return SurfaceContainer(reader.surface, width, height, dpi) to marker
            }
            val (first, firstMarker) = hostFrame(640, 400)
            val now = System.currentTimeMillis()
            val journey = Journey(id = "isolated-car-render", transport = Transport.CAR, startedAt = now - 60_000)
            val current = WeatherCoordinate(41.0, 29.0)
            val ahead = WeatherCoordinate(41.0004, 29.0012)
            val turn = WeatherCoordinate(41.0014, 29.0012)
            val destination = WeatherCoordinate(41.0014, 29.003)
            val route = PlannedRoute("isolated-car-render-route",
                listOf(RouteStop("Konum", current), RouteStop("Örnek hedef", destination)),
                listOf(RouteVertex(current, 0.0), RouteVertex(ahead, 20.0), RouteVertex(turn, 40.0),
                    RouteVertex(destination, 60.0)), 370.0, 60.0, now, transport = Transport.CAR,
                maneuvers = listOf(org.iz.navigation.weather.RouteManeuver(
                    10, "Sağa dön", "Sağa dön", emptyList(), 1, 2, 20.0, 40.0)))
            val recording = NavigationState(journey = journey, recording = true, route = route,
                gpsStale = false, fix = NavigationFix(current, now, 5f),
                roadSpeed = RoadSpeedState(57.6, RoadSpeedLimit(90.0, RoadSpeedSource.TOMTOM_POSTED,
                    true, now, current, Transport.CAR, "test-road"), true))
            instrumentation.runOnMainSync {
                renderer = CarMapSurface(TestCarContext.createCarContext(app)).also {
                    it.setActive(true)
                    it.update(recording)
                    it.onSurfaceAvailable(first)
                    it.onStableAreaChanged(Rect(160, 0, 640, 360))
                    it.onVisibleAreaChanged(Rect(160, 0, 640, 400))
                }
            }
            val firstReady = firstMarker.await(20, TimeUnit.SECONDS)
            assertTrue("First surface layers/attribution incomplete: ${lastFrameCounts.get()}\n${if (!firstReady) viewDiagnostics() else ""}", firstReady)
            assertNull("First rendered PNG capture failed", captureFailure.get())
            assertDeferredTrailAndAttribution(Rect(160, 0, 640, 360))
            assertTrue("Own speed and generic source must reach the host views", viewDiagnostics().let {
                it.contains("Hızım") && it.contains("58 km/sa") && it.contains("Genel yol sınırı · TomTom")
            })
            val output = checkNotNull(renderer)
            lateinit var nativeMap: MapLibreMap
            lateinit var controls: CarCameraInteraction
            lateinit var attribution: TextView
            instrumentation.runOnMainSync {
                nativeMap = CarMapSurface::class.java.getDeclaredField("map").apply { isAccessible = true }
                    .get(output) as MapLibreMap
                controls = CarMapSurface::class.java.getDeclaredField("interaction").apply { isAccessible = true }
                    .get(output) as CarCameraInteraction
                attribution = CarMapSurface::class.java.getDeclaredField("attribution").apply { isAccessible = true }
                    .get(output) as TextView
                assertEquals("The synthetic profile uses normal font", 1f, attribution.resources.configuration.fontScale, .01f)
                output.update(NavigationState())
            }
            val fresh = NavigationState(guidance = true, sessionTransport = Transport.CAR, route = route,
                gpsStale = false, fix = NavigationFix(current, now, 5f))
            fun ownedAnimator(): Any = checkNotNull(CarMapSurface::class.java.getDeclaredField("cameraAnimator")
                .apply { isAccessible = true }.get(output))
            fun activeEase(animator: Any): Any? =
                animator.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(animator)
            fun awaitFollow(coordinate: WeatherCoordinate, update: () -> Unit): CameraPosition {
                val idle = CountDownLatch(1)
                lateinit var settled: CameraPosition
                val listener = MapLibreMap.OnCameraIdleListener {
                    val position = nativeMap.cameraPosition
                    val target = position.target
                    if (target != null && abs(target.latitude - coordinate.latitude) < 1e-7 &&
                        abs(target.longitude - coordinate.longitude) < 1e-7 && abs(position.tilt - 45.0) < .01) {
                        settled = position
                        idle.countDown()
                    }
                }
                try {
                    instrumentation.runOnMainSync {
                        nativeMap.addOnCameraIdleListener(listener)
                        update()
                    }
                    assertTrue("Native follow ease did not settle at the submitted fix", idle.await(3, TimeUnit.SECONDS))
                    return settled
                } finally {
                    instrumentation.runOnMainSync { nativeMap.removeOnCameraIdleListener(listener) }
                }
            }
            var baseBottom = 0.0
            instrumentation.runOnMainSync {
                baseBottom = 40.0 + maxOf(attribution.height,
                    attribution.lineHeight + attribution.paddingTop + attribution.paddingBottom) + 8.0
            }
            fun anchor(position: CameraPosition): Double =
                .5 + checkNotNull(position.padding)[1] / (2.0 * (400.0 - baseBottom))
            val profile = listOf(0, 30, 60, 90, 110, 60, 0).mapIndexed { index, speed ->
                if (index > 0) SystemClock.sleep(1_050)
                val coordinate = current.copy(longitude = current.longitude + (index + 1) * .00001)
                awaitFollow(coordinate) {
                    output.update(fresh.copy(fix = NavigationFix(coordinate, now + index * 1_050L, 5f, speed / 3.6f, 60f)))
                }.also {
                    assertTrue("Settled zoom must stay within the approved curve", it.zoom in 15.49..17.51)
                    assertTrue("Settled anchor must stay within the approved band", anchor(it) in 0.649..0.701)
                    assertEquals("Viewport bottom padding must reflect the live attribution inset", baseBottom,
                        checkNotNull(it.padding)[3], .75)
                }
            }
            assertEquals(.65, anchor(profile[0]), .002)
            assertEquals(.65, anchor(profile[1]), .002)
            assertTrue(anchor(profile[4]) > anchor(profile[2]))
            assertTrue(anchor(profile[6]) < anchor(profile[4]))
            assertTrue(profile[4].zoom < profile[1].zoom)
            assertTrue(profile[6].zoom > profile[4].zoom)
            SystemClock.sleep(1_050)
            val turnFix = current.copy(longitude = current.longitude + .00009)
            val nearTurn = awaitFollow(turnFix) {
                output.update(fresh.copy(fix = NavigationFix(turnFix, now + 8_400L, 5f, 110f / 3.6f, 60f),
                    progress = NavigationProgress(0.0, 60.0, 370.0, 0.0, 0, 100.0)))
            }
            assertTrue("A near turn must pull the filtered camera toward 65% / 17.5",
                anchor(nearTurn) <= anchor(profile.last()) + .001 && nearTurn.zoom >= profile.last().zoom - .001)

            SystemClock.sleep(1_050)
            val interrupted = fresh.copy(fix = NavigationFix(destination, now + 9_450L, 5f))
            lateinit var stoppedPosition: CameraPosition
            instrumentation.runOnMainSync {
                output.update(interrupted)
                val animator = ownedAnimator()
                assertTrue("Stale transition must interrupt an owned native ease", activeEase(animator) != null)
                output.update(interrupted.copy(gpsStale = true))
                assertNull("Stale GPS must cancel the owned ease", activeEase(animator))
                stoppedPosition = nativeMap.cameraPosition
            }
            SystemClock.sleep(1_050)
            instrumentation.runOnMainSync {
                assertEquals("A cancelled ease must not finish at the stale fix", stoppedPosition, nativeMap.cameraPosition)
            }
            // Same fix and viewport, delivered on the normal cadence without an explicit recenter.
            awaitFollow(destination) { output.update(interrupted) }
            instrumentation.runOnMainSync {
                output.cameraMode(DriveCameraMode.SIMPLE_2D)
                output.update(fresh)
                output.recenter()
                assertEquals("Simple follow stays flat immediately", 0.0, nativeMap.cameraPosition.tilt, .01)
                assertNull("A fresh fix in simple mode must not start easing", activeEase(ownedAnimator()))
                output.cameraMode(DriveCameraMode.ADAPTIVE)
            }

            for (manual in listOf(false, true)) for (stale in listOf(false, true))
                for (lostArea in listOf(Rect(), Rect(500, 0, 640, 360), Rect(160, 330, 640, 360))) {
                    awaitFollow(current) {
                        output.onVisibleAreaChanged(Rect(160, 0, 640, 400))
                        output.update(fresh)
                        output.recenter()
                    }
                    instrumentation.runOnMainSync {
                        if (manual) { output.panMode(true); output.onScroll(45f, 20f) }
                        if (stale) output.update(fresh.copy(gpsStale = true,
                            fix = NavigationFix(WeatherCoordinate(42.0, 30.0), now - 60_000, 5f)))
                        val held = nativeMap.cameraPosition
                        output.onVisibleAreaChanged(lostArea)
                        val flattened = nativeMap.cameraPosition
                        assertEquals(0.0, flattened.tilt, .01)
                        assertEquals(0.0, flattened.bearing, .01)
                        assertEquals(held.target!!.latitude, flattened.target!!.latitude, 1e-7)
                        assertEquals(held.target!!.longitude, flattened.target!!.longitude, 1e-7)
                        assertEquals(held.zoom, flattened.zoom, .01)
                        assertEquals("Area loss must preserve manual hold", !manual, controls.following)
                    }
                }
            awaitFollow(current) {
                output.onVisibleAreaChanged(Rect(160, 0, 640, 400))
                output.update(fresh.copy(route = null))
                output.recenter()
            }
            instrumentation.runOnMainSync {
                val beforeOverview = nativeMap.cameraPosition
                output.overview()
                assertTrue("A stale route-menu click must not suspend following", controls.following)
                assertEquals(beforeOverview, nativeMap.cameraPosition)
                output.update(fresh)
                output.overview()
                assertFalse("An available route overview suspends following", controls.following)
                output.recenter()
            }
            val (replacement, replacementMarker) = hostFrame(800, 480, 320)
            SystemClock.sleep(1_050)
            instrumentation.runOnMainSync {
                output.update(fresh.copy(fix = NavigationFix(turnFix, now + 12_000L, 5f)))
                val oldAnimator = ownedAnimator()
                assertTrue("Replacement must interrupt an owned native ease", activeEase(oldAnimator) != null)
                output.onSurfaceAvailable(replacement)
                assertNull("Replacing the host surface must release the old ease", activeEase(oldAnimator))
                output.onSurfaceDestroyed(first) // Late destruction must not tear down replacement.
                output.update(recording)
                renderer!!.onStableAreaChanged(Rect(200, 0, 800, 440))
                renderer!!.onVisibleAreaChanged(Rect(200, 0, 800, 480))
                renderer!!.setActive(false)
                renderer!!.setActive(true)
                renderer!!.nightChanged()
            }
            val replacementReady = replacementMarker.await(20, TimeUnit.SECONDS)
            assertTrue("Replacement surface layers/attribution incomplete: ${lastFrameCounts.get()}\n${if (!replacementReady) viewDiagnostics() else ""}", replacementReady)
            assertNull("Replacement rendered PNG capture failed", captureFailure.get())
            assertDeferredTrailAndAttribution(Rect(200, 0, 800, 440))
        } finally {
            instrumentation.runOnMainSync { renderer?.close() }
            readers.forEach { it.setOnImageAvailableListener(null, null) }
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }
            drained.await(2, TimeUnit.SECONDS)
            readers.forEach { it.close() }
            thread.quitSafely()
            thread.join(2_000)
        }
    }
}
