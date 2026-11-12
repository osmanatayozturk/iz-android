package org.iz.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.Place
import org.iz.navigation.data.PlaceSource
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
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Four synthetic component frames; never native map/entry, upload, GPS or UIUX acceptance.
 * Root must bind this source/config/candidate to the installed APK pair and authorize runtime.
 * Run the four selectors IN ORDER with one fresh UUID. Failures preserve partial files; no retry.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlaceInformationVisualCaptureTest {
    // Empty rule is essential: all admission/output checks precede the first Activity launch.
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val callbacks = AtomicInteger()
    private val noAction: () -> Unit = { callbacks.incrementAndGet(); Unit }
    private val list = AtomicReference<LazyListState?>()
    private val key = PlaceInfoKey("https://example.invalid/api/0.6", OsmRef(OsmType.NODE, 1001))
    private val instant = Instant.parse("2026-11-12T15:30:00Z")
    private val zone = PlaceInfoZones.resolve(null, "Europe/Istanbul", ZoneId.of("UTC"))
    private val snapshot by lazy { PublicPlaceSnapshot(key, 7, linkedMapOf(
        "name" to "Sentetik kamusal kafe", "addr:street" to "Örnek Sokak", "addr:housenumber" to "12",
        "phone" to "+90 312 555 0100", "website" to "https://example.invalid/cafe",
        "opening_hours" to HOURS, "timezone" to "Europe/Istanbul", "wheelchair" to "limited",
        "payment:cash" to "yes", "payment:credit_cards" to "yes"), instant.toEpochMilli()) }

    @Test fun frame01MapCardTop() = capture(0, "frame01MapCardTop")
    @Test fun frame02SavedCardLower() = capture(1, "frame02SavedCardLower")
    @Test fun frame03HoursDetail() = capture(2, "frame03HoursDetail")
    @Test fun frame04ZonePickerLandscapeFont2() = capture(3, "frame04ZonePickerLandscapeFont2")

    @Composable private fun PublicCard(close: Boolean = false, modifier: Modifier = Modifier) {
        PlaceInformationContent(PlaceInfoState(key = key, snapshot = snapshot), key, "Sentetik seçim",
            instant, ZoneId.of("UTC"), null, { noAction() }, noAction, noAction, noAction,
            noAction, noAction, { _, _ -> noAction() }, onClose = if (close) noAction else null,
            modifier = modifier, onSave = if (close) noAction else null)
    }

    @Composable private fun Frame(index: Int) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, if (index == 3) 2f else 1f)) {
            IzTheme {
                when (index) {
                    0 -> PlaceInformationMapDialog(noAction) {
                        PublicCard(true, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp))
                    }
                    1 -> {
                        val scroll = rememberLazyListState()
                        SideEffect { list.set(scroll) }
                        val place = Place(id = "00000000-0000-4000-8000-000000001001", name = PRIVATE_NAME,
                            osmType = OsmType.NODE, osmId = 1001, source = PlaceSource.OSM)
                        PlaceDetail(place, DiaryState(places = listOf(place)), noAction, noAction, noAction,
                            noAction, noAction, { noAction() }, { noAction() }, { noAction() }, noAction,
                            { noAction() }, { noAction() }, { noAction() }, onSurvey = noAction,
                            publicInformation = { PublicCard() }, listState = scroll)
                    }
                    2 -> PlaceInformationHoursDetail(BasicPlaceOpeningHours.evaluate(HOURS, instant, zone.zone),
                        zone, onClose = noAction)
                    3 -> PlaceInformationZoneDialog(PlaceInfoZones.resolve("UTC", null, ZoneId.of("UTC")),
                        "UTC", { noAction() }, noAction, instant)
                }
            }
        }
    }

    private fun capture(index: Int, method: String) {
        val metadata = admission() // Missing opt-in => assumption/NOT_RUN, before files/Activity.
        val directory = namespace(metadata, index)
        val png = File(directory, "${FRAMES[index]}.png")
        val json = File(directory, "${FRAMES[index]}.json")
        metadata.put("frame", FRAMES[index]).put("method", "${javaClass.name}#$method")
            .put("captureStatus", "INCOMPLETE").put("evidenceKind", "COMPONENT_SYNTHETIC_HOST")
            .put("key", "example.invalid/api/0.6|node/1001").put("snapshotVersion", 7)
            .put("instant", instant.toString()).put("effectiveZone", if (index == 3) "UTC" else zone.zone.id)
            .put("publicData", "IMMUTABLE_SYNTHETIC_NO_READER").put("loading", false).put("stale", false)
            .put("hostReadback", "PENDING_BYTES_SHA_PNG_CRC").put("uiuxVerdict", "NOT_RUN")
            .put("limits", "4PNG+4JSON;4096px;16MiB/PNG;64KiB/JSON;CREATE_NEW;no native surfaces")
        var scenario: ActivityScenario<ComponentActivity>? = null
        var failure: Throwable? = null
        try {
            val context = instrumentation.targetContext
            val intent = Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.packageManager.getActivityInfo(intent.component!!, 0) // Existing ui-test-manifest only.
            val active = ActivityScenario.launch<ComponentActivity>(intent)
            scenario = active
            val requested = if (index == 3) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            active.onActivity { it.requestedOrientation = if (index == 3)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(8_000) {
                var correct = false
                active.onActivity { correct = it.resources.configuration.orientation == requested }
                correct
            }
            active.onActivity { it.setContent { Frame(index) } }
            compose.waitForIdle()
            if (index == 1) {
                var height = 0; var density = 0f
                active.onActivity { height = it.window.decorView.height; density = it.resources.displayMetrics.density }
                // Real LazyColumn scroll; keep the personal boundary near the bottom of its actual viewport.
                val state = checkNotNull(list.get())
                instrumentation.runOnMainSync {
                    runBlocking { state.scrollToItem(1, -(height - (190 * density).toInt()).coerceAtLeast(0)) }
                }
                compose.waitForIdle()
                compose.onNodeWithText(PRIVATE_NAME).assertIsDisplayed()
                compose.onNodeWithText("Kişisel yer günlüğü", useUnmergedTree = true).assertIsDisplayed()
            }
            if (index == 3) {
                compose.onNodeWithTag("place-info-zone-input").performTextInput("Istanbul")
                compose.onNodeWithTag("place-info-zone-options").performScrollTo()
                    .performScrollToNode(hasTestTag("place-info-zone-option-Europe/Istanbul"))
                compose.onNodeWithTag("place-info-zone-option-Europe/Istanbul").performClick().assertIsSelected()
                    .assertTextContains("UTC+03:00", substring = true)
                compose.onNodeWithText("Seçili: Europe/Istanbul").assertExists()
                metadata.put("cityQuery", "Istanbul").put("selectedInstalledZone", "Europe/Istanbul")
                    .put("selectedOffset", "+03:00").put("overrideCallbackWrites", 0).put("zoneSelectionCommitted", false)
                active.onActivity { activity ->
                    // Exact owned focus token only; no global keyboard/window operation.
                    val decor = ownedWindow(activity, dialog = true).decorView
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(decor.windowToken, 0)
                }
                compose.onNodeWithTag("place-info-zone-save").assertIsDisplayed()
                compose.onNodeWithTag("place-info-zone-cancel").assertIsDisplayed()
            }
            compose.waitForIdle()
            val probes = when (index) {
                0 -> listOf("place-information-card", "place-info-status", "place-info-contact", "place-info-phone-0", "place-info-web-0")
                1 -> listOf("place-info-hours", "place-info-zone", "place-info-wheelchair", "place-info-payments", "place-info-contribute")
                2 -> listOf("place-info-hours-detail", "place-info-hours-close")
                else -> listOf("place-info-zone-input", "place-info-zone-option-Europe/Istanbul", "place-info-zone-save", "place-info-zone-cancel", "place-info-zone-reset")
            }
            val geometry = JSONArray()
            for (tag in probes) geometry.put(probe(tag, compose.onNodeWithTag(tag)))
            if (index == 0) {
                geometry.put(probe("public-name", compose.onNodeWithText("Sentetik kamusal kafe")))
                geometry.put(probe("public-address", compose.onNodeWithText("Örnek Sokak", substring = true)))
                geometry.put(probe("public-source", compose.onNodeWithText("node 1001", substring = true)))
            }
            if (index == 1) {
                geometry.put(probe("personal-heading", compose.onNodeWithText("Kişisel yer günlüğü", useUnmergedTree = true)))
                geometry.put(probe("personal-name", compose.onNodeWithText(PRIVATE_NAME)))
                compose.runOnIdle { metadata.put("lazyScrollIndex", list.get()!!.firstVisibleItemIndex)
                    .put("lazyScrollOffsetPx", list.get()!!.firstVisibleItemScrollOffset) }
            }
            if (index == 2) for (day in DayOfWeek.values()) {
                val label = day.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("tr-TR"))
                geometry.put(probe(day.name, compose.onNodeWithText(label)))
            }
            if (index == 2) metadata.put("weeklyFixture", HOURS).put("weeklyDays", 7)
            if (index == 3) geometry.put(probe("selected-UTC-offset", compose.onNode(
                hasText("UTC+03:00", substring = true) and
                    hasAnyAncestor(hasTestTag("place-info-zone-option-Europe/Istanbul")), useUnmergedTree = true)))
            metadata.put("semanticsGeometry", geometry)
            // Required visible content fails closed; offscreen probes remain explicitly recorded.
            when (index) {
                0 -> { compose.onNodeWithText("Sentetik kamusal kafe").assertIsDisplayed()
                    compose.onNodeWithTag("place-info-status").assertIsDisplayed()
                    compose.onNodeWithTag("place-info-contact").assertIsDisplayed() }
                1 -> { compose.onNodeWithTag("place-info-contribute").assertIsDisplayed()
                    compose.onNodeWithTag("place-info-payments").assertIsDisplayed() }
                2 -> compose.onNodeWithTag("place-info-hours-close").assertIsDisplayed()
                3 -> { compose.onNodeWithTag("place-info-zone-option-Europe/Istanbul").assertIsDisplayed()
                    compose.onNode(hasText("UTC+03:00", substring = true) and
                        hasAnyAncestor(hasTestTag("place-info-zone-option-Europe/Istanbul")), useUnmergedTree = true).assertIsDisplayed() }
            }
            var owned: Window? = null
            active.onActivity { activity ->
                check(activity.resources.configuration.orientation == requested)
                owned = ownedWindow(activity, dialog = index != 1)
                metadata.put("activity", activity.javaClass.name).put("actualOrientation", requested)
                    .put("density", activity.resources.displayMetrics.density)
                    .put("systemFontScale", activity.resources.configuration.fontScale)
                    .put("localFontScale", if (index == 3) 2 else 1)
            }
            val window = checkNotNull(owned)
            compose.waitUntil(5_000) {
                var hidden = false
                instrumentation.runOnMainSync { hidden = ViewCompat.getRootWindowInsets(window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == false }
                hidden
            }
            copyWindow(window, png, metadata)
            check(callbacks.get() == 0)
            assertIdle()
            metadata.put("callbacks", callbacks.get()).put("captureStatus", "CAPTURED_PENDING_HOST_READBACK_UIUX")
        } catch (error: Throwable) {
            failure = error
            metadata.put("captureStatus", "NONPASS").put("failureClass", error.javaClass.name)
        } finally {
            try { scenario?.onActivity { it.setContent {} }; scenario?.close() }
            catch (error: Throwable) {
                metadata.put("captureStatus", "NONPASS").put("cleanupFailureClass", error.javaClass.name)
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            metadata.put("callbacks", callbacks.get())
            val bytes = metadata.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_JSON && !json.exists() && json.createNewFile())
            FileOutputStream(json).use { it.write(bytes); it.fd.sync() }
        }
        failure?.let { throw it }
    }

    private fun probe(label: String, node: SemanticsNodeInteraction): JSONObject {
        val result = JSONObject().put("label", label)
        val bounds = runCatching { node.fetchSemanticsNode().boundsInRoot }.getOrNull()
        result.put("exists", bounds != null).put("displayed", runCatching { node.assertIsDisplayed() }.isSuccess)
            .put("displayedMayBePartial", true)
        bounds?.let { result.put("boundsInRootPx", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
        return result
    }

    private fun admission(): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Root isolated offline visual allocation required; missing guard is NOT_RUN",
            args.getString("izPlaceInformationVisual") == "isolated-offline")
        val context = instrumentation.targetContext
        check(QA_PACKAGE != "org.iz.navigation" && QA_PACKAGE in setOf(
            "org.iz.navigation.placeinfoqa20261112p01", "org.iz.navigation.placeinfoqa20261112p02"))
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == QA_PACKAGE && context.packageName == QA_PACKAGE)
        check(instrumentation.context.packageName == "$QA_PACKAGE.test" && args.getString("izExpectedPackage") == QA_PACKAGE)
        val pins = PIN_NAMES.associateWith { checkNotNull(args.getString(it)) }
        for ((name, value) in pins) check(value.matches(Regex(if (name.endsWith("Sha256")) "[0-9a-f]{64}" else "[0-9a-f]{40}")))
        val uuid = checkNotNull(args.getString("izVisualRunUuid"))
        check(UUID.fromString(uuid).toString() == uuid)
        check(BuildConfig.OSM_CLIENT_ID.isEmpty() && BuildConfig.GROUP_SUPABASE_URL.isEmpty() && BuildConfig.GROUP_SUPABASE_KEY.isEmpty())
        val result = JSONObject().put("pins", JSONObject(pins)).put("runUuid", uuid)
            .put("sourceArgumentBinding", "REQUIRES_ROOT_HOST_SOURCE_CONFIG_CAPTURE_TEST_APK_BINDING")
        check(BasicPlaceOpeningHours.evaluate(HOURS, instant, zone.zone).weeklyProgram?.map { it.day } == DayOfWeek.values().toList())
        for ((app, pin) in listOf(context to "izAppApkSha256", instrumentation.context to "izTestApkSha256")) {
            check(context.packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName) == PackageManager.PERMISSION_DENIED)
            val info = context.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            check(app.applicationInfo.splitSourceDirs.isNullOrEmpty())
            val apk = File(app.applicationInfo.sourceDir)
            check(apk.isFile && apk.length() in 1..(512L * 1024 * 1024))
            val actualHash = hash(apk)
            check(actualHash == pins.getValue(pin))
            val signers = checkNotNull(info.signingInfo).apkContentsSigners.map { digest(it.toByteArray()) }
            check(signers == listOf(pins.getValue("izSignerSha256")))
            result.put(pin, JSONObject().put("package", app.packageName).put("bytes", apk.length())
                .put("sha256", actualHash).put("signerSha256", signers.single()).put("versionCode", info.longVersionCode))
        }
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)) check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        check(connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true })
        assertIdle()
        return result
    }

    private fun assertIdle() {
        val context = instrumentation.targetContext
        check(!TrackerSettings(context).enabled && !TrackingService.isRunning)
        val nav = (context.applicationContext as IzApplication).navigation.state.value
        check(nav.sessionId == null && nav.journey == null && nav.route == null && !nav.guidance &&
            !nav.recording && !nav.recordingPending && !nav.locationActive)
        check(runBlocking { DiaryRepository(context).snapshot() } == DiarySnapshot())
    }

    private fun namespace(metadata: JSONObject, index: Int): File {
        val base = File(instrumentation.targetContext.cacheDir.canonicalFile, "place-information-visual-v1")
        check(base.canonicalFile == base.absoluteFile && (base.isDirectory || base.mkdir()))
        val directory = File(base, metadata.getString("runUuid"))
        check(directory.canonicalFile == directory.absoluteFile)
        if (index == 0) check(!directory.exists() && directory.mkdir()) else check(directory.isDirectory)
        val expected = FRAMES.take(index).flatMap { listOf("$it.png", "$it.json") }.toSet()
        val files = checkNotNull(directory.listFiles())
        check(files.map { it.name }.toSet() == expected && files.size == expected.size)
        for (frame in FRAMES.take(index)) {
            val png = File(directory, "$frame.png"); val json = File(directory, "$frame.json")
            check(png.canonicalFile == png.absoluteFile && json.canonicalFile == json.absoluteFile)
            check(png.isFile && png.length() in 1..MAX_PNG && json.isFile && json.length() in 1..MAX_JSON.toLong())
            val prior = JSONObject(json.readText(Charsets.UTF_8))
            check(prior.getString("captureStatus") == "CAPTURED_PENDING_HOST_READBACK_UIUX")
            check(prior.getString("runUuid") == metadata.getString("runUuid") && prior.getString("frame") == frame)
            for (pin in PIN_NAMES) check(prior.getJSONObject("pins").getString(pin) == metadata.getJSONObject("pins").getString(pin))
            check(prior.getLong("pngBytes") == png.length() && prior.getString("pngSha256") == hash(png))
        }
        return directory
    }

    private fun ownedWindow(activity: ComponentActivity, dialog: Boolean): Window {
        check(activity.packageName == QA_PACKAGE && !activity.isFinishing && !activity.isDestroyed)
        val found = linkedSetOf<Window>()
        fun inspect(view: View, depth: Int = 0) {
            check(depth < 64)
            if (view is DialogWindowProvider && view.window.context.activity() === activity) found += view.window
            if (view is ViewGroup) { check(view.childCount < 1024); for (i in 0 until view.childCount) inspect(view.getChildAt(i), depth + 1) }
        }
        for (root in WindowInspector.getGlobalWindowViews()) if (root.context.activity() === activity) inspect(root)
        val window = if (dialog) found.single() else { check(found.isEmpty()); activity.window }
        val decor = window.decorView
        check(window.context.activity() === activity && decor.isShown && decor.hasWindowFocus())
        check(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        fun rejectNative(view: View) {
            check(view !is SurfaceView && view !is TextureView && !view.javaClass.name.contains("MapView"))
            if (view is ViewGroup) for (i in 0 until view.childCount) rejectNative(view.getChildAt(i))
        }
        // Include the host beneath a dialog in the rejection boundary as well.
        rejectNative(activity.window.decorView); rejectNative(decor)
        check(decor.width in 1..4096 && decor.height in 1..4096)
        return window
    }

    private fun Context.activity(): Activity? {
        var context: Context = this
        repeat(16) {
            if (context is Activity) return context as Activity
            val wrapped = context as? ContextWrapper ?: return null
            val next = wrapped.baseContext
            if (next === context) return null
            context = next
        }
        return null
    }

    private fun copyWindow(window: Window, png: File, metadata: JSONObject) {
        var bitmap: Bitmap? = null
        val ready = CountDownLatch(1)
        val status = AtomicInteger(-1)
        val lock = Any()
        var requested = false
        var abandoned = false
        try {
            instrumentation.runOnMainSync {
                val owner = checkNotNull(window.context.activity())
                check(ownedWindow(owner as ComponentActivity, window !== owner.window) === window)
                val decor = window.decorView
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                check(!insets.isVisible(WindowInsetsCompat.Type.ime()))
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                metadata.put("windowWidthPx", decor.width).put("windowHeightPx", decor.height)
                    .put("windowOriginOnScreenPx", JSONArray(location.toList()))
                    .put("windowOwnerPackage", owner.packageName).put("nativeViews", 0).put("imeVisible", false)
                    .put("systemInsetsPx", JSONArray(listOf(bars.left, bars.top, bars.right, bars.bottom)))
                    .put("captureBackend", "PixelCopy_exact_owned_Window_no_fallback")
                bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(window, bitmap!!, {
                    synchronized(lock) {
                        status.set(it)
                        if (abandoned) bitmap?.recycle()
                        ready.countDown()
                    }
                }, Handler(Looper.getMainLooper()))
                requested = true
            }
            check(ready.await(5, TimeUnit.SECONDS) && status.get() == PixelCopy.SUCCESS)
            check(!png.exists() && png.canonicalFile == png.absoluteFile && png.createNewFile())
            FileOutputStream(png).use { file ->
                val capped = CappedOutput(file, MAX_PNG)
                check(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, capped))
                capped.flush(); file.fd.sync()
            }
            check(png.length() in 1..MAX_PNG)
            metadata.put("pngBytes", png.length()).put("pngSha256", hash(png))
        } finally {
            // A timed-out request may still own the bitmap; never recycle it while PixelCopy is pending.
            synchronized(lock) {
                if (!requested || ready.count == 0L) bitmap?.recycle() else abandoned = true
            }
        }
    }

    private class CappedOutput(output: OutputStream, private val maximum: Long) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) { check(count + 1 <= maximum); out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && count + length <= maximum); out.write(bytes, offset, length); count += length
        }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().hex()
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val QA_PACKAGE = BuildConfig.APPLICATION_ID
        private const val HOURS = "Mo-Fr 09:00-17:00; Sa 10:00-14:00; Su off"
        private const val PRIVATE_NAME = "Sentetik kişisel ad"
        private const val MAX_PNG = 16L * 1024 * 1024
        private const val MAX_JSON = 64 * 1024
        private val FRAMES = listOf("01-map-card-top", "02-saved-card-lower", "03-hours-detail", "04-zone-picker-landscape-font2")
        private val PIN_NAMES = listOf("izCandidateCommit", "izCandidateTree", "izFixtureSha256", "izQaConfigSha256",
            "izAppApkSha256", "izTestApkSha256", "izSignerSha256")
    }
}
