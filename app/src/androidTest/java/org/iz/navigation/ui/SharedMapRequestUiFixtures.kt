package org.iz.navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.iz.navigation.BuildConfig
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.util.concurrent.atomic.AtomicInteger

/** Pure in-memory platform adapter; the controller and QuickDirectionsDialog remain production code. */
internal class SharedMapRequestUiFixture : AutoCloseable {
    companion object { const val NOW = 1000L }
    val runtime = SharedMapRequestUiRuntime()
    private val job: Job
    val controller: JourneyNavigationCoordinator
    val target = RouteStop("Synthetic shared target", WeatherCoordinate(40.01, 29.0))
    val onStarted = AtomicInteger()

    init {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Root must opt into this isolated synthetic integration",
            arguments.getString("izSharedMapRequestAcceptance") == "isolated-offline")
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID != "org.iz.navigation" &&
            BuildConfig.APPLICATION_ID != "org.iz.navigation.batterycheck")
        check(arguments.getString("izExpectedPackage") == targetContext.packageName &&
            targetContext.packageName == BuildConfig.APPLICATION_ID)
        check(arguments.getString("izCandidateCommit").orEmpty().matches(Regex("[0-9a-f]{40}")))
        val testContext = InstrumentationRegistry.getInstrumentation().context
        check(testContext.packageName == targetContext.packageName + ".test")
        for (name in listOf(targetContext.packageName, testContext.packageName)) {
            check(targetContext.packageManager.checkPermission(Manifest.permission.INTERNET, name) ==
                PackageManager.PERMISSION_DENIED)
        }
        job = SupervisorJob()
        controller = JourneyNavigationCoordinator(runtime, null, { NOW },
            CoroutineScope(job + Dispatchers.Main.immediate))
    }

    fun route(origin: WeatherCoordinate = runtime.fix.coordinate, destination: RouteStop = target,
        mode: Transport = Transport.CAR) = PlannedRoute("synthetic-shared-route",
        listOf(RouteStop("Synthetic origin", origin), destination),
        listOf(RouteVertex(origin, 0.0), RouteVertex(destination.coordinate, 100.0)),
        1110.0, 100.0, NOW, listOf(0.0, 100.0), mode,
        maneuvers = listOf(RouteManeuver(1, "Synthetic instruction", "Synthetic instruction",
            emptyList(), 0, 1, 0.0, 100.0)))

    fun seedRecording(): String = runBlocking { controller.startFreeDrive(Transport.CAR) }

    fun replaceRecording(): String = runBlocking {
        controller.finishSession(checkNotNull(controller.state.value.sessionId))
        controller.startFreeDrive(Transport.CAR)
    }

    override fun close() { runBlocking { job.cancelAndJoin() } }
}

internal class SharedMapRequestUiRuntime : NavigationRuntime {
    override val journeys = MutableStateFlow<List<Journey>>(emptyList())
    val fix = NavigationFix(WeatherCoordinate(40.0, 29.0), SharedMapRequestUiFixture.NOW, 5f)
    var started = 0
    var finished = 0
    private var running: String? = null
    override suspend fun activeJourney() = journeys.value.singleOrNull { it.endedAt == null }
    override suspend fun start(transport: Transport, stillCurrent: () -> Boolean): String {
        check(stillCurrent() && activeJourney() == null)
        val journey = Journey(id = "shared-ui-record-${++started}", transport = transport,
            startedAt = SharedMapRequestUiFixture.NOW)
        journeys.value = journeys.value + journey
        running = journey.id
        return journey.id
    }
    override suspend fun confirm(id: String, transport: Transport) =
        error("Only confirmed synthetic records are used")
    override suspend fun finish(id: String) {
        check(id == running)
        journeys.value = journeys.value.map {
            if (it.id == id) it.copy(endedAt = SharedMapRequestUiFixture.NOW + 1000) else it
        }
        running = null
        finished++
    }
    override suspend fun interrupt(id: String) = error("A shared request must not interrupt a record")
    override fun recordingId() = running
    override fun setHighFrequency(journeyId: String?) = Unit
    override suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute =
        error("Injected synthetic route only; no provider")
    override suspend fun locate() = error("No device GPS")
    override fun hasPreciseLocation() = true
    override fun canLocateQuickly() = true
    override suspend fun cachedQuickLocation(precise: Boolean) = fix
    override suspend fun locateApproximate() = error("The synthetic fresh fix must be reused")
    override fun render(state: NavigationState) = Unit
    override fun speak(text: String) = Unit
    override fun cancelSpeech() = Unit
}

