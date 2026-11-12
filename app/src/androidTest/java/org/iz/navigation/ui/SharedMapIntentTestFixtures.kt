package org.iz.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.os.SystemClock
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real MainActivity/DiaryApp host. It does not replace content, the receiver, navigation or tracking.
 * Only a separately allocated empty offline QA pair may opt in. Failure never resets app/session data.
 */
internal class SharedMapIntentTestFixture(private val compose: ComposeTestRule) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as IzApplication
    private val repository: DiaryRepository
    private val observedStates = ConcurrentLinkedQueue<NavigationState>()
    private val observation: CoroutineScope
    private val fixtureToken = UUID.randomUUID().toString()
    private val tokenExtra = "org.iz.navigation.test.sharedMapFixtureToken"
    private val component = ComponentName(context, MainActivity::class.java)
    private val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    private val lifecycleTimeoutMs = 10_000L
    private var launched = false
    // The following ownership fields are accessed only on main.
    private var registered = false
    private var owned: MainActivity? = null
    private var ownedStage: Stage? = null
    private var ownedTask: Int? = null
    private var pending: Replacement? = null
    private var closing: MainActivity? = null
    private var closeDestroyed: CountDownLatch? = null
    @Volatile private var lifecycleFailure: AssertionError? = null

    private class Replacement(val previous: MainActivity?) {
        val destroyed = CountDownLatch(if (previous == null) 0 else 1)
        val resumed = CountDownLatch(1)
        var created: MainActivity? = null // Main thread only.
    }

    private fun failLifecycle(message: String) {
        if (lifecycleFailure == null) lifecycleFailure = AssertionError(message)
        pending?.destroyed?.countDown()
        pending?.resumed?.countDown()
        closeDestroyed?.countDown()
    }

    private val lifecycle = ActivityLifecycleCallback { activity: Activity, stage: Stage ->
        if (activity is MainActivity && activity.componentName == component &&
            activity.application === app && activity.applicationInfo.uid == context.applicationInfo.uid) {
            val request = pending
            val known = activity === owned || activity === request?.previous ||
                activity === request?.created || activity === closing
            if (activity.intent.getStringExtra(tokenExtra) != fixtureToken) {
                if (known) failLifecycle("Owned Activity lost its fixture token")
            } else if (stage == Stage.DESTROYED) {
                // A finished Activity may no longer have a live task; trust only known instances.
                if (!known) {
                    failLifecycle("Unrecognized fixture Activity destruction")
                } else {
                    if (activity === request?.previous) request?.destroyed?.countDown()
                    if (activity === closing) closeDestroyed?.countDown()
                    if (activity === owned) { owned = null; ownedStage = null }
                }
            } else if (ownedTask != null && activity.taskId != ownedTask) {
                failLifecycle("Fixture token appeared in an unexpected task")
            } else when (stage) {
                Stage.CREATED -> {
                    if (request == null || request.created != null || activity === request.previous ||
                        request.destroyed.count != 0L || owned != null) {
                        failLifecycle("Unexpected or ambiguous owned Activity creation")
                    } else {
                        ownedTask = activity.taskId
                        request.created = activity
                        owned = activity
                        ownedStage = stage
                    }
                }
                else -> if (activity === owned) {
                    ownedStage = stage
                    if (stage == Stage.RESUMED && activity === request?.created) request?.resumed?.countDown()
                }
            }
        }
    }

    // Re-throw action failures on the test thread, not the application main thread.
    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { action() } catch (caught: Throwable) { failure = caught }
        }
        failure?.let { throw it }
    }

    private fun awaitLifecycle(latch: CountDownLatch, deadline: Long, phase: String) {
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        check(latch.await(remaining, TimeUnit.MILLISECONDS)) { "Owned Activity timeout: $phase" }
        lifecycleFailure?.let { throw it }
    }

    private fun currentOwned(): MainActivity {
        lifecycleFailure?.let { throw it }
        return checkNotNull(owned) { "No current owned Activity" }.also {
            check(it.componentName == component && it.application === app &&
                it.applicationInfo.uid == context.applicationInfo.uid && it.taskId == ownedTask &&
                it.intent.getStringExtra(tokenExtra) == fixtureToken && !it.isDestroyed)
        }
    }

    init {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Root must allocate this isolated lifecycle acceptance explicitly",
            arguments.getString("izSharedMapIntentAcceptance") == "isolated-offline")
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID != "org.iz.navigation")
        check(arguments.getString("izExpectedPackage") == context.packageName &&
            context.packageName == BuildConfig.APPLICATION_ID)
        check(context.packageName != "org.iz.navigation.batterycheck" &&
            context.packageName != "org.iz.navigation.batterycheck.test") {
            "Use a separately allocated QA pair, not either protected batterycheck package"
        }
        check(instrumentation.context.packageName == context.packageName + ".test")
        check(arguments.getString("izCandidateCommit").orEmpty().matches(Regex("[0-9a-f]{40}")))
        check(BuildConfig.OSM_CLIENT_ID.isEmpty() && BuildConfig.GROUP_SUPABASE_URL.isEmpty() &&
            BuildConfig.GROUP_SUPABASE_KEY.isEmpty())
        for (packageName in listOf(context.packageName, instrumentation.context.packageName)) {
            check(context.packageManager.checkPermission(Manifest.permission.INTERNET, packageName) ==
                PackageManager.PERMISSION_DENIED) { "Both allocated APKs must omit INTERNET" }
        }
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        check(connectivity.allNetworks.none { network ->
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }) { "An allocated offline device is required; this test does not change connectivity" }
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                "Location must already be denied; this test neither grants nor revokes permissions"
            }
        }
        check(!TrackerSettings(context).enabled && !TrackingService.isRunning)
        repository = DiaryRepository(context)
        check(runBlocking { repository.snapshot() } == DiarySnapshot()) {
            "A fresh empty synthetic QA database is required; no cleanup or seed mutation is performed"
        }
        assertIdle(app.navigation.state.value)
        observation = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        observation.launch(start = CoroutineStart.UNDISPATCHED) {
            app.navigation.state.collect { observedStates.add(it) }
        }
    }

    fun launchCold(text: String) = launch(share(text))

    fun launchIdle() = launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))

    private fun launch(intent: Intent) {
        check(!launched)
        launched = true
        val request = Replacement(null)
        onMain {
            pending = request
            monitor.addLifecycleCallback(lifecycle)
            registered = true
        }
        val actual = instrumentation.startActivitySync(Intent(intent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(tokenExtra, fixtureToken))
        awaitLifecycle(request.resumed, SystemClock.elapsedRealtime() + lifecycleTimeoutMs, "initial RESUMED")
        onMain {
            check(currentOwned() === actual && request.created === actual && ownedStage == Stage.RESUMED)
            pending = null
        }
        compose.waitForIdle()
        assertNoSessionEffect()
    }

    /** Platform Instrumentation invokes the real production override on the Activity main thread. */
    fun deliverWarm(vararg texts: String) {
        onMain {
            val activity = currentOwned()
            check(ownedStage == Stage.RESUMED && !activity.isFinishing)
            // All deliveries stay in this one main runnable, before the next composition.
            texts.forEach { text -> instrumentation.callActivityOnNewIntent(activity, share(text)) }
        }
        compose.waitForIdle()
        assertNoSessionEffect()
    }

    fun recreate() {
        lateinit var request: Replacement
        onMain {
            val previous = currentOwned()
            check(pending == null && ownedStage == Stage.RESUMED && !previous.isFinishing)
            request = Replacement(previous)
            pending = request
            previous.recreate()
        }
        val deadline = SystemClock.elapsedRealtime() + lifecycleTimeoutMs
        awaitLifecycle(request.destroyed, deadline, "old instance DESTROYED")
        awaitLifecycle(request.resumed, deadline, "replacement RESUMED")
        onMain {
            check(currentOwned() === request.created && owned !== request.previous && ownedStage == Stage.RESUMED)
            pending = null
        }
        compose.waitForIdle()
        assertNoSessionEffect()
    }

    fun assertNoSessionEffect() {
        assertFalse("Receiving a share must not start the real tracking service", TrackingService.isRunning)
        assertTrue("The real navigation state stream must be observed", observedStates.isNotEmpty())
        observedStates.forEach(::assertIdle)
        assertIdle(app.navigation.state.value)
        assertEquals("Receiving/dismissing/restoring a share must not create diary or recording data",
            DiarySnapshot(), runBlocking { repository.snapshot() })
    }

    private fun assertIdle(state: NavigationState) {
        assertTrue("Share receipt must not activate a route, session, recording or location acquisition",
            state.sessionId == null && state.journey == null && state.route == null &&
                !state.guidance && !state.recording && !state.recordingPending &&
                !state.simulation && !state.locationActive && !state.sharingLocation && !state.loading)
    }

    private fun share(text: String) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(tokenExtra, fixtureToken)

    override fun close() {
        try {
            // Finish only the latest exact owned instance; never guess or stop/reset a package.
            var destroyed: CountDownLatch? = null
            onMain {
                if (pending != null || lifecycleFailure != null) {
                    throw AssertionError("Owned lifecycle incomplete: pending=${pending != null}; " +
                        "priorFailure=${lifecycleFailure != null}; no finish or cleanup performed", lifecycleFailure)
                }
                val activity = owned
                if (activity != null) {
                    check(currentOwned() === activity)
                    destroyed = CountDownLatch(1)
                    closing = activity
                    closeDestroyed = destroyed
                    activity.finish()
                }
            }
            destroyed?.let { awaitLifecycle(it, SystemClock.elapsedRealtime() + lifecycleTimeoutMs, "close DESTROYED") }
            assertNoSessionEffect()
        } finally {
            try {
                onMain {
                    if (registered) { monitor.removeLifecycleCallback(lifecycle); registered = false }
                    owned = null; ownedStage = null; pending = null; closing = null; closeDestroyed = null
                }
            } finally { observation.cancel() }
        }
    }
}

