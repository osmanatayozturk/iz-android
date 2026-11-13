package org.iz.navigation.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditFieldAction
import org.iz.navigation.data.MapEditStage
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.tracking.TrackingService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
        assertTrue("Allocated emulator required; never the user phone", Build.HARDWARE in setOf("ranchu", "goldfish"))
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
    private class NoNetworkMaps(private val previous: ModuleProvider) : ModuleProvider {
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
