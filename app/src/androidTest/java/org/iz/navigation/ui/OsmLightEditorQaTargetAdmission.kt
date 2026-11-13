package org.iz.navigation.ui

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.iz.navigation.BuildConfig

/** Test-only target gate. Host must separately seal source/config and authorize each fresh run.
 * This validates the APKs actually installed; source/config arguments are host attestations,
 * not an in-app proof that a source tree or config produced those APKs.
 */
internal object OsmLightEditorQaTargetAdmission {
    private const val QA = "org.iz.navigation.qalighteditor20261113"
    private const val PREFIX = "osmLightEditorAdmission"
    private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"
    private const val MAX_AGE_MS = 15L * 60 * 1000
    private const val MAX_LEASE_MS = 60L * 60 * 1000
    private var admittedProof: List<String>? = null
    private var admittedApks: Pair<String, String>? = null

    @Synchronized
    fun requireAdmittedTarget() {
        val args = InstrumentationRegistry.getArguments()
        when (args.getString("osmLightEditorQaTarget") ?: "emulator") {
            "emulator" -> {
                check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Allocated emulator required" }
                return
            }
            "physical" -> Unit
            else -> error("Unknown QA target mode")
        }
        check(Looper.myLooper() != Looper.getMainLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val test = instrumentation.context
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == QA && app.packageName == QA)
        check(test.packageName == "$QA.test" && args.getString("osmLightEditorZipQaPackage") == QA)
        for (context in listOf(app, test)) {
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            check(Manifest.permission.INTERNET !in info.requestedPermissions.orEmpty())
            check(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "Only the sealed standalone APK is admitted" }
        }
        check(app.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
        check(kotlin.math.abs(app.resources.configuration.fontScale - 1f) < .01f)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            check(ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_DENIED)
        }
        fun required(suffix: String): String = requireNotNull(args.getString(PREFIX + suffix)) {
            "Missing admission field $suffix"
        }.also { check(it.isNotBlank() && it.length <= 1024 && it == it.trim()) }
        fun digest(suffix: String, length: Int = 64): String = required(suffix).also {
            check(it.matches(Regex("[0-9a-f]{$length}"))) { "Malformed admission digest $suffix" }
        }
        val run = required("RunUUID")
        check(UUID.fromString(run).toString() == run && run != ZERO_UUID)
        val source = digest("Source", 40)
        val appSha = digest("AppSha256")
        val testSha = digest("TestSha256")
        val configSha = digest("QaConfigSha256")
        val serialSha = digest("SerialSha256")
        val fingerprint = required("Fingerprint")
        val api = required("Api")
        val model = required("Model")
        val hardware = required("Hardware")
        val manufacturer = required("Manufacturer")
        val abi = required("Abi")
        val issued = required("IssuedAtEpochMs").toLong()
        val expires = required("ExpiresAtEpochMs").toLong()
        check(issued > 0 && expires > issued && expires - issued <= MAX_LEASE_MS)
        val now = System.currentTimeMillis()
        check(now >= issued && now < expires)
        val proof = listOf(run, source, appSha, testSha, configSha, serialSha, fingerprint, api,
            model, hardware, manufacturer, abi, issued.toString(), expires.toString())
        if (admittedProof == null) check(now - issued <= MAX_AGE_MS) { "Fresh per-invocation admission required" }
        else check(admittedProof == proof) { "Admission cannot change within an instrumentation invocation" }
        check(Build.HARDWARE !in setOf("ranchu", "goldfish"))
        check(Build.FINGERPRINT == fingerprint && Build.VERSION.SDK_INT.toString() == api)
        check(Build.MODEL == model && Build.HARDWARE == hardware && Build.MANUFACTURER == manufacturer)
        check(Build.SUPPORTED_ABIS.firstOrNull() == abi)
        args.getString("osmLightEditorCaptureRunUUID")?.let { captureRun ->
            check(captureRun == run) { "Capture and admission run UUIDs must match" }
            for ((suffix, expected) in listOf("Source" to source, "AppSha256" to appSha,
                "TestSha256" to testSha, "QaConfigSha256" to configSha)) {
                check(args.getString("osmLightEditorCapture$suffix") == expected) { "Capture identity mismatch: $suffix" }
            }
        }
        // Fixed read-only property; never use privileged Build.getSerial, reflection or a runner command.
        val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("/system/bin/getprop ro.serialno")
        val serialBytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArray(1025)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            check(count in 1..1024) { "Missing or oversized read-only serial proof" }
            bytes.copyOf(count)
        }
        val serial = serialBytes.toString(Charsets.UTF_8).trim()
        check(serial.isNotBlank() && !serial.equals("unknown", ignoreCase = true))
        check(sha256(serial.toByteArray(Charsets.UTF_8)) == serialSha) { "Physical target mismatch" }
        val apkHashes = admittedApks ?: (apkSha(app) to apkSha(test))
        check(apkHashes.first == appSha && apkHashes.second == testSha) { "Installed APKs differ from the sealed pair" }
        admittedApks = apkHashes
        admittedProof = proof // Only a complete success can admit later checks in this invocation.
    }

    private fun apkSha(context: Context): String {
        val file = File(context.applicationInfo.sourceDir)
        check(file.isFile && file.length() in 1..256L * 1024 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
