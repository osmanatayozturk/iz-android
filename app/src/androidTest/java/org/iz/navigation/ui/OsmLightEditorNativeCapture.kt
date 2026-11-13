package org.iz.navigation.ui

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.FilterOutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import org.iz.navigation.BuildConfig
import org.json.JSONObject

/** Optional test-only evidence. No argument => no capture, files or changed business assertions. */
internal object OsmLightEditorNativeCapture {
    private const val QA = "org.iz.navigation.qalighteditor20261113"
    private const val MAX_PNG = 16L * 1024 * 1024
    private val methods = mapOf(
        "01-main-map-empty-type" to "MainMapOsmEditorEntryTest#nativeMainMapLongPressOpensBlankRequiredTypeFormAndReturnsWithoutChangingTheSession",
        "02-search-favorites" to "MapEditPresetSelectorTest#favoriteToggleDoesNotSelectAndSelectingDoesNotToggleFavorite",
        "03-new-readable-preview" to "MapEditEditorTest#previewShowsReadableFieldsAndTheFrozenTypeNameRatherThanCurrentCatalogName",
        "04-alias-delete-preview" to "MapEditCorrectionUiTest#existingPreviewShowsReadableBeforeAfterFieldsUsingFrozenTypeNamesAndExactAliasDeletion",
        "05-unsupported-fallback" to "MapEditCorrectionUiTest#closedHighwayUsesExternalEditorInsteadOfEditableForm",
    )

    fun capture(state: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("osmLightEditorCaptureRunUUID") ?: return
        check(Looper.myLooper() != Looper.getMainLooper())
        check(UUID.fromString(run).toString() == run && run != "00000000-0000-0000-0000-000000000000")
        val method = "org.iz.navigation.ui." + methods.getValue(state)
        val app = instrumentation.targetContext
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == QA && app.packageName == QA)
        check(instrumentation.context.packageName == "$QA.test")
        check(args.getString("osmLightEditorZipQaPackage") == QA)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(kotlin.math.abs(app.resources.configuration.fontScale - 1f) < .01f)
        if (Build.VERSION.SDK_INT < 34) error("Owned-window UiAutomation capture requires API34+")
        @Suppress("DEPRECATION")
        val packageInfo = app.packageManager.getPackageInfo(QA, PackageManager.GET_PERMISSIONS)
        check("android.permission.INTERNET" !in packageInfo.requestedPermissions.orEmpty())
        fun identity(key: String, length: Int) = requireNotNull(args.getString(key)).also {
            check(it.matches(Regex("[0-9a-f]{$length}")))
        }
        val source = identity("osmLightEditorCaptureSource", 40)
        val appSha = identity("osmLightEditorCaptureAppSha256", 64)
        val testSha = identity("osmLightEditorCaptureTestSha256", 64)
        val configSha = identity("osmLightEditorCaptureQaConfigSha256", 64)
        instrumentation.waitForIdleSync()
        var owned: Result<Pair<Activity, Window>>? = null
        instrumentation.runOnMainSync { owned = runCatching {
            val host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filter { it.packageName == QA && !it.isFinishing && !it.isDestroyed }.single()
            val windows = (listOf(host.window) + WindowInspector.getGlobalWindowViews()
                .flatMap { descendants(it) }.filterIsInstance<DialogWindowProvider>().map { it.window })
                .distinctBy { it.decorView }.filter {
                    it.decorView.context.activity() === host && it.decorView.isShown && it.decorView.hasWindowFocus()
                }
            val window = windows.single()
            check(window !== host.window) // All five allocated states are the actual owned dialog.
            check(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            if (state == "01-main-map-empty-type") check(host.javaClass.name == "org.iz.navigation.MainActivity")
            host to window
        } }
        val (host, window) = requireNotNull(owned).getOrThrow()
        val cache = app.cacheDir.canonicalFile
        val base = File(cache, "osm-light-editor-native")
        check(!Files.isSymbolicLink(base.toPath()) && base.canonicalFile.parentFile == cache)
        check(base.isDirectory || base.mkdir())
        val directory = File(base, run)
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == base.canonicalFile)
        check(directory.isDirectory || directory.mkdir())
        val png = File(directory, "$state.png")
        val json = File(directory, "$state.json")
        check(!png.exists() && !json.exists()) // Never overwrite earlier or partial evidence.
        val bitmap = checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .takeScreenshot(window))
        try {
            check(bitmap.width in 1..4096 && bitmap.height in 1..4096)
            val software = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            try {
                Files.newOutputStream(png.toPath(), CREATE_NEW, WRITE).use { file ->
                    val bounded = object : FilterOutputStream(file) {
                        private var count = 0L
                        override fun write(value: Int) { check(count < MAX_PNG); out.write(value); count++ }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            check(length.toLong() <= MAX_PNG - count)
                            out.write(bytes, offset, length); count += length
                        }
                    }
                    check(software.compress(Bitmap.CompressFormat.PNG, 100, bounded))
                    bounded.flush()
                }
            } finally { if (software !== bitmap) software.recycle() }
            check(png.length() in 1..MAX_PNG)
            val sha = MessageDigest.getInstance("SHA-256").digest(png.readBytes()).joinToString("") { "%02x".format(it) }
            val metadata = JSONObject().put("runUUID", run).put("state", state).put("method", method)
                .put("sourceArgument", source).put("appApkSha256Argument", appSha).put("testApkSha256Argument", testSha)
                .put("qaConfigSha256Argument", configSha).put("apkBinding", "HOST_VERIFICATION_REQUIRED")
                .put("appPackage", QA).put("testPackage", instrumentation.context.packageName)
                .put("versionName", packageInfo.versionName).put("versionCode", packageInfo.longVersionCode)
                .put("ownerActivity", host.javaClass.name).put("fontScale", app.resources.configuration.fontScale)
                .put("api", Build.VERSION.SDK_INT).put("captureMethod", "UIAUTOMATION_OWNED_DIALOG_WINDOW_NO_COMPOSITING")
                .put("png", png.name).put("bytes", png.length()).put("sha256", sha)
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("capturedAt", System.currentTimeMillis()).put("elapsedRealtime", SystemClock.elapsedRealtime())
                .put("captureStatus", "CAPTURED_PENDING_HOST_READBACK_UIUX").put("uiuxVerdict", "NOT_RUN")
            val bytes = metadata.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size <= 64 * 1024)
            Files.newOutputStream(json.toPath(), CREATE_NEW, WRITE).use { it.write(bytes) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext === this) null else baseContext.activity()
        else -> null
    }
}
