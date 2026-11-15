package org.iz.navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterOutputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.iz.navigation.integration.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production owner/panel with fake IO. No map, network, production package or live diagnosis proof. */
@RunWith(AndroidJUnit4::class)
class NearbyDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var owner: PlaceInformationDiscoveryOwner
    private val secret = "PRIVATE https://private.invalid/?token=private 39.12345,32.98765"

    @Before fun requireIsolatedOfflineQa() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.iz.navigation.qalighteditor20261113", app.packageName)
        OsmLightEditorQaTargetAdmission.requireAdmittedTarget()
        @Suppress("DEPRECATION")
        assertFalse(Manifest.permission.INTERNET in app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty())
    }

    private fun show(loader: suspend (Double, Double) -> List<MapPlaceCandidate>) {
        compose.setContent { IzTheme {
            val scope = rememberCoroutineScope()
            owner = remember { PlaceInformationDiscoveryOwner(scope, loader, { error("No information action expected") }, { "synthetic" }) }
            Text("Sentetik yakındaki yerler testi")
            PlaceInformationDiscoveryContent(owner)
        } }
        compose.runOnIdle { owner.tap(0.0, 0.0, 17.0) }
        compose.waitUntil(5_000) { owner.state.retryable }
    }

    @Test fun technicalDetailsToggleKeepsRetryAccessible() {
        var reads = 0
        show { _, _ -> if (++reads == 1) throw UnknownHostException(secret) else emptyList() }
        val toggle = compose.onNodeWithTag("nearby-diagnostics-toggle")
        toggle.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Teknik ayrıntı")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kapalı"))
        compose.onNodeWithTag("nearby-diagnostics-details").assertDoesNotExist()
        capture("01-closed", "technicalDetailsToggleKeepsRetryAccessible")
        toggle.performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Açık"))
        compose.onNodeWithText("Hata kodu: ND_DNS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aşama: Ağ bağlantısı").assertExists()
        compose.onAllNodesWithText("HTTP durumu", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(secret, substring = true).assertCountEquals(0)
        capture("02-open", "technicalDetailsToggleKeepsRetryAccessible")
        compose.onNodeWithTag("place-info-discovery-retry").performScrollTo().assertIsDisplayed().assertIsEnabled()
        toggle.performScrollTo().performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kapalı"))
        compose.onNodeWithTag("nearby-diagnostics-details").assertDoesNotExist()
        compose.onNodeWithTag("place-info-discovery-retry").performScrollTo().performClick()
        compose.waitUntil(5_000) { !owner.state.busy }
        compose.runOnIdle { assertEquals(2, reads); assertNull(owner.state.diagnostic) }
        toggle.assertDoesNotExist()
    }

    @Test fun newAttemptClearsDiagnosticAndCollapsesDetails() {
        var reads = 0
        val next = CompletableDeferred<Unit>()
        show { _, _ ->
            if (++reads == 1) throw NearbyFailureException(NearbyFailure(NearbyCode.ND_SERVICE_WAIT,
                NearbyStage.HTTP, httpStatus = 429, waitUntilMillis = 0, limits = listOf(NearbyLimit.SERVICE_WAIT)))
            next.await()
            throw ConnectException(secret)
        }
        compose.onNodeWithTag("nearby-diagnostics-toggle").performScrollTo().performClick()
        compose.onNodeWithText("HTTP durumu: 429").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tekrar için bekleme: 0 sn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dolmuş sınır: Servis bekleme süresi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("place-info-discovery-retry").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(owner.state.busy); assertNull(owner.state.diagnostic); assertEquals(2, reads) }
        compose.onNodeWithTag("nearby-diagnostics-toggle").assertDoesNotExist()
        compose.onNodeWithTag("nearby-diagnostics-details").assertDoesNotExist()
        compose.runOnIdle { next.complete(Unit) }
        compose.waitUntil(5_000) { owner.state.retryable }
        compose.onNodeWithTag("nearby-diagnostics-toggle").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kapalı"))
        compose.onNodeWithTag("nearby-diagnostics-details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(NearbyCode.ND_CONNECTION, owner.state.diagnostic!!.code); assertEquals(2, reads) }
        capture("03-new-attempt-collapsed", "newAttemptClearsDiagnosticAndCollapsesDetails")
    }

    /** Optional synthetic evidence only: crop the owned Compose panel, never the desktop/window. */
    private fun capture(state: String, method: String) {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("nearbyDiagnosticsCapture") != "true") return
        OsmLightEditorQaTargetAdmission.requireAdmittedTarget()
        val run = requireNotNull(args.getString("osmLightEditorAdmissionRunUUID"))
        check(UUID.fromString(run).toString() == run && run != "00000000-0000-0000-0000-000000000000")
        fun digest(suffix: String, length: Int = 64) = requireNotNull(args.getString("osmLightEditorAdmission$suffix"))
            .also { check(it.matches(Regex("[0-9a-f]{$length}"))) }
        val source = digest("Source", 40)
        val appSha = digest("AppSha256")
        val testSha = digest("TestSha256")
        val config = digest("QaConfigSha256")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = app.cacheDir.canonicalFile
        val base = File(cache, "nearby-diagnostics-compose")
        check(!Files.isSymbolicLink(base.toPath()) && base.canonicalFile.parentFile == cache)
        check(base.isDirectory || base.mkdir())
        val directory = File(base, run)
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == base.canonicalFile)
        check(directory.isDirectory || directory.mkdir())
        val png = File(directory, "$state.png")
        val json = File(directory, "$state.json")
        check(!png.exists() && !json.exists())
        val bitmap = compose.onNodeWithTag("place-info-discovery").captureToImage().asAndroidBitmap()
        check(bitmap.width in 1..4096 && bitmap.height in 1..4096)
        Files.newOutputStream(png.toPath(), CREATE_NEW, WRITE).use { output ->
            val bounded = object : FilterOutputStream(output) {
                private var count = 0L
                override fun write(value: Int) { check(count < 16 * 1024 * 1024); out.write(value); count++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    check(count + length <= 16 * 1024 * 1024); out.write(bytes, offset, length); count += length
                }
            }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded))
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(png.readBytes()).joinToString("") { "%02x".format(it) }
        val metadata = JSONObject().put("runUUID", run).put("state", state).put("method", method)
            .put("sourceArgument", source).put("appApkSha256Argument", appSha).put("testApkSha256Argument", testSha)
            .put("qaConfigSha256Argument", config).put("apkBinding", "HOST_VERIFICATION_REQUIRED")
            .put("captureMethod", "COMPOSE_OWNED_PANEL_CROP_NO_COMPOSITING")
            .put("evidencePurpose", "SYNTHETIC_COMPONENT_ONLY_NOT_MAIN_MAP_OR_LIVE_SERVICE")
            .put("png", png.name).put("sha256", sha).put("bytes", png.length())
            .put("width", bitmap.width).put("height", bitmap.height).put("capturedAt", System.currentTimeMillis())
        Files.newOutputStream(json.toPath(), CREATE_NEW, WRITE).use { it.write(metadata.toString(2).toByteArray()) }
    }
}
