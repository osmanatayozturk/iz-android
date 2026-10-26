package org.iz.navigation.power

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.MainActivity
import org.iz.navigation.ui.DiaryState
import org.iz.navigation.ui.DiaryStatisticsScreen
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.JourneyStatsPanel
import org.junit.Rule
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.data.HealthMetric
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyHealthSummary
import org.iz.navigation.data.JourneyStats
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.security.MessageDigest

/** Five bounded checks for the separate verification package. No diary, account or sensor is modified. */
@RunWith(AndroidJUnit4::class)
class BatteryDeviceChecks {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun requireDedicatedVerificationPackage() {
        assertEquals("Focused checks must target the separate verification package", "org.iz.navigation.batterycheck", context.packageName)
    }

    @Test fun realBatteryReadDoesNotTurnUnsupportedValuesIntoMeasurements() {
        val manager = requireNotNull(context.getSystemService(BatteryManager::class.java))
        val capacityBefore = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentBefore = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeBefore = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val battery = PhoneBatteryReader.read(context)
        val capacityAfter = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentAfter = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeAfter = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        assertTrue("An available charge percentage must be bounded", battery.percent?.let { it in 0..100 } ?: true)
        if (capacityBefore == capacityAfter && capacityBefore in 0..100) assertEquals(capacityBefore, battery.percent)
        // CURRENT_NOW changes between reads; compare support semantics, not a volatile instantaneous value.
        // Android target >=28 uses MIN_VALUE for unsupported properties:
        // https://developer.android.com/reference/android/os/BatteryManager#getIntProperty(int)
        if (currentBefore == Int.MIN_VALUE && currentAfter == Int.MIN_VALUE) assertNull(battery.currentMicroAmps)
        if (chargeBefore == Int.MIN_VALUE && chargeAfter == Int.MIN_VALUE) assertNull(battery.chargeMicroAmpHours)
        assertNotEquals(Int.MIN_VALUE, battery.currentMicroAmps)
        assertNotEquals(Int.MIN_VALUE, battery.chargeMicroAmpHours)
        assertTrue(battery.temperatureC?.isFinite() ?: true)

        // Local missing-service context only; this never changes the phone's battery or a global service.
        val absent = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = if (name == Context.BATTERY_SERVICE) null else super.getSystemService(name)
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = null
        }
        assertEquals(PhoneBattery(null, null, null, null, null), PhoneBatteryReader.read(absent))
    }

    @Test fun productionPowerPolicyHasExactTwentyPercentBoundaryAndActiveVeto() {
        assertFalse("Unknown battery must wait", analysisPowerEligible(null, active = false))
        assertFalse("19 percent must wait", analysisPowerEligible(19, active = false))
        assertTrue("Exactly 20 percent may process while idle", analysisPowerEligible(20, active = false))
        assertTrue("21 percent may process while idle", analysisPowerEligible(21, active = false))
        for (percent in listOf(null, 19, 20, 21, 100)) {
            assertFalse("An active recording/navigation session always vetoes processing", analysisPowerEligible(percent, active = true))
        }
        assertFalse(analysisPowerEligible(-1, active = false))
        assertFalse(analysisPowerEligible(101, active = false))
    }

    @Test fun preparedCacheRoundTripPreservesMetadataAndSeparateHealthSources() = withFixtureDirectory { directory ->
        val journey = Journey(id = "synthetic-battery-journey", title = "SYNTHETIC_PRIVATE_TITLE", note = "SYNTHETIC_PRIVATE_NOTE",
            transport = Transport.RUN, startedAt = 1_000, endedAt = 61_000, stepCount = 12)
        val stats = JourneyStats(200.0, 60_000, 60_000, 50_000, 10_000, 0, 12.0, 14.4, 18.0)
        val health = JourneyHealthSummary(journey.id, heartRateMeanBpm = 123.0, heartRateMinBpm = 110.0,
            heartRateMaxBpm = 140.0, latestHeartRateBpm = 130.0, latestHeartRateAt = 31_000, heartRateSampleCount = 3,
            totalCaloriesKcal = 12.5, calorieCoverageMillis = 60_000, watchSteps = 111, stepCoverageMillis = 60_000,
            measurementStartAt = 1_000, measurementEndAt = 61_000, metricCheckedAt = mapOf(HealthMetric.STEPS to 65_000))
        val watch = JourneyHealthSummary(journey.id, heartRateMeanBpm = 87.0, heartRateMinBpm = 80.0,
            heartRateMaxBpm = 92.0, latestHeartRateBpm = 90.0, latestHeartRateAt = 31_000, heartRateSampleCount = 4,
            watchSteps = 222, stepCoverageMillis = 30_000, measurementStartAt = 1_000, measurementEndAt = 31_000)
        val fingerprint = "a".repeat(64)
        val entry = AnalysisEntry(fingerprint, stats, health, watch, journey)
        val cache = AnalysisCache(directory)
        cache.saveEntry(journey.id, entry)
        cache.save(mapOf(journey.id to entry), "b".repeat(64), daily = null, changed = 123)
        val restored = requireNotNull(AnalysisCache(directory).load().first.ready(journey.id))
        assertEquals(stats, restored.stats)
        assertEquals(health, restored.health)
        assertEquals(watch, restored.watch)
        assertEquals(111L, restored.health.watchSteps)
        assertEquals(222L, restored.watch.watchSteps)
        assertEquals(12.5, restored.health.totalCaloriesKcal!!, 0.0)
        assertNull("Direct watch does not synthesize Samsung Health calories", restored.watch.totalCaloriesKcal)
        assertEquals("", restored.journey.title)
        assertEquals("", restored.journey.note)
        assertTrue(restored.matches(journey.copy(title = "RENAMED", note = "UPDATED_NOTE")))
        assertFalse(restored.matches(journey.copy(id = "other-synthetic-id")))
        assertEquals(journey.id, restored.journey.id)
        assertEquals(Transport.RUN, restored.journey.transport)
        assertEquals(1_000L, restored.journey.startedAt)
        assertEquals(61_000L, restored.journey.endedAt)
        assertEquals(12L, restored.journey.stepCount)
        assertFalse(restored.journey.interrupted)
        assertEquals(JourneyStatus.CONFIRMED, restored.journey.status)
        assertFalse(restored.matches(journey.copy(transport = Transport.WALK)))
        assertFalse(restored.matches(journey.copy(startedAt = 1_001)))
        assertFalse(restored.matches(journey.copy(endedAt = 61_001)))
        assertFalse(restored.matches(journey.copy(stepCount = 13)))
        assertFalse(restored.matches(journey.copy(interrupted = true)))
        assertFalse(restored.matches(journey.copy(status = JourneyStatus.TEMPORARY)))
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            val text = file.readText()
            assertFalse("Derived cache must not duplicate titles", text.contains(journey.title))
            assertFalse("Derived cache must not duplicate notes", text.contains(journey.note))
        }
        assertNull(AnalysisCache(directory).entry(journey.id, "c".repeat(64)))
        assertEquals(restored, AnalysisCache(directory).entry(journey.id, fingerprint))
        cache.pending(true)
        assertNull("Pending work must hide prepared values", AnalysisCache(directory).load().first.ready(journey.id))
        cache.pending(false)
        assertNotNull(AnalysisCache(directory).load().first.ready(journey.id))
    }

    @Test fun diagnosticsExportIsReadOnlyAndRejectsLocationKeyAndHealthFields() {
        val directory = File(context.noBackupFilesDir, "power_diagnostics").apply { mkdirs() }
        val fixtureAt = System.currentTimeMillis() - 60_000
        val day = java.time.Instant.ofEpochMilli(fixtureAt).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val fixture = File(directory, "battery-$day-$fixtureAt.jsonl")
        val validSequence = Long.MAX_VALUE - 10
        val valid = diagnosticFixture(validSequence)
        val privateMarker = "SYNTHETIC_FORBIDDEN_PRIVATE_VALUE"
        val invalid = listOf(
            diagnosticFixture(validSequence + 1).put("latitude", 0.0),
            diagnosticFixture(validSequence + 2).put("longitude", 0.0),
            diagnosticFixture(validSequence + 3).put("apiKey", privateMarker),
            diagnosticFixture(validSequence + 4).put("heartRateBpm", 0.0),
            diagnosticFixture(validSequence + 5).put("address", privateMarker),
            diagnosticFixture(validSequence + 6).put("message", privateMarker),
            diagnosticFixture(validSequence + 7).put("token", privateMarker),
            // Allowed measurement keys must not become a channel for private strings either.
            diagnosticFixture(validSequence + 8).put("currentMicroAmps", privateMarker),
            // A fresh file's timestamp must not make a seven-day-old row exportable.
            diagnosticFixture(validSequence + 9, at = System.currentTimeMillis() - 8L * 86_400_000),
        )
        assertTrue("A unique synthetic file is required; never overwrite an existing chunk", fixture.createNewFile())
        try {
            fixture.writeText((listOf(valid) + invalid).joinToString("\n", postfix = "\n"))
            val bytes = digest(fixture.readBytes())
            val modified = fixture.lastModified()
            val size = fixture.length()
            val writer = StringWriter()
            PowerDiagnostics.dump(PrintWriter(writer))
            val exported = writer.toString()
            assertFalse("No forbidden synthetic value may escape the schema filter", exported.contains(privateMarker))
            val lines = exported.lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals("IZ_BATTERY_DIAGNOSTICS_V1", lines.firstOrNull())
            assertEquals("IZ_BATTERY_DIAGNOSTICS_END", lines.lastOrNull())
            val rows = lines.drop(1).dropLast(1).map(::JSONObject)
            assertTrue("Valid fixed-schema fixture must be exported", rows.any { it.getLong("sequence") == validSequence })
            assertFalse("Sensitive extension fields must never be exported", rows.any { it.getLong("sequence") in (validSequence + 1)..(validSequence + invalid.size) })
            rows.forEach { row ->
                assertEquals(DIAGNOSTIC_FIELDS, row.keys().asSequence().toSet())
                assertEquals(1, row.getInt("schema"))
                assertEquals("PROCESS_CUMULATIVE", row.getString("counterMode"))
                assertTrue(row.getString("analysisPhase") in AnalysisPhase.entries.map { it.name })
                val counters = row.getJSONObject("counters")
                assertEquals(PowerDiagnostics.Metric.entries.map { it.name }.toSet(), counters.keys().asSequence().toSet())
                counters.keys().asSequence().forEach { name ->
                    val metric = counters.getJSONObject(name)
                    assertEquals(setOf("count", "durationMs"), metric.keys().asSequence().toSet())
                    assertTrue(metric.getLong("count") >= 0)
                    assertTrue(metric.getLong("durationMs") >= 0)
                }
            }
            assertTrue("Dump must not remove its source", fixture.isFile)
            assertEquals("Dump must not rewrite its source", bytes, digest(fixture.readBytes()))
            assertEquals(modified, fixture.lastModified())
            assertEquals(size, fixture.length())
        } finally {
            // This exact synthetic file only. Existing app diagnostics are preserved.
            assertTrue(!fixture.exists() || fixture.delete())
        }
    }

    @Test fun actualLowBatteryKeepsTheDeferredManagerWaiting() = runBlocking {
        val before = PhoneBatteryReader.read(context).percent
        assumeTrue("Natural device battery must currently be below 20 percent; never override it", before != null && before < 20)
        val manager = PostJourneyAnalysis.get(context)
        val phase = withTimeoutOrNull(5_000) {
            manager.state.first { it.phase == AnalysisPhase.WAITING_BATTERY || it.phase == AnalysisPhase.FAILED }
        }
        val after = PhoneBatteryReader.read(context).percent
        assumeTrue("Battery naturally crossed the threshold during observation", after != null && after < 20)
        assertNotNull("Idle low-battery manager must reach a battery waiting phase within five seconds", phase)
        assertEquals(AnalysisPhase.WAITING_BATTERY, phase!!.phase)
        assertTrue(phase.pending)
        assertFalse(manager.canProcessNow())
    }

    private fun withFixtureDirectory(block: (File) -> Unit) {
        val base = context.cacheDir.canonicalFile
        val directory = Files.createTempDirectory(base.toPath(), "battery-check-").toFile().canonicalFile
        check(directory.parentFile == base)
        try { block(directory) } finally {
            // Cache fixtures have a flat layout; never recurse into app data or another task's files.
            directory.listFiles().orEmpty().forEach { file ->
                check(file.canonicalFile.parentFile == directory && file.isFile)
                assertTrue(file.delete())
            }
            assertTrue(directory.delete())
        }
    }

    private fun diagnosticFixture(sequence: Long, at: Long = System.currentTimeMillis()) = JSONObject().apply {
        put("schema", 1); put("sequence", sequence); put("at", at); put("elapsedMs", 1_000)
        put("version", "0.10.2-batterycheck"); put("code", 21); put("event", "SAMPLE")
        put("foreground", false); put("screenInteractive", false); put("recording", false); put("guidance", false)
        put("processing", false); put("pending", true); put("analysisPhase", "WAITING_BATTERY")
        put("batteryPercent", JSONObject.NULL); put("charging", JSONObject.NULL); put("temperatureC", JSONObject.NULL)
        put("currentMicroAmps", JSONObject.NULL); put("chargeMicroAmpHours", JSONObject.NULL); put("processCpuMs", 0)
        put("counterMode", "PROCESS_CUMULATIVE")
        put("counters", JSONObject().apply { PowerDiagnostics.Metric.entries.forEach {
            put(it.name, JSONObject().put("count", 0).put("durationMs", 0))
        } })
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val DIAGNOSTIC_FIELDS = setOf("schema", "sequence", "at", "elapsedMs", "version", "code", "event", "foreground",
            "screenInteractive", "recording", "guidance", "processing", "pending", "analysisPhase", "batteryPercent", "charging",
            "temperatureC", "currentMicroAmps", "chargeMicroAmpHours", "processCpuMs", "counterMode", "counters")
    }
}


/**
 * One isolated COMPONENT flow, using real Compose card input and the production statistics views.
 * This does not exercise MainActivity navigation, raw journeys, battery transitions or worker integration.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BatteryDeferredUiCheck {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @OptIn(ExperimentalTestApi::class)
    @Test fun pendingStatisticsCardOpensDeferredDetailsWithoutShowingStaleTotals() {
        assertEquals("org.iz.navigation.batterycheck", context.packageName)
        assertEquals("org.iz.navigation.batterycheck",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        val base = context.cacheDir.canonicalFile
        val directory = Files.createTempDirectory(base.toPath(), "battery-ui-fixture-").toFile().canonicalFile
        check(directory.parentFile == base)
        val selected = mutableStateOf<Journey?>(null)
        var taps = 0
        var activity: MainActivity? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var contentMounted = false
        val evidence = Files.createTempDirectory(base.toPath(), "battery-ui-evidence-").toFile().canonicalFile
        check(evidence.parentFile == base)
        // Capture only the verified QA Compose root, never a whole-device/system screenshot.
        fun foreground() {
            assertEquals("QA foreground required for input/capture", context.packageName,
                InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString())
            compose.runOnIdle {
                assertTrue(activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
                assertTrue(activity?.hasWindowFocus() == true)
            }
        }
        fun capture(name: String) {
            foreground()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(evidence, "$name.png")
            check(file.canonicalFile.parentFile == evidence && file.createNewFile())
            file.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            println("IZ_BATTERY_UI_EVIDENCE=" + file.absolutePath)
        }
        try {
            val journey = Journey(id = "battery-ui-synthetic", title = "Pil QA sentetik yolculuk",
                transport = Transport.WALK, startedAt = 1_000, endedAt = 61_000)
            val fingerprint = "d".repeat(64)
            val entry = AnalysisEntry(fingerprint,
                JourneyStats(200.0, 60_000, 60_000, 50_000, 10_000, 0, 12.0, 14.4, 18.0),
                JourneyHealthSummary(journey.id), JourneyHealthSummary(journey.id), journey)
            val cache = AnalysisCache(directory)
            cache.saveEntry(journey.id, entry)
            cache.save(mapOf(journey.id to entry), "e".repeat(64), daily = null, changed = 123)
            cache.pending(true)
            assertNotNull("Prepared values must actually exist behind the pending gate", cache.entry(journey.id, fingerprint))
            // WAITING_BATTERY is a UI fixture state, not an assertion about the real battery.
            val state = DiaryState(journeys = listOf(journey),
                analysis = cache.load().first.copy(phase = AnalysisPhase.WAITING_BATTERY))
            assertTrue(state.analysis.pending)
            assertNull(state.prepared(journey))
            val before = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
            val launched = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            scenario = launched
            launched.onActivity { host ->
                activity = host
                host.setContent { IzTheme {
                    val detail = selected.value
                    if (detail == null) DiaryStatisticsScreen(state, now = 61_000,
                        onJourney = { selected.value = it; taps++ })
                    else Column(Modifier.fillMaxSize()) {
                        JourneyStatsPanel(detail, state.prepared(detail)?.stats, state.analysisLabel)
                    }
                } }
            }
            contentMounted = true
            compose.waitUntil(5_000) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
            }
            foreground()
            compose.onNodeWithText(state.analysisLabel).assertIsDisplayed()
            compose.onNodeWithText("0,2 km kaydedildi").assertDoesNotExist()
            // Existing semantics resolve current bounds; touch injection is not performClick's semantic action.
            // https://developer.android.com/develop/ui/compose/testing/apis
            val card = compose.onNode(hasText(journey.title) and hasClickAction()).assertIsDisplayed()
            val firstBounds = card.fetchSemanticsNode().boundsInRoot
            compose.waitForIdle()
            val secondBounds = card.fetchSemanticsNode().boundsInRoot
            assertEquals("Stop instead of tapping a moving target", firstBounds, secondBounds)
            foreground()
            card.performTouchInput { click() }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Yolculuk istatistikleri").fetchSemanticsNodes().size == 1
            }
            foreground()
            compose.onNodeWithText("Yolculuk istatistikleri").assertIsDisplayed()
            compose.onNodeWithText(state.analysisLabel).assertIsDisplayed()
            compose.onNodeWithText("Konum kayıtların korunuyor. Ayrıntılar kayıt ve navigasyon bittikten sonra pil en az %20 olduğunda hazırlanır.")
                .assertIsDisplayed()
            compose.onNodeWithText("Mesafe").assertDoesNotExist()
            compose.onNodeWithText("0,20 km").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, taps); assertEquals(journey, selected.value) }
            val after = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
            assertEquals("Opening a pending detail must preserve the exact synthetic persisted fixture", before, after)
            assertTrue(cache.load().first.pending)
            assertNotNull(cache.entry(journey.id, fingerprint))
            assertNull(cache.load().first.ready(journey.id))
            val writer = StringWriter()
            PowerDiagnostics.dump(PrintWriter(writer))
            val dump = writer.toString()
            assertTrue(dump.startsWith("IZ_BATTERY_DIAGNOSTICS_V1"))
            assertTrue(dump.trimEnd().endsWith("IZ_BATTERY_DIAGNOSTICS_END"))
            assertFalse(dump.contains(journey.id))
            assertFalse(dump.contains(journey.title))
            capture("success")
        } catch (failure: Throwable) {
            if (contentMounted) runCatching { capture("failure") }
            throw failure
        } finally {
            try {
                if (contentMounted) compose.runOnIdle { selected.value = null }
            } finally {
                try { scenario?.close() } finally {
                    // Only our newly created flat fixture; retained images are evidence, not app data.
                    directory.listFiles().orEmpty().forEach { file ->
                        check(file.canonicalFile.parentFile == directory && file.isFile)
                        check(file.delete())
                    }
                    check(directory.delete())
                }
            }
        }
    }
}
