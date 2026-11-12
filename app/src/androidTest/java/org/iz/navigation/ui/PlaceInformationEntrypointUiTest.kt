package org.iz.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.integration.LocalOsmMapPresentation
import org.iz.navigation.integration.OsmMapPresentation
import org.iz.navigation.integration.OsmServiceSettings
import org.iz.navigation.integration.parseOverpassPlaces
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.placeinfo.*
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Actual DiaryApp/default ViewModel/Room entries, with synthetic disk data and offline native style.
 * Run AFTER the four captures, then case01 before case02, with a new Root-sealed entrypoint UUID.
 * No MainActivity launcher, real service/GPS, route start, upload or screenshot acceptance is implied.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlaceInformationEntrypointUiTest {
    @get:Rule val compose = createEmptyComposeRule() // Admission must precede Activity/own seeding.
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val publicDirectory get() = File(context.cacheDir.canonicalFile, "public-place-info")
    private val publicFile get() = File(publicDirectory, "places-v1.json")
    private val publicTemporary get() = File(publicDirectory, "places-v1.tmp")
    private val native = AtomicReference<MapLibreMap?>()

    @Test fun case01NativeMapTapOpensDiaryAppPublicCard() = runCase(0)
    @Test fun case02SavedPlaceRowOpensDiaryAppPublicAndPersonalDetail() = runCase(1)

    private fun runCase(index: Int) {
        val receipt = admission()
        val directory = namespace(receipt, index)
        val output = File(directory, "${CASES[index]}.json")
        requirePublicStore(index, directory)
        val uuid = receipt.getString("runUuid")
        val identity = UUID.nameUUIDFromBytes("placeinfo-entry:$uuid:$index".toByteArray(Charsets.UTF_8))
        val ref = OsmRef(OsmType.NODE, (identity.mostSignificantBits and 0x0000FFFFFFFFFFFFL) + 1)
        val key = PlaceInfoKey(PlaceInfoServices.DEFAULT_ENDPOINT, ref)
        val publicName = "Kamusal QA ${identity.toString().take(8)}"
        val privateName = "Kişisel QA ${identity.toString().take(8)}"
        val snapshot = PublicPlaceSnapshot(key, 7, linkedMapOf("name" to publicName,
            "opening_hours" to "24/7", "timezone" to "UTC", "wheelchair" to "limited"), System.currentTimeMillis())
        val place = Place(id = identity.toString(), name = privateName, osmType = ref.type, osmId = ref.id,
            source = PlaceSource.OSM) // Deliberately no coordinates: no second embedded DiaryMap.
        val repository = DiaryRepository(context)
        val cache = PublicPlaceCache(publicDirectory)
        val budget = context.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE)
            .getString("overpass_budget", null)
        val started = SystemClock.elapsedRealtime()
        receipt.put("case", CASES[index]).put("testStatus", "INCOMPLETE")
            .put("method", "${javaClass.name}#${METHODS[index]}")
            .put("ref", "node/${ref.id}").put("endpoint", key.endpoint).put("version", 7)
            .put("fetchedAtMillis", snapshot.fetchedAtMillis).put("publicName", publicName)
            .put("evidenceKind", "ACTUAL_DIARYAPP_ENTRY_SYNTHETIC_CACHE_OFFLINE_STYLE")
            .put("networkRequestCounter", "NOT_INSTRUMENTED; noINTERNET/offline/budget/cache guards")
            .put("screenshots", 0).put("bodyStatus", "INCOMPLETE").put("cleanupStatus", "NOT_ATTEMPTED")
        var scenario: ActivityScenario<ComponentActivity>? = null
        var nearby: File? = null
        var nearbyHash: String? = null
        var saved = false
        var publicSeeded = false
        var failure: Throwable? = null
        try {
            // Only our new key may exist in the actual default store throughout this case.
            check(runBlocking { cache.put(snapshot) })
            publicSeeded = true
            requirePublicSnapshot(snapshot)
            if (index == 1) {
                check(runBlocking { repository.snapshot() } == DiarySnapshot())
                runBlocking { repository.savePlace(place) }
                saved = true
                check(runBlocking { repository.snapshot() } == DiarySnapshot().copy(places = listOf(place)))
                receipt.put("ownedPlaceId", place.id).put("personalName", privateName).put("coordinates", "NONE")
            }
            val active = ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario = active
            val presentation = OsmMapPresentation(style = ::offlineStyle, onReady = { native.set(it) })
            active.onActivity { activity ->
                check(activity.packageName == QA_PACKAGE)
                activity.setContent {
                    CompositionLocalProvider(LocalOsmMapPresentation provides presentation) {
                        IzTheme { DiaryApp(null, {}) } // Normal default VM, repository and history; no router injection.
                    }
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("home-menu").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("place-information-card").assertDoesNotExist()
            if (index == 0) {
                compose.waitUntil(15_000) { native.get() != null &&
                    compose.onAllNodesWithTag("navigation-home-map").fetchSemanticsNodes().size == 1 }
                compose.waitForIdle()
                active.onActivity { activity ->
                    requireOwnedMap(activity)
                    checkNotNull(native.get()).moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.001, 0.002), 17.0))
                }
                var stable = 0
                compose.waitUntil(5_000) {
                    var correct = false
                    active.onActivity {
                        val camera = checkNotNull(native.get()).cameraPosition
                        correct = camera.zoom in 16.99..17.01 && camera.target?.let { target ->
                            abs(target.latitude - 0.001) < 0.0000001 && abs(target.longitude - 0.002) < 0.0000001
                        } == true
                    }
                    stable = if (correct) stable + 1 else 0
                    stable >= 3
                }
                val bounds = compose.onNodeWithTag("navigation-home-map").fetchSemanticsNode().boundsInRoot
                var tap = Offset.Zero
                var coordinate: LatLng? = null
                active.onActivity { activity ->
                    val view = requireOwnedMap(activity)
                    check(abs(bounds.width - view.width) <= 1f && abs(bounds.height - view.height) <= 1f)
                    val map = checkNotNull(native.get())
                    // Integer native-view pixels, away from rounding-cell borders and normal top/bottom controls.
                    val point = sequenceOf(.53f, .59f, .47f).flatMap { y ->
                        sequenceOf(.50f, .57f, .43f).map { x -> PointF((view.width * x).toInt().toFloat(),
                            (view.height * y).toInt().toFloat()) }
                    }.first { point ->
                        val value = map.projection.fromScreenLocation(point)
                        safeCell(value.latitude) && safeCell(value.longitude)
                    }
                    tap = Offset(point.x, point.y)
                    coordinate = map.projection.fromScreenLocation(point)
                    check(map.cameraPosition.zoom >= 16)
                }
                val location = checkNotNull(coordinate)
                val endpoint = OsmServiceSettings(context).read().overpass
                val cell = nearbyKey(endpoint, location)
                val nearbyDirectory = canonicalChild(context.cacheDir.canonicalFile, "osm_place_queries")
                check(nearbyDirectory.isDirectory || nearbyDirectory.mkdir())
                val file = canonicalChild(nearbyDirectory, digest(cell.toByteArray(Charsets.UTF_8)))
                check(!file.exists()) { "Nearby cell already exists; no overwrite or retry" }
                val discoveryName = "Nearby QA ${identity.toString().take(8)}" // Must be replaced by full public snapshot name.
                val json = JSONObject().put("elements", JSONArray().put(JSONObject().put("type", "node")
                    .put("id", ref.id).put("version", 7).put("lat", location.latitude).put("lon", location.longitude)
                    .put("tags", JSONObject().put("name", discoveryName).put("amenity", "cafe")))).toString()
                val parsed = parseOverpassPlaces(json).single()
                check(parsed.osmRef == ref && parsed.name == discoveryName && parsed.latitude == location.latitude && parsed.longitude == location.longitude)
                receipt.put("nearbyCacheKey", cell).put("nearbyFile", file.name)
                writeNew(file, json.toByteArray(Charsets.UTF_8), MAX_SEED)
                nearby = file
                nearbyHash = hash(file)
                receipt.put("nearbyBytes", file.length())
                    .put("nearbySha256", nearbyHash).put("tapPx", JSONArray(listOf(tap.x, tap.y)))
                    .put("tapLatitude", location.latitude).put("tapLongitude", location.longitude).put("zoom", 17)
                check(SystemClock.elapsedRealtime() - started < 90_000)
                assertIdle()
                active.onActivity { activity ->
                    requireOwnedMap(activity)
                    check(OsmServiceSettings(context).read().overpass == endpoint)
                    val now = checkNotNull(native.get()).projection.fromScreenLocation(PointF(tap.x, tap.y))
                    check(nearbyKey(endpoint, now) == cell && abs(now.latitude - location.latitude) < 0.00000001 &&
                        abs(now.longitude - location.longitude) < 0.00000001)
                    check(checkNotNull(native.get()).cameraPosition.zoom >= 16)
                }
                check(System.currentTimeMillis() - file.lastModified() in 0..600_000)
                compose.onNodeWithTag("navigation-home-map").performTouchInput { click(tap) }
                awaitPublicCard(publicName, ref)
                compose.onNodeWithTag("place-info-close").performClick()
                compose.onNodeWithTag("place-information-card").assertDoesNotExist()
                compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
                receipt.put("entryPath", "native MapView touch -> production nearby cache/parser/discovery -> DiaryApp modal -> close")
            } else {
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-places").performScrollTo().performClick()
                val row = "place-row-${place.id}"
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("places-list").performScrollToNode(hasTestTag(row))
                compose.onNodeWithTag(row).performClick()
                awaitPublicCard(publicName, ref)
                compose.onNodeWithTag("place-info-route").assertIsNotEnabled()
                // Real PlaceDetail lazy items; verify public first, then actual private heading/name.
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Kişisel yer günlüğü"))
                compose.onNodeWithText("Kişisel yer günlüğü", useUnmergedTree = true).assertIsDisplayed()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(privateName))
                compose.onNodeWithText(privateName).assertIsDisplayed()
                compose.onNodeWithTag("return-home-map").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("place-information-card").assertDoesNotExist()
                receipt.put("entryPath", "actual menu-places -> Room place-row -> DiaryApp public card -> actual personal section -> back")
            }
            check(SystemClock.elapsedRealtime() - started < 90_000)
            assertIdle()
            check(budget == context.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE).getString("overpass_budget", null))
            requirePublicSnapshot(snapshot)
            check(runBlocking { repository.snapshot() } == if (saved) DiarySnapshot().copy(places = listOf(place)) else DiarySnapshot())
            receipt.put("bodyStatus", "PASS").put("overpassBudgetUnchanged", true)
        } catch (error: Throwable) {
            failure = error
            receipt.put("failureClass", error.javaClass.name)
        } finally {
            // Dispose actual entry owners before any successful-case removal. Failure preserves all seeds.
            try { scenario?.onActivity { it.setContent {} }; compose.waitForIdle(); scenario?.close() }
            catch (error: Throwable) { failure = combine(failure, error); receipt.put("disposeFailureClass", error.javaClass.name) }
            if (failure == null) try {
                assertIdle()
                check(publicSeeded)
                check(runBlocking { cache.cached(key) }?.snapshot == snapshot) // Drain this owned cache's serialized work.
                requirePublicSnapshot(snapshot)
                check(runBlocking { repository.snapshot() } == if (saved) DiarySnapshot().copy(places = listOf(place)) else DiarySnapshot())
                nearby?.let { check(it.isFile && it.length() in 1..MAX_SEED.toLong() && hash(it) == nearbyHash) }
                receipt.put("cleanupStatus", "BODY_PASS_OWN_ONLY_STARTED")
                if (saved) runBlocking { repository.deletePlace(place.id) }
                runBlocking { cache.invalidate(key) }
                requireEmptyPublicStore()
                nearby?.let {
                    check(it.canonicalFile == it.absoluteFile && it.isFile && it.length() in 1..MAX_SEED.toLong() && hash(it) == nearbyHash)
                    check(it.delete())
                }
                check(runBlocking { repository.snapshot() } == DiarySnapshot())
                receipt.put("postCleanupPublicCacheSha256", hash(publicFile))
                    .put("cleanupStatus", "OWN_ROW_KEY_AND_NEARBY_REMOVED_EMPTY_PUBLIC_CONTAINER_RETAINED")
                    .put("testStatus", "PASS").put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            } catch (error: Throwable) {
                failure = error
                receipt.put("cleanupStatus", "FAILED_REMAINING_DATA_PRESERVED").put("cleanupFailureClass", error.javaClass.name)
            }
            if (failure != null) receipt.put("testStatus", "NONPASS").put("failureData",
                if (receipt.getString("cleanupStatus").startsWith("FAILED")) "REMAINING_DATA_PRESERVED_AFTER_CLEANUP_FAILURE"
                else "SEEDS_PRESERVED_NO_RETRY_NO_APP_CLEAR")
            writeNew(output, receipt.toString(2).toByteArray(Charsets.UTF_8), MAX_JSON)
        }
        failure?.let { throw it }
    }

    private fun awaitPublicCard(name: String, ref: OsmRef) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("place-information-card").assertExists()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("node ${ref.id}", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("place-info-refresh").assertIsEnabled()
        val status = compose.onNodeWithTag("place-info-status").fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("\n") { it.text }
        check(status.contains("OSM bilgileri") && !status.contains("alınamadı") && !status.contains("eski") &&
            !status.contains("Güncelleniyor") && !status.contains("yükleniyor"))
    }

    private fun admission(): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Root entrypoint allocation after four captures required; missing opt-in is NOT_RUN",
            args.getString("izPlaceInformationEntrypoints") == "isolated-offline")
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == QA_PACKAGE && context.packageName == QA_PACKAGE)
        check(instrumentation.context.packageName == "$QA_PACKAGE.test" && args.getString("izExpectedPackage") == QA_PACKAGE)
        val pins = PIN_NAMES.associateWith { checkNotNull(args.getString(it)) }
        for ((name, value) in pins) check(value.matches(Regex(if (name.endsWith("Sha256")) "[0-9a-f]{64}" else "[0-9a-f]{40}")))
        val run = checkNotNull(args.getString("izEntrypointRunUuid"))
        val visual = checkNotNull(args.getString("izVisualRunUuid"))
        check(UUID.fromString(run).toString() == run && UUID.fromString(visual).toString() == visual && run != visual)
        check(args.getString("izFixtureSha256") == CAPTURE_SHA) // Existing capture argument; entrypoint source has its own pin.
        check(BuildConfig.OSM_CLIENT_ID.isEmpty() && BuildConfig.GROUP_SUPABASE_URL.isEmpty() && BuildConfig.GROUP_SUPABASE_KEY.isEmpty())
        val result = JSONObject().put("pins", JSONObject(pins)).put("runUuid", run).put("visualRunUuid", visual)
            .put("captureFixtureSha256", CAPTURE_SHA).put("hostBinding", "REQUIRES_ROOT_GO_FINAL_SOURCE_CONFIG_APK_BINDING")
        for ((app, name) in listOf(context to "izAppApkSha256", instrumentation.context to "izTestApkSha256")) {
            check(context.packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName) == PackageManager.PERMISSION_DENIED)
            check(app.applicationInfo.splitSourceDirs.isNullOrEmpty())
            val apk = File(app.applicationInfo.sourceDir)
            check(apk.isFile && apk.length() in 1..(512L * 1024 * 1024))
            val actualHash = hash(apk)
            check(actualHash == pins.getValue(name))
            val info = context.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = checkNotNull(info.signingInfo).apkContentsSigners.map { digest(it.toByteArray()) }
            check(signers == listOf(pins.getValue("izSignerSha256")))
            result.put(name, JSONObject().put("package", app.packageName).put("bytes", apk.length())
                .put("sha256", actualHash).put("signerSha256", signers.single()))
        }
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)) check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        check(connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true })
        assertIdle()
        check(runBlocking { DiaryRepository(context).snapshot() } == DiarySnapshot())
        // Verify exact completed capture prefix from this SAME final APK pair before own seed/Activity.
        val capture = canonicalChild(canonicalChild(context.cacheDir.canonicalFile, "place-information-visual-v1"), visual)
        check(capture.isDirectory)
        val expected = FRAMES.flatMap { listOf("$it.png", "$it.json") }.toSet()
        val files = checkNotNull(capture.listFiles())
        check(files.size == expected.size && files.map { it.name }.toSet() == expected)
        for (frame in FRAMES) {
            val png = canonicalChild(capture, "$frame.png"); val json = canonicalChild(capture, "$frame.json")
            check(png.isFile && png.length() in 1..(16L * 1024 * 1024) && json.isFile && json.length() in 1..MAX_JSON.toLong())
            val prior = JSONObject(json.readText(Charsets.UTF_8))
            check(prior.getString("captureStatus") == "CAPTURED_PENDING_HOST_READBACK_UIUX" &&
                prior.getString("runUuid") == visual && prior.getString("frame") == frame)
            val priorPins = prior.getJSONObject("pins")
            check(priorPins.getString("izFixtureSha256") == CAPTURE_SHA)
            for (pin in PIN_NAMES.filter { it != "izEntrypointFixtureSha256" }) check(priorPins.getString(pin) == pins.getValue(pin))
            check(prior.getLong("pngBytes") == png.length() && prior.getString("pngSha256") == hash(png))
        }
        return result.put("fourPrecedingCaptures", "EXACT_PAIR_PINS_BYTES_SHA_VERIFIED; UIUX/host PNG CRC remain separate")
    }

    private fun assertIdle() {
        check(!TrackerSettings(context).enabled && !TrackingService.isRunning)
        val nav = (context.applicationContext as IzApplication).navigation.state.value
        check(nav.sessionId == null && nav.journey == null && nav.route == null && !nav.guidance && !nav.recording &&
            !nav.recordingPending && !nav.locationActive && !nav.simulation)
        // Only existence is examined: never read/log any credential or pending verifier.
        val authDirectory = File(context.noBackupFilesDir, "osm-auth")
        for (name in listOf("session.bin", "session.bin.bak", "session.bin.new")) check(!File(authDirectory, name).exists())
        val auth = OsmAuthManager.get(context).state.value
        check(auth.user == null && !auth.busy)
    }

    private fun namespace(receipt: JSONObject, index: Int): File {
        val base = canonicalChild(context.cacheDir.canonicalFile, "place-information-entrypoints-v1")
        check(base.isDirectory || base.mkdir())
        val directory = canonicalChild(base, receipt.getString("runUuid"))
        if (index == 0) check(!directory.exists() && directory.mkdir()) else check(directory.isDirectory)
        val files = checkNotNull(directory.listFiles())
        val expected = CASES.take(index).map { "$it.json" }.toSet()
        check(files.size == expected.size && files.map { it.name }.toSet() == expected)
        if (index == 1) {
            val prior = readPrior(directory)
            check(prior.getString("testStatus") == "PASS" && prior.getString("runUuid") == receipt.getString("runUuid") &&
                prior.getString("visualRunUuid") == receipt.getString("visualRunUuid"))
            for (pin in PIN_NAMES) check(prior.getJSONObject("pins").getString(pin) == receipt.getJSONObject("pins").getString(pin))
        }
        return directory
    }

    private fun readPrior(directory: File): JSONObject {
        val file = canonicalChild(directory, "${CASES[0]}.json")
        check(file.isFile && file.length() in 1..MAX_JSON.toLong())
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun requirePublicStore(index: Int, directory: File) {
        check(publicDirectory.canonicalFile == publicDirectory.absoluteFile && publicFile.canonicalFile == publicFile.absoluteFile)
        check(!publicTemporary.exists())
        if (index == 0) check(!publicFile.exists()) { "Default public store pre-exists; preserve it and stop" }
        else { requireEmptyPublicStore(); check(hash(publicFile) == readPrior(directory).getString("postCleanupPublicCacheSha256")) }
    }

    private fun publicRows(): JSONArray {
        check(publicFile.isFile && publicFile.length() in 1..MAX_PUBLIC.toLong() && !publicTemporary.exists())
        val root = JSONObject(publicFile.readText(Charsets.UTF_8))
        check(root.getInt("format") == 1)
        return root.getJSONArray("entries")
    }

    private fun requireEmptyPublicStore() { check(publicRows().length() == 0) }

    private fun requirePublicSnapshot(snapshot: PublicPlaceSnapshot) {
        val rows = publicRows(); check(rows.length() == 1)
        val row = rows.getJSONObject(0)
        check(row.getString("endpoint") == snapshot.key.endpoint && row.getString("type") == snapshot.key.ref.type.name &&
            row.getLong("id") == snapshot.key.ref.id && row.getLong("version") == snapshot.version &&
            row.getLong("fetchedAt") == snapshot.fetchedAtMillis && !row.has("unavailable"))
        val tags = row.getJSONObject("tags")
        check(tags.length() == snapshot.tags.size && snapshot.tags.all { (key, value) -> tags.getString(key) == value })
    }

    private fun requireOwnedMap(activity: ComponentActivity): MapView {
        check(activity.packageName == QA_PACKAGE && activity.window.decorView.hasWindowFocus() && !activity.isDestroyed)
        val maps = mutableListOf<MapView>()
        fun visit(view: View, depth: Int = 0) {
            check(depth < 64)
            if (view is MapView) maps += view
            if (view is ViewGroup) { check(view.childCount < 1024); for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1) }
        }
        visit(activity.window.decorView)
        val map = maps.single()
        check(map.context.activity() === activity && map.isShown && map.width in 1..4096 && map.height in 1..4096)
        return map
    }

    private fun Context.activity(): Activity? {
        var value: Context = this
        repeat(16) {
            if (value is Activity) return value as Activity
            val next = (value as? ContextWrapper)?.baseContext ?: return null
            if (next === value) return null
            value = next
        }
        return null
    }

    private fun offlineStyle(raw: String): String = JSONObject(raw).apply {
        val sources = getJSONObject("sources")
        sources.remove("osm-tiles")
        // Preserve production local GeoJSON/layers; reject any additional remote source rather than request it.
        sources.keys().forEach { name ->
            val source = sources.getJSONObject(name)
            check(source.optString("type") == "geojson" && !source.has("url") && !source.has("tiles"))
            check(source.opt("data") is JSONObject)
        }
        val original = getJSONArray("layers")
        put("layers", JSONArray().put(JSONObject().put("id", "qa-entrypoint-background").put("type", "background")
            .put("paint", JSONObject().put("background-color", "#E4EEE6"))).apply {
            for (i in 0 until original.length()) {
                val layer = original.getJSONObject(i)
                if (layer.optString("source") != "osm-tiles" && layer.optString("type") != "symbol") put(layer)
            }
        })
        remove("glyphs"); remove("sprite")
    }.toString()

    private fun safeCell(value: Double) = value.isFinite() && abs(value * 10_000 - kotlin.math.round(value * 10_000)) < .28
    private fun nearbyKey(endpoint: String, point: LatLng) = "nearby:$endpoint:" +
        String.format(Locale.US, "%.4f", point.latitude) + ":" + String.format(Locale.US, "%.4f", point.longitude)
    private fun canonicalChild(parent: File, name: String): File = File(parent, name).also {
        check(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == parent.canonicalFile)
    }
    private fun writeNew(file: File, bytes: ByteArray, maximum: Int) {
        check(bytes.size in 1..maximum && file.canonicalFile == file.absoluteFile && !file.exists() && file.createNewFile())
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(64 * 1024)
            while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
        return digest.digest().hex()
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun combine(prior: Throwable?, next: Throwable): Throwable = prior?.also { it.addSuppressed(next) } ?: next

    companion object {
        private const val QA_PACKAGE = "org.iz.navigation.placeinfoqa20261112p01"
        private const val CAPTURE_SHA = "9031c6b022d904a55fea3d9a1e7fa8ffda673beb954e62f0ec02ae4467c00cb5"
        private const val MAX_SEED = 4 * 1024
        private const val MAX_PUBLIC = 16 * 1024
        private const val MAX_JSON = 64 * 1024
        private val CASES = listOf("01-native-map-entry", "02-saved-place-entry")
        private val METHODS = listOf("case01NativeMapTapOpensDiaryAppPublicCard", "case02SavedPlaceRowOpensDiaryAppPublicAndPersonalDetail")
        private val FRAMES = listOf("01-map-card-top", "02-saved-card-lower", "03-hours-detail", "04-zone-picker-landscape-font2")
        private val PIN_NAMES = listOf("izCandidateCommit", "izCandidateTree", "izEntrypointFixtureSha256", "izQaConfigSha256",
            "izAppApkSha256", "izTestApkSha256", "izSignerSha256")
    }
}
