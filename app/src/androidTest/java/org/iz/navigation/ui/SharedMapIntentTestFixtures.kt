package org.iz.navigation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
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
import java.util.concurrent.ConcurrentLinkedQueue

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
    private var scenario: ActivityScenario<MainActivity>? = null

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
        check(scenario == null)
        scenario = ActivityScenario.launch<MainActivity>(intent)
        compose.waitForIdle()
        assertNoSessionEffect()
    }

    /** Platform Instrumentation invokes the real production override on the Activity main thread. */
    fun deliverWarm(vararg texts: String) {
        checkNotNull(scenario).onActivity { activity ->
            texts.forEach { text ->
                instrumentation.callActivityOnNewIntent(activity, share(text))
            }
        }
        compose.waitForIdle()
        assertNoSessionEffect()
    }

    fun recreate() {
        checkNotNull(scenario).recreate()
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

    override fun close() {
        try {
            // Close only this test-owned Activity; never stop/reset a service to hide an effect.
            scenario?.close()
            scenario = null
            assertNoSessionEffect()
        } finally {
            observation.cancel()
        }
    }
}

