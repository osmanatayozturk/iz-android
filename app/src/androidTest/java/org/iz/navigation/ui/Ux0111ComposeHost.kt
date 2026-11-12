package org.iz.navigation.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.health.HealthPrivacyActivity
import org.iz.navigation.tracking.TrackingService
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProvider
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpResponder
import org.maplibre.android.maps.MapView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Existing QA-only Activity rehost; no new manifest host or live map transport. Not native-render acceptance. */
internal class Ux0111ComposeHost(private val compose: ComposeTestRule) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<HealthPrivacyActivity>? = null
    private var original: ModuleProvider? = null
    private var transport: NoNetworkMaps? = null
    private val visible = mutableStateOf(true)

    fun setContent(content: @Composable () -> Unit) {
        if (scenario == null) {
            val context = instrumentation.targetContext
            check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID != "org.iz.navigation" &&
                BuildConfig.APPLICATION_ID in setOf("org.iz.navigation.batterycheck",
                    "org.iz.navigation.placeinfoqa20261112p01", "org.iz.navigation.placeinfoqa20261112p02") &&
                context.packageName == BuildConfig.APPLICATION_ID) { "Dedicated QA package only" }
            check(!TrackingService.isRunning) { "An allocated idle recorder is required" }
            check(!org.iz.navigation.tracking.TrackerSettings(context).enabled) { "QA automatic recording must already be disabled" }
            val nav = (context.applicationContext as IzApplication).navigation.state.value
            check(nav.sessionId == null && !nav.guidance && !nav.recording && !nav.locationActive) { "No real session may be changed" }
            instrumentation.runOnMainSync { original = MapLibre.getModuleProvider() }
            val isolated = NoNetworkMaps(checkNotNull(original))
            transport = isolated
            instrumentation.runOnMainSync { MapLibre.setModuleProvider(isolated) }
            scenario = ActivityScenario.launch(Intent(context, HealthPrivacyActivity::class.java))
        }
        checkNotNull(scenario).onActivity { activity ->
            check(activity.javaClass == HealthPrivacyActivity::class.java && activity.packageName == BuildConfig.APPLICATION_ID)
            check(activity.window.decorView.nativeMapCount() == 0) { "Do not replace an existing map scene" }
            activity.setContent { if (visible.value) content() }
        }
        compose.waitForIdle()
        transport?.assertHealthy()
    }

    override fun close() {
        if (scenario == null && original == null && transport == null) return
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
        val interrupted = Thread.interrupted()
        transport?.closing?.set(true)
        try {
            if (scenario != null) attempt { instrumentation.runOnMainSync { visible.value = false }; compose.waitForIdle() }
            attempt { scenario?.onActivity { check(it.window.decorView.nativeMapCount() == 0) { "Native map disposal is required" } } }
            attempt { transport?.drain() }
        } finally {
            try {
                attempt { original?.let { saved -> instrumentation.runOnMainSync {
                    MapLibre.setModuleProvider(saved)
                    check(MapLibre.getModuleProvider() === saved) { "Original SDK provider must be restored exactly" }
                } } }
            } finally { attempt { scenario?.close() }; scenario = null }
        }
        attempt { transport?.assertHealthy() }
        if (interrupted) Thread.currentThread().interrupt()
        if (failures.isNotEmpty()) throw AssertionError("QA host cleanup failed; no further maps", failures.first()).also { error ->
            failures.drop(1).forEach(error::addSuppressed)
        }
    }

    private fun View.nativeMapCount(): Int = when (this) {
        is MapView -> 1
        is ViewGroup -> (0 until childCount).sumOf { getChildAt(it).nativeMapCount() }
        else -> 0
    }

    /** A bounded failure-only map transport: header/dialog tests do not need raster pixels. */
    private class NoNetworkMaps(private val previous: ModuleProvider) : ModuleProvider {
        val closing = AtomicBoolean(false)
        private val gate = Any()
        private val callbacks = AtomicInteger()
        private val fatal = AtomicReference<Throwable?>()
        private val pending = ConcurrentHashMap.newKeySet<FutureTask<Unit>>()
        private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
            { runnable -> Thread(runnable, "iz-ux-test-map").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
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
                check(future.compareAndSet(null, task)) { "Map request must execute once" }
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
            check(worker.awaitTermination(5, TimeUnit.SECONDS) && callbacks.get() == 0 && pending.isEmpty()) { "Map transport must drain before provider restoration" }
        }
    }
}