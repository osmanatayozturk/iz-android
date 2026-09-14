package org.iz.navigation.ui

import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.PlaceOrderRules
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.integration.OsmServiceSettings
import org.iz.navigation.navigation.NavigationStopSnapshot
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Private to ApprovedPhoneUsabilityAcceptanceTest; never clears or restores an application DB. */
internal class ApprovedPhoneUsabilityFixture(val app: IzApplication) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val namespace = "izqa-usability-${UUID.randomUUID()}"
    val query = namespace.lowercase(Locale.ROOT)
    val repository = DiaryRepository(app)
    private val firstId = (UUID.randomUUID().mostSignificantBits and 0x0000FFFFFFFFFFFEL) + 1
    val first = SelectedOsmPlace(
        "$namespace Atlas", 0.001, 0.002, OsmRef(OsmType.WAY, firstId),
        displayName = "$namespace Atlas, Sentetik Deneme Alanı, Kuzey Giriş",
    )
    val selected = SelectedOsmPlace(
        first.name, 0.003, 0.004, OsmRef(OsmType.WAY, firstId + 1),
        displayName = "$namespace Atlas, Sentetik Deneme Alanı, Güney Giriş",
    )
    val origin = RouteStop("$namespace başlangıç", WeatherCoordinate(0.0, 0.0))
    val via = RouteStop("$namespace ara durak", WeatherCoordinate(0.001, 0.001))
    val previousDestination = RouteStop("$namespace önceki hedef", WeatherCoordinate(0.006, 0.006))
    val selectedStop = RouteStop(selected.name, WeatherCoordinate(selected.latitude, selected.longitude))
    val visitNote = "$namespace private synthetic visit"
    val evidenceDirectory: File by lazy { File(app.noBackupFilesDir, "qa-approved-usability/$namespace") }
    private var before: DiarySnapshot? = null
    private var searchLimits: Map<String, Long>? = null
    private val ownedCacheFiles = linkedMapOf<File, String>()
    private var ownedSimulationId: String? = null
    private var enabledSimulation = false
    private var originalMuted = false
    private var pairedChargingContext: String? = null

    fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        // A broad suite must not launch this stateful acceptance class on an unallocated device.
        assumeTrue("Explicit isolated acceptance opt-in required",
            args.getString("izApprovedAcceptance") == "isolated-offline")
        check(args.getString("izCandidateCommit").orEmpty().matches(Regex("[0-9a-f]{40}"))) {
            "Pass the allocated final source commit as izCandidateCommit"
        }
        check(BuildConfig.DEBUG) { "Only a debug test candidate is supported" }
        val expectedAvd = args.getString("izExpectedAvd").orEmpty()
        check(expectedAvd.isNotBlank()) { "Pass the allocated isolated AVD as izExpectedAvd" }
        check(shell("getprop ro.boot.qemu.avd_name") == expectedAvd) {
            "Wrong AVD; do not create, reset or replace it"
        }
        requireOffline()
        check(!checkNotNull(app.getSystemService(KeyguardManager::class.java)).isKeyguardLocked) {
            "Allocated emulator is locked; test will not unlock it"
        }
        check(!TrackerSettings(app).enabled && !TrackingService.isRunning) {
            "Existing detection/recording must be preserved; no automatic shutdown"
        }
        val snapshot = io { repository.snapshot() }
        check(snapshot.journeys.none { it.endedAt == null || it.status == JourneyStatus.TEMPORARY }) {
            "Existing active/temporary diary data blocks MainActivity recovery safely"
        }
        check(snapshot.places.none { it.osmType == OsmType.WAY && it.osmId in setOf(firstId, firstId + 1) }) {
            "Synthetic OSM identity collision"
        }
        check(snapshot.places.all { it.sortOrder < PlaceOrderRules.MAX_ORDER }) {
            "Adding a place would renumber pre-existing places"
        }
        val nav = app.navigation.state.value
        check(nav.sessionId == null && nav.journey == null && nav.route == null &&
            !nav.recording && !nav.guidance && !nav.sharingLocation && !nav.simulation &&
            nav.trackFollow == null) { "Existing navigation session must not be replaced" }
        originalMuted = nav.muted
        before = snapshot
        searchLimits = readSearchLimits()
        check(evidenceDirectory.mkdirs()) { "Evidence namespace must be new" }
    }

    fun seedSearch() {
        check(before != null) { "Run preservation guards before seeding" }
        val service = OsmServiceSettings(app).read().nominatim.toHttpUrl()
        val firstUrl = service.newBuilder().setQueryParameter("q", query)
            .setQueryParameter("format", "jsonv2").setQueryParameter("limit", "10")
            .setQueryParameter("addressdetails", "0").removeAllQueryParameters("exclude_place_ids").build()
        val exclusions = listOf(first, selected).map { requireNotNull(it.osmRef) }
            .map { it.type.name.first().toString() + it.id }.sorted().joinToString(",")
        val secondUrl = firstUrl.newBuilder().setQueryParameter("exclude_place_ids", exclusions).build()
        val json = JSONArray().put(resultJson(first)).put(resultJson(selected)).toString()
        // Both pages are present: a default client miss is a failure, never a reason to enable internet.
        writeOwnedCache(searchCacheKey(firstUrl), json)
        writeOwnedCache(searchCacheKey(secondUrl), "[]")
    }

    // Match NominatimSearch.page, including OkHttp's remove-and-append ordering of q.
    private fun searchCacheKey(url: HttpUrl): String {
        val cacheUrl = url.newBuilder()
            .setQueryParameter("q", requireNotNull(url.queryParameter("q")).lowercase(Locale.ROOT)).build()
        return "search-v2:accept-language=tr:$cacheUrl"
    }

    private fun resultJson(place: SelectedOsmPlace) = JSONObject()
        .put("name", place.name).put("display_name", place.displayName)
        .put("lat", place.latitude.toString()).put("lon", place.longitude.toString())
        .put("osm_type", "way").put("osm_id", requireNotNull(place.osmRef).id)

    private fun writeOwnedCache(key: String, json: String) {
        val directory = File(app.cacheDir, "osm_place_queries")
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, sha256(key.toByteArray(Charsets.UTF_8)))
        val bytes = json.toByteArray(Charsets.UTF_8)
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        ownedCacheFiles[file] = sha256(bytes)
        check(file.setLastModified(System.currentTimeMillis()))
    }

    fun requireOffline() {
        check(Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
            "Airplane mode must already be enabled on the allocated emulator"
        }
        check(checkNotNull(app.getSystemService(ConnectivityManager::class.java)).allNetworks.isEmpty()) {
            "A network is still present; test will not alter device networking"
        }
    }

    fun assertSearchStayedCached() {
        check(readSearchLimits() == searchLimits) { "Default search attempted a network request/cache miss" }
        requireOffline()
    }

    private fun readSearchLimits(): Map<String, Long> {
        val prefs = app.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE)
        return listOf("nominatim_last_request", "nominatim_cooldown").associateWith { prefs.getLong(it, 0L) }
    }

    fun startSimulation() {
        check(ownedSimulationId == null)
        val route = PlannedRoute(
            id = "$namespace-route", stops = listOf(origin, previousDestination),
            vertices = listOf(RouteVertex(origin.coordinate, 0.0), RouteVertex(previousDestination.coordinate, 3600.0)),
            distanceMeters = 950.0, durationSeconds = 3600.0, createdAt = System.currentTimeMillis(),
            maneuvers = listOf(RouteManeuver(1, "$namespace düz ilerle", "", emptyList(), 0, 1, 0.0, 3600.0)),
        )
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                check(app.navigation.state.value.sessionId == null)
                app.navigation.setMuted(true)
                app.navigation.enableSimulation()
                enabledSimulation = true
                ownedSimulationId = app.navigation.startGuidance(route, recordJourney = false)
            }
        }
        check(app.navigation.state.value.simulation) { "Fixture must never become a real recording" }
    }

    fun stopOwnedGuidance(): Boolean = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val state = app.navigation.state.value
            check(state.sessionId == ownedSimulationId && state.simulation)
            app.navigation.stopGuidance(requireNotNull(NavigationStopSnapshot.from(state)))
        }
    }

    data class ScreenObservation(val allInteractive: Boolean, val sawDisplayOff: Boolean)

    /** Only allocated-emulator settings; exact values restored even if the negative control blocks. */
    fun withPairedTimeoutControls(block: () -> Unit) {
        check(before != null)
        val originalTimeout = shell("settings get system screen_off_timeout")
        val originalStayAwake = shell("settings get global stay_on_while_plugged_in")
        check(originalTimeout.matches(Regex("[0-9]+")) && originalStayAwake.matches(Regex("[0-9]+"))) {
            "Unknown existing power settings; leave unchanged"
        }
        File(evidenceDirectory, "paired_power_before.json").writeText(powerFacts().toString(2))
        try {
            shell("settings put system screen_off_timeout 15000")
            shell("settings put global stay_on_while_plugged_in 0")
            check(shell("settings get system screen_off_timeout") == "15000")
            check(shell("settings get global stay_on_while_plugged_in") == "0")
            pairedChargingContext = powerFacts().getString("chargingContext")
            wakeAllocatedScreen()
            block()
        } finally {
            shell("settings put system screen_off_timeout $originalTimeout")
            shell("settings put global stay_on_while_plugged_in $originalStayAwake")
            shell("input keyevent KEYCODE_WAKEUP")
            File(evidenceDirectory, "paired_power_restored.json").writeText(powerFacts().toString(2))
            check(shell("settings get system screen_off_timeout") == originalTimeout)
            check(shell("settings get global stay_on_while_plugged_in") == originalStayAwake)
        }
    }

    fun wakeAllocatedScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!checkNotNull(app.getSystemService(PowerManager::class.java)).isInteractive &&
            SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        check(checkNotNull(app.getSystemService(PowerManager::class.java)).isInteractive)
        check(!checkNotNull(app.getSystemService(KeyguardManager::class.java)).isKeyguardLocked) {
            "Emulator locked; no automatic credential or keyguard dismissal"
        }
    }

    /** A shell KEYCODE_SLEEP proves emulator input/system behavior, not a physical power button. */
    fun sleepAllocatedScreen() { shell("input keyevent KEYCODE_SLEEP") }

    fun powerFacts(): JSONObject {
        val power = shell("dumpsys power")
        val relevant = Regex("Wakefulness|WakeLock|Wake Locks|wake lock|SCREEN_|FULL_WAKE|PARTIAL_WAKE|mStayOn|mIsPowered|mPlugType|mUserActivitySummary|Display Power|mScreenOffTimeout|mUserActivityTimeoutOverride")
        return JSONObject().put("timeMillis", System.currentTimeMillis())
            .put("timeout", shell("settings get system screen_off_timeout"))
            .put("stayAwake", shell("settings get global stay_on_while_plugged_in"))
            .put("chargingContext", power.lineSequence().filter { it.contains("mIsPowered=") || it.contains("mPlugType=") }.joinToString(";"))
            .put("powerEvidence", power.lineSequence().filter { relevant.containsMatchIn(it) }.take(120).joinToString("\n"))
            .put("windowEvidence", shell("dumpsys window windows").lineSequence()
                .filter { it.contains("mHoldScreen") || it.contains("mCurrentFocus") }.take(20).joinToString("\n"))
    }

    /** No Espresso, Compose input, screenshots or wake requests during this passive interval. */
    fun observeScreen(label: String, durationMillis: Long = 25_000): ScreenObservation {
        check(label.matches(Regex("[a-zA-Z0-9_-]+")))
        val power = checkNotNull(app.getSystemService(PowerManager::class.java))
        val display = checkNotNull(app.getSystemService(DisplayManager::class.java))
        val startFacts = powerFacts()
        val started = SystemClock.elapsedRealtime()
        val samples = JSONArray()
        var allInteractive = true
        var sawDisplayOff = false
        do {
            val interactive = power.isInteractive
            val displayState = display.getDisplay(Display.DEFAULT_DISPLAY)?.state
            allInteractive = allInteractive && interactive && displayState == Display.STATE_ON
            sawDisplayOff = sawDisplayOff || (!interactive && displayState == Display.STATE_OFF)
            samples.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("interactive", interactive).put("displayState", displayState))
            if (SystemClock.elapsedRealtime() - started >= durationMillis) break
            Thread.sleep(500)
        } while (true)
        val endFacts = powerFacts()
        File(evidenceDirectory, "$label-power.json").writeText(JSONObject()
            .put("before", startFacts).put("after", endFacts).put("samples", samples)
            .put("allInteractive", allInteractive).put("sawDisplayOff", sawDisplayOff).toString(2))
        for (facts in listOf(startFacts, endFacts)) {
            check(facts.getString("timeout") == "15000" && facts.getString("stayAwake") == "0")
            check(facts.getString("chargingContext").isNotBlank() &&
                facts.getString("chargingContext") == pairedChargingContext) {
                "Charging conditions changed; paired screen experiment is invalid"
            }
        }
        return ScreenObservation(allInteractive, sawDisplayOff)
    }

    fun capture(label: String, facts: JSONObject = JSONObject()) {
        check(label.matches(Regex("[a-zA-Z0-9_-]+")))
        if (before == null) return
        facts.put("namespace", namespace)
            .put("sourceCommitArgument", InstrumentationRegistry.getArguments().getString("izCandidateCommit"))
            .put("label", label).put("timeMillis", System.currentTimeMillis())
            .put("simulationIsNotRecordingEvidence", true)
        File(evidenceDirectory, "$label.json").writeText(facts.toString(2))
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No screenshot evidence" }
        try {
            File(evidenceDirectory, "$label.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root == null) {
            File(evidenceDirectory, "$label-ui-unavailable.txt").writeText("No active accessibility root; not UI tree evidence.")
            return
        }
        try {
            File(evidenceDirectory, "$label-ui.xml").outputStream().use { output ->
                val serializer = Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true) }
                var visited = 0
                fun writeNode(node: AccessibilityNodeInfo, depth: Int) {
                    if (++visited > 400 || depth > 24) return
                    val bounds = Rect().also(node::getBoundsInScreen)
                    serializer.startTag(null, "node")
                    mapOf("package" to node.packageName, "class" to node.className, "text" to node.text,
                        "description" to node.contentDescription, "resourceId" to node.viewIdResourceName,
                        "bounds" to bounds.flattenToString(), "clickable" to node.isClickable.toString(),
                        "focused" to node.isFocused.toString(), "scrollable" to node.isScrollable.toString()
                    ).forEach { (key, value) -> serializer.attribute(null, key, value?.toString().orEmpty()) }
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { writeNode(child, depth + 1) } finally { child.recycle() }
                    }
                    serializer.endTag(null, "node")
                }
                writeNode(root, 0)
                serializer.endDocument()
            }
        } finally { root.recycle() }
    }

    /** Only new namespaced rows may be removed. On unexpected ownership, fail and leave evidence/data intact. */
    fun close() {
        val baseline = before ?: return
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                val state = app.navigation.state.value
                val owned = ownedSimulationId
                if (owned != null && state.sessionId == owned) {
                    check(state.simulation && (state.route?.id?.startsWith(namespace) != false))
                    check(app.navigation.finishSession(requireNotNull(NavigationStopSnapshot.from(state))))
                } else check(state.sessionId == null) { "Foreign session appeared; leave it intact" }
                if (enabledSimulation) app.navigation.stopSimulation()
                if (enabledSimulation) app.navigation.setMuted(originalMuted)
            }
        }
        io {
            val current = repository.snapshot()
            val newPlaces = current.places.filter { place ->
                baseline.places.none { it.id == place.id } && place.name == selected.name &&
                    place.osmType == OsmType.WAY && place.osmId == selected.osmRef?.id &&
                    place.latitude == selected.latitude && place.longitude == selected.longitude
            }
            for (place in newPlaces) {
                val visits = current.visits.filter { it.placeId == place.id }
                check(visits.all { it.note == visitNote && baseline.visits.none { old -> old.id == it.id } })
                check(current.photos.none { photo -> visits.any { it.id == photo.visitId } })
                check(current.drafts.none { draft -> visits.any { it.id == draft.visitId } })
                check(current.contributions.none { it.placeId == place.id })
                // This guard above rejects unexpected ownership; repository cascades only this new place's visits.
                repository.deletePlace(place.id)
            }
            check(repository.snapshot() == baseline) { "Pre-existing diary content changed; no restore attempted" }
        }
        for ((file, hash) in ownedCacheFiles) {
            check(file.isFile && sha256(file.readBytes()) == hash) { "Owned cache changed; leave it for investigation" }
            check(file.delete()) { "Could not remove owned cache file" }
        }
        assertSearchStayedCached()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { withTimeout(10_000) { block() } }
}
