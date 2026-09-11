package org.iz.navigation.watch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.tiles.renderer.TileRenderer
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import com.google.common.util.concurrent.ListenableFuture
import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercises the real ProtoLayout Android renderer, not a screenshot-shaped Compose mock. */
class WatchTileRenderTest {
    private val now = 1_000_000L
    @Test fun providersExposeProtectedTileAndTextAndIconComplications() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val complication = manager.getServiceInfo(android.content.ComponentName(context, WatchComplicationService::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals("com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER", complication.permission)
        assertEquals("SHORT_TEXT,LONG_TEXT,ICON", complication.metaData.getString("android.support.wearable.complications.SUPPORTED_TYPES"))
        val tile = manager.getServiceInfo(android.content.ComponentName(context, WatchTileService::class.java), 0)
        assertEquals("com.google.android.wearable.permission.BIND_TILE_PROVIDER", tile.permission)
    }
    @Test fun renderNavigationRecordingAndDailyOnSmallAndLargeRounds() {
        val day = WearDailySummary("1970-01-01", "UTC", 0, 86_400_000, now, now,
            WearDailyJourneyTotal(8500.0, 1200000, 1), WearDailyJourneyTotal(1800.0, 900000, 1),
            WearDailyJourneyTotal(3000.0, 600000, 1), 4200, 2500.0, 1500000,
            WearDailyHealthStatus.PARTIAL, WearDailyHealthStatus.AVAILABLE, WearDailyHealthStatus.AVAILABLE)
        val nav = WearNavigationSummary("n", true, now, false, false, false, false, false, 10,
            "Bir sonraki kavşakta sağa dön", 150.0, 2200.0, now + 600000, "Ev")
        val samples = linkedMapOf(
            "navigation" to WearSnapshot(now, navigation = nav),
            "recording" to WearSnapshot(now, journeyId = "j", recording = true, mode = WearMode.WALK, elapsedMillis = 420000, distanceMeters = 1250.0),
            "daily" to WearSnapshot(now, daily = day),
            "candidate" to WearSnapshot(now, journeyId = "c", recording = true, temporary = true, daily = day),
            "stale_gps" to WearSnapshot(now, navigation = nav.copy(fixAt = now - 31000)),
        )
        for (size in listOf(180, 227)) for ((name, snapshot) in samples) {
            val frame = WatchSurfacePolicy.frame(snapshot, true, now)
            render(frame, size, name)
        }
    }
    private fun render(frame: WatchSurfaceFrame, sizeDp: Int, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val pixels = (sizeDp * context.resources.displayMetrics.density).toInt()
        lateinit var host: FrameLayout
        lateinit var future: ListenableFuture<View>
        instrumentation.runOnMainSync {
            host = FrameLayout(context)
            val renderer = TileRenderer(context, ContextCompat.getMainExecutor(context)) { }
            val root = WatchTileLayout.build(instrumentation.targetContext.packageName, frame, sizeDp) as LayoutElementBuilders.Box
            val open = root.modifiers!!.clickable!!.onClick as ActionBuilders.LaunchAction
            assertEquals("org.iz.navigation", open.androidActivity!!.packageName)
            assertEquals(WatchActivity::class.java.name, open.androidActivity!!.className)
            future = renderer.inflateAsync(LayoutElementBuilders.Layout.Builder().setRoot(root).build(),
                ResourceBuilders.Resources.Builder().setVersion("1").build(), host)
        }
        assertNotNull(future.get(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            val spec = View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY)
            host.measure(spec, spec); host.layout(0, 0, pixels, pixels)
            fun texts(view: View): List<TextView> = when (view) {
                is TextView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else -> emptyList()
            }
            assertTrue(texts(host).any { it.text.toString().contains(frame.title) })
            assertTrue("Rendered Tile must have clickable content", host.childCount > 0)
            val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.clipPath(android.graphics.Path().apply { addCircle(pixels / 2f, pixels / 2f, pixels / 2f, android.graphics.Path.Direction.CW) })
            host.draw(canvas)
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "surface-fixtures").apply { mkdirs() }
            File(folder, "tile_${name}_${sizeDp}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
