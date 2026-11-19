package org.iz.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.BuildConfig
import org.iz.navigation.IzApplication
import org.iz.navigation.health.HealthPrivacyActivity
import org.iz.navigation.integration.LocalOsmMapPresentation
import org.iz.navigation.integration.OsmMapPresentation
import org.iz.navigation.tracking.TrackerSettings
import org.iz.navigation.tracking.TrackingService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Collects selected missing frames only. The existing behavior suites own navigation and mutations. */
@RunWith(AndroidJUnit4::class)
class RedesignPhoneVisualCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun captureSelectedPhoneScenes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit visual allocation required", args.getString("izPhoneVisualOnly") == "true")
        val app = instrumentation.targetContext
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == QA_PACKAGE &&
            app.packageName == QA_PACKAGE && instrumentation.context.packageName == "$QA_PACKAGE.test")
        check(app.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        check(!TrackingService.isRunning && !TrackerSettings(app).enabled)
        val navigation = (app.applicationContext as IzApplication).navigation
        val baseline = navigation.state.value
        check(baseline.sessionId == null && !baseline.guidance && !baseline.recording && !baseline.locationActive)
        val source = checkNotNull(args.getString("izPhoneVisualSourceSha"))
        require(source.matches(SHA))
        val runText = checkNotNull(args.getString("izPhoneVisualRun"))
        val run = UUID.fromString(runText)
        require(run.toString() == runText)
        val ids = checkNotNull(args.getString("izPhoneVisualIds")).split(",")
        require(ids.isNotEmpty() && ids.size == ids.toSet().size && ids.all { it in phoneVisualSceneIds })
        val appSha = pinnedApk(app, checkNotNull(args.getString("izAppApkSha256")))
        val testSha = pinnedApk(instrumentation.context, checkNotNull(args.getString("izTestApkSha256")))
        val parent = File(app.cacheDir, "redesign-phone-visual")
        val directory = File(parent, run.toString())
        check(parent.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        check(directory.canonicalFile.parentFile == parent.canonicalFile)
        check((parent.isDirectory || parent.mkdir()) && directory.mkdir()) { "Use a new visual run UUID" }

        val failures = mutableListOf<Throwable>()
        for (id in ids) {
            val session = PhoneSceneHost(this, directory, source, appSha, testSha, runText)
            val failure = try {
                session.stagePhoneScene(id)
                null
            } catch (error: Exception) {
                error
            } catch (error: AssertionError) {
                error
            } finally {
                // Only continue after host cleanup and checking that the real session is unchanged.
                session.close()
                check(navigation.state.value == baseline && !TrackingService.isRunning)
            }
            if (failure == null) session.publish(id)
            else failures += AssertionError("$id visual capture failed", failure)
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} phone visual scene(s) failed").also { error ->
                failures.forEach(error::addSuppressed)
            }
        }
    }

    private fun pinnedApk(context: Context, expected: String): String {
        require(expected.matches(HASH))
        check(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "A pinned single APK is required" }
        val actual = phoneVisualSha(File(context.applicationInfo.sourceDir))
        check(actual == expected) { "Installed APK hash differs for ${context.packageName}" }
        return actual
    }

    companion object {
        private const val QA_PACKAGE = "org.iz.navigation.qaredesign20261119"
        private val SHA = Regex("[a-f0-9]{40}")
        private val HASH = Regex("[a-f0-9]{64}")
    }
}

internal class PhoneSceneHost(
    private val test: RedesignPhoneVisualCaptureTest,
    private val directory: File,
    private val sourceSha: String,
    private val appSha: String,
    private val testSha: String,
    private val run: String,
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val host = Ux0111ComposeHost(test.compose)
    private val mapReady = AtomicBoolean()
    private lateinit var window: Window
    private var nativeDialogWindow: Window? = null
    private var pendingReceipt: JSONObject? = null
    val compose get() = test.compose

    fun show(body: @Composable () -> Unit) {
        host.setContent {
            val activity = generateSequence(LocalContext.current) { context ->
                (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
            }.filterIsInstance<Activity>().first()
            check(activity.javaClass == HealthPrivacyActivity::class.java)
            SideEffect { window = activity.window }
            val mapPresentation = remember { OsmMapPresentation(::localMapStyle) { mapReady.set(true) } }
            CompositionLocalProvider(LocalOsmMapPresentation provides mapPresentation) {
                IzTheme {
                    Surface {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) { body() }
                    }
                }
            }
        }
    }

    @Composable
    fun bindNativeDialogWindow() {
        val view = LocalView.current
        val nativeOwner = generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().first()
        val target = nativeOwner.window
        SideEffect {
            check(target !== window && target.context.packageName == instrumentation.targetContext.packageName)
            nativeDialogWindow = target
        }
    }

    fun capture(id: String, anchor: SemanticsMatcher, component: String,
        fixture: String, native: Boolean = false, requiredColor: Int? = null) {
        check(pendingReceipt == null && id in phoneVisualSceneIds)
        compose.waitUntil(5_000) { compose.onAllNodes(anchor).fetchSemanticsNodes().size == 1 }
        compose.onNode(anchor).assertIsDisplayed()
        val captureWindow = if (id == "preview") checkNotNull(nativeDialogWindow) else window
        if (native) {
            check(Build.VERSION.SDK_INT >= 34) { "Native scene capture requires an owned API 34+ window" }
            compose.waitUntil(5_000) { mapReady.get() && captureWindow.decorView.hasWindowFocus() }
        }
        val method = when {
            id == "preview" -> "OWNED_DIALOG_WINDOW_SYNTHETIC_NATIVE_MAP"
            native -> "OWNED_WINDOW_SYNTHETIC_NATIVE_MAP"
            else -> "OWNED_COMPOSE_ROOT"
        }
        val expectedColor = if (native) MAP_COLOR else requiredColor
        var settled: Bitmap? = null
        compose.waitUntil(5_000) {
            val frame = if (native) {
                checkNotNull(instrumentation.uiAutomation.takeScreenshot(captureWindow))
            } else {
                compose.onNode(isRoot() and hasAnyDescendant(anchor), useUnmergedTree = true)
                    .captureToImage().asAndroidBitmap()
            }
            if (expectedColor == null || frame.containsFixtureColor(expectedColor)) settled = frame
            else frame.recycle()
            settled != null
        }
        val source = checkNotNull(settled)
        val bitmap = if (source.config == Bitmap.Config.HARDWARE) checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) else source
        try {
            check(bitmap.width in 1..4096 && bitmap.height in 1..4096)
            val draft = File(directory, "$id.png.partial")
            check(draft.createNewFile())
            draft.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            pendingReceipt = JSONObject()
                .put("id", id).put("run", run).put("sourceSha", sourceSha)
                .put("appApkSha256", appSha).put("testApkSha256", testSha)
                .put("component", component).put("fixture", fixture).put("method", method)
                .put("image", "$id.png").put("imageSha256", phoneVisualSha(draft))
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("capturedAtMillis", System.currentTimeMillis()).put("status", "CAPTURED_PENDING_VISUAL_REVIEW")
        } finally {
            bitmap.recycle()
            if (bitmap !== source) source.recycle()
        }
    }

    fun publish(id: String) {
        val payload = checkNotNull(pendingReceipt)
        check(payload.getString("id") == id)
        val image = File(directory, "$id.png")
        val receipt = File(directory, "$id.json")
        val draftReceipt = File(directory, "$id.json.partial")
        check(!image.exists() && !receipt.exists() && draftReceipt.createNewFile())
        draftReceipt.writeText(payload.toString(2), Charsets.UTF_8)
        check(File(directory, "$id.png.partial").renameTo(image))
        check(draftReceipt.renameTo(receipt))
    }

    override fun close() = host.close()

    private fun localMapStyle(raw: String): String = JSONObject(raw).apply {
        val sources = getJSONObject("sources")
        val removeSources = sources.keys().asSequence().filter { sources.getJSONObject(it).optString("type") != "geojson" }.toList()
        removeSources.forEach { sources.remove(it) }
        val original = getJSONArray("layers")
        val layers = JSONArray().put(JSONObject().put("id", "visual-fixture-background")
            .put("type", "background").put("paint", JSONObject().put("background-color", "#C8E5D5")))
        for (index in 0 until original.length()) {
            val layer = original.getJSONObject(index)
            if (layer.optString("source") !in removeSources && layer.optString("type") !in setOf("symbol", "background")) {
                layers.put(layer)
            }
        }
        put("layers", layers)
        remove("glyphs")
        remove("sprite")
    }.toString().also { check(!it.contains("https://") && !it.contains("http://")) }

    private fun Bitmap.containsFixtureColor(color: Int): Boolean {
        val readable = if (config == Bitmap.Config.HARDWARE) checkNotNull(copy(Bitmap.Config.ARGB_8888, false)) else this
        try {
            var matches = 0
            for (y in 4 until height step 8) for (x in 4 until width step 8) {
                val pixel = readable.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) >= 248 &&
                    kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(color)) <= 3 &&
                    kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(color)) <= 3 &&
                    kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(color)) <= 3 &&
                    ++matches >= 12) return true
            }
            return false
        } finally {
            if (readable !== this) readable.recycle()
        }
    }

    companion object {
        private val MAP_COLOR = 0xffc8e5d5.toInt()
    }
}

private fun phoneVisualSha(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val bytes = ByteArray(64 * 1024)
        while (true) {
            val size = stream.read(bytes)
            if (size < 0) break
            digest.update(bytes, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
