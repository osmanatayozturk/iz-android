package com.atay.iz.car

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.app.Presentation
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.car.app.SurfaceContainer
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atay.iz.IzApplication
import com.atay.iz.data.Journey
import com.atay.iz.data.TrackPoint
import com.atay.iz.data.Transport
import com.atay.iz.navigation.NavigationFix
import com.atay.iz.navigation.NavigationState
import com.atay.iz.weather.WeatherCoordinate
import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.RouteVertex
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** Isolated real GL/View -> VirtualDisplay -> host Surface test. It does not impersonate DHU. */
@RunWith(AndroidJUnit4::class)
class CarMapSurfaceTest {
    @Test fun mapMarkerReachesHostSurfaceAndSurvivesReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<IzApplication>()
        val thread = HandlerThread("car-surface-test").apply { start() }
        val handler = Handler(thread.looper)
        val readers = mutableListOf<ImageReader>()
        val captureFailure = AtomicReference<Throwable?>(null)
        val lastFrameCounts = AtomicReference("")
        var renderer: CarMapSurface? = null
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
            fun hostFrame(width: Int, height: Int): Pair<SurfaceContainer, CountDownLatch> {
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                readers.add(reader)
                val marker = CountDownLatch(1)
                reader.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes.first()
                        val buffer = plane.buffer
                        var markerPixels = 0
                        var routePixels = 0
                        var trailPixels = 0
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
                                if (red in 220..255 && green in 135..180 && blue in 15..65) trailPixels++
                                if (x > image.width - 300 && y in image.height - 90 until image.height - 40) {
                                    val dayAttribution = red > 210 && green > 210 && blue > 210
                                    val nightAttribution = red in 23..38 && green in 27..43 && blue in 33..52 && blue > red + 3
                                    if (dayAttribution || nightAttribution) attributionPixels++
                                }
                            }
                        }
                        lastFrameCounts.set("${image.width}: marker=$markerPixels route=$routePixels trail=$trailPixels attribution=$attributionPixels")
                        if (markerPixels >= 8 && routePixels >= 20 && trailPixels >= 15 && attributionPixels >= 30 && marker.count > 0) {
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
                return SurfaceContainer(reader.surface, width, height, 160) to marker
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
                    RouteVertex(destination, 60.0)), 370.0, 60.0, now, transport = Transport.CAR)
            val trail = listOf(
                TrackPoint(journeyId = journey.id, latitude = 40.999, longitude = 28.9985, recordedAt = now - 60_000, accuracy = 5f),
                TrackPoint(journeyId = journey.id, latitude = 40.9998, longitude = 28.9994, recordedAt = now - 30_000, accuracy = 5f),
                TrackPoint(journeyId = journey.id, latitude = current.latitude, longitude = current.longitude, recordedAt = now, accuracy = 5f))
            instrumentation.runOnMainSync {
                renderer = CarMapSurface(TestCarContext.createCarContext(app)).also {
                    it.update(NavigationState(journey = journey, recording = true, route = route,
                        gpsStale = false, fix = NavigationFix(current, now, 5f)), trail)
                    it.onSurfaceAvailable(first)
                    it.onStableAreaChanged(Rect(160, 0, 640, 360))
                    it.onVisibleAreaChanged(Rect(160, 0, 640, 400))
                }
            }
            val firstReady = firstMarker.await(20, TimeUnit.SECONDS)
            assertTrue("First surface layers/attribution incomplete: ${lastFrameCounts.get()}\n${if (!firstReady) viewDiagnostics() else ""}", firstReady)
            assertNull("First rendered PNG capture failed", captureFailure.get())
            val (replacement, replacementMarker) = hostFrame(800, 480)
            instrumentation.runOnMainSync {
                renderer!!.onSurfaceAvailable(replacement)
                renderer!!.onSurfaceDestroyed(first) // Late destruction must not tear down replacement.
                renderer!!.onStableAreaChanged(Rect(200, 0, 800, 440))
                renderer!!.onVisibleAreaChanged(Rect(200, 0, 800, 480))
                renderer!!.setActive(false)
                renderer!!.setActive(true)
                renderer!!.nightChanged()
            }
            val replacementReady = replacementMarker.await(20, TimeUnit.SECONDS)
            assertTrue("Replacement surface layers/attribution incomplete: ${lastFrameCounts.get()}\n${if (!replacementReady) viewDiagnostics() else ""}", replacementReady)
            assertNull("Replacement rendered PNG capture failed", captureFailure.get())
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
