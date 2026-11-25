package org.iz.navigation.lean

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.DiaryBackup
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.LeanReportDiagnostics
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeanDiagnosticReportAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rejectedGpsStillProducesSensorCountsWhileLegacyReportRemainsUnknown() {
        val report = syntheticRoomReport()
        val measured = report.sessionDetails.single { it.sensorTiming != null }
        val historical = report.sessionDetails.single { it.sensorTiming == null }
        compose.setContent {
            IzTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LeanReportDiagnostics(report.sessionDetails, "Gidon")
                }
            }
        }
        fun inSession(session: LeanReportSession, text: String) =
            hasText(text) and hasAnyAncestor(hasTestTag("lean-report-session-${session.sessionId}"))
        compose.onNode(inSession(measured, "Kaydedilen örnek · 3 örnek")).performScrollTo().assertIsDisplayed()
        compose.onNode(inSession(measured, "Kaydedilen örnek · 0 örnek")).performScrollTo().assertIsDisplayed()
        compose.onNode(inSession(measured, "GPS hız doğruluğu bilgisi eksik · 2 ölçüm aralığı · 2,00 sn"))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(inSession(historical, "Sensör ayrıntıları bilinmiyor.")).performScrollTo().assertIsDisplayed()
        compose.onNode(inSession(historical, "Bilinmiyor")).performScrollTo().assertIsDisplayed()
        compose.onNode(inSession(historical, "Kaydedilen örnek · 0 örnek")).assertDoesNotExist()
    }

    private fun syntheticRoomReport() = runBlocking(Dispatchers.IO) {
        val context = requireLeanDiagnosticEmulator()
        assertAndroidFixFieldPresence()
        val database: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val diary = database.diaryDao()
            val store = LeanStore(context, database)
            val trip = Journey(transport = Transport.MOTORCYCLE, startedAt = UTC_START)
            val profile = LeanCalibration(
                epoch = "qa-handlebar", createdAt = UTC_START - 1L,
                uprightGravity = listOf(0.0, 0.0, 9.81), gyroBias = listOf(0.0, 0.0, 0.0),
                bodyConfirmed = false, steeringAxis = listOf(0.0, 0.0, 1.0),
            )
            diary.save(trip)
            val current = requireNotNull(store.begin(trip.id, profile, "qa-boot", UTC_START, START))
            val raw = listOf(
                acceleration(1, 200_000_000), acceleration(2, 250_000_000),
                acceleration(3, 400_000_000),
                poorFix(1_000), poorFix(2_000), poorFix(3_000),
            )
            assertTrue(store.append(current, raw))
            assertEquals(1, database.leanDao().chunkInfos(current.id).size)
            store.seal(current, START + 5_000_000_000)

            val legacyUtc = UTC_START + 10_000
            val legacyStart = START + 10_000_000_000
            val legacy = requireNotNull(store.begin(trip.id, profile, "qa-boot", legacyUtc, legacyStart))
            store.seal(legacy, legacyStart + 1_000_000_000)

            val bodyStart = START + 12_000_000_000
            val bodyUtc = UTC_START + 12_000
            val body = requireNotNull(store.begin(trip.id, profile.copy(
                epoch = "qa-body", bodyConfirmed = true, steeringAxis = emptyList(),
            ), "qa-boot", bodyUtc, bodyStart))
            val bodySample = LeanImuRecord(LeanSensorKind.GYROSCOPE, 1L, bodyStart + 300_000_000L,
                0.0, 0.0, 0.0, 3)
            assertTrue(store.append(body, listOf(bodySample)))
            assertEquals(1, database.leanDao().chunkInfos(body.id).size)
            store.seal(body, bodyStart + 1_000_000_000L)
            diary.save(trip.copy(endedAt = UTC_START + 15_000))
            val legacyInput = store.pendingInputs().single { it.token.id == legacy.id }
            val oldResult = LeanEstimate(
                algorithmVersion = HandlebarLeanEstimator.VERSION, calibrationEpoch = profile.epoch,
                leftDegrees = null, rightDegrees = null, validMillis = 0, observedMillis = 1_000,
                partialReasons = emptySet(), curves = emptyList(),
            )
            assertTrue(store.commitResult(legacy.id, legacyInput.inputHash, oldResult) {})
            store.processPending(LeanEstimatorV1()) {}

            val fullReport = store.observeReport(trip.id).first()
            val report = requireNotNull(fullReport.handlebar)
            val bodyDetails = fullReport.sessionDetails.single()
            assertEquals(body.id, bodyDetails.sessionId)
            assertEquals("qa-body", bodyDetails.calibrationEpoch)
            assertEquals(LeanStreamTiming(1L, 300_000_000L, 300_000_000L, null), bodyDetails.sensorTiming?.gyroscope)
            assertEquals(LeanStreamTiming(0L, null, null, null), bodyDetails.sensorTiming?.accelerometer)
            for (session in listOf(current, legacy, body)) {
                assertTrue("Completed session still owns raw Room chunks", database.leanDao().chunkInfos(session.id).isEmpty())
            }
            assertFalse(report.pending)
            assertEquals(0L, report.validMillis)
            assertEquals(6_000L, report.observedMillis)
            assertNull(report.leftDegrees)
            assertNull(report.rightDegrees)
            assertEquals(setOf(current.id, legacy.id), report.sessionDetails.map { it.sessionId }.toSet())
            val observed = report.sessionDetails.single { it.sessionId == current.id }
            assertEquals(profile.epoch, observed.calibrationEpoch)
            assertEquals(UTC_START, observed.startedAtUtc)
            val timing = requireNotNull(observed.sensorTiming)
            assertEquals(5_000_000_000L, timing.durationNanos)
            assertEquals(LeanStreamTiming(3L, 200_000_000L, 400_000_000L, 150_000_000L), timing.accelerometer)
            assertEquals(LeanStreamTiming(0L, null, null, null), timing.gyroscope)
            val issues = requireNotNull(observed.gnssDiagnostics).issues
            assertTrue(LeanGnssIssue("GNSS_SPEED_ACCURACY_MISSING", "pair", 2L, 2_000_000_000L) in issues)
            assertTrue(LeanGnssIssue("GNSS_TIME_UNCERTAINTY_MISSING", "pair", 2L, 2_000_000_000L) in issues)
            val historical = report.sessionDetails.single { it.sessionId == legacy.id }
            assertEquals(legacyUtc, historical.startedAtUtc)
            assertNull(historical.sensorTiming)
            assertNull(historical.gnssDiagnostics)
            assertEquals(fullReport, roundTrip(context, database, trip.id))
            report
        } finally {
            database.close()
        }
    }

    private fun assertAndroidFixFieldPresence() {
        fun location(offsetMs: Long) = Location("gps").apply {
            elapsedRealtimeNanos = START + offsetMs * 1_000_000L
            time = UTC_START + offsetMs
            latitude = .25
            longitude = -140.0
            speed = 12f
            accuracy = 3f
            bearing = 90f
            elapsedRealtimeUncertaintyNanos = 4_000_000.0
        }
        val absent = location(1_000)
        val invalid = location(2_000).apply {
            speedAccuracyMetersPerSecond = -1f
            bearingAccuracyDegrees = Float.NaN
        }
        assertFalse(absent.hasSpeedAccuracy())
        assertTrue(invalid.hasSpeedAccuracy())
        assertTrue(invalid.hasBearingAccuracy())
        val records = listOf(absent, invalid).map { requireNotNull(it.toLeanFix()) }
        val decoded = LeanCodec.decodeRecords(LeanCodec.encodeRecords(records)).filterIsInstance<LeanGnssRecord>().toList()
        assertEquals(records, decoded)
        assertNull(decoded[0].speedAccuracyMps)
        assertEquals(-1.0, requireNotNull(decoded[1].speedAccuracyMps), 0.0)
        assertNull(decoded[1].bearingAccuracyDegrees)
        assertEquals(setOf(LeanGnssIssueCode.GNSS_SPEED_ACCURACY_MISSING),
            LeanGnssQuality.fixIssues(decoded[0], LeanMount.HANDLEBAR))
        assertEquals(setOf(LeanGnssIssueCode.GNSS_SPEED_ACCURACY_OUT_OF_RANGE),
            LeanGnssQuality.fixIssues(decoded[1], LeanMount.HANDLEBAR))
        assertTrue(LeanGnssIssueCode.GNSS_BEARING_ACCURACY_MISSING in
            LeanGnssQuality.movingHandlebarIssues(decoded[1], 20.0, 1.5, 30.0))
    }

    private suspend fun roundTrip(context: Context, source: DiaryDatabase, journeyId: String): LeanJourneyReport {
        val directory = File(context.cacheDir, "camera")
        check(directory.mkdirs() || directory.isDirectory)
        val archive = File.createTempFile("qa-lean-diagnostics-", ".zip", directory)
        val restored: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
            DiaryBackup(context, DiaryRepository(context, source)).exportTo(uri)
            DiaryBackup(context, DiaryRepository(context, restored)).importFrom(uri)
            val report = LeanStore(context, restored).observeReport(journeyId).first()
            for (session in report.sessionDetails + requireNotNull(report.handlebar).sessionDetails) {
                assertTrue(restored.leanDao().chunkInfos(session.sessionId).isEmpty())
            }
            return report
        } finally {
            restored.close()
            archive.delete()
        }
    }

    private fun acceleration(sequence: Long, offset: Long) =
        LeanImuRecord(LeanSensorKind.ACCELEROMETER, sequence, START + offset, 0.0, 0.0, 9.81, 3)

    private fun poorFix(offsetMillis: Long) = LeanGnssRecord(
        elapsedNanos = START + offsetMillis * 1_000_000, utcMillis = UTC_START + offsetMillis,
        latitude = 0.25, longitude = -140.0 + (offsetMillis / 1000L) * .0001, accuracyMeters = 3.0,
        speedMps = 12.0, bearingDegrees = 90.0, speedAccuracyMps = null, bearingAccuracyDegrees = null,
        elapsedUncertaintyNanos = null, mock = false, provider = "gps",
    )

    private companion object {
        const val START = 1_000_000_000L
        const val UTC_START = 1_736_899_200_000L
    }
}

internal const val LEAN_DIAGNOSTIC_QA_PACKAGE = "org.iz.navigation.qayatist20261125"

internal fun requireLeanDiagnosticEmulator(): Context {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val expectedPackage = InstrumentationRegistry.getArguments().getString("qaTargetPackage")
    assertEquals(LEAN_DIAGNOSTIC_QA_PACKAGE, expectedPackage)
    assertEquals(LEAN_DIAGNOSTIC_QA_PACKAGE, context.packageName)
    assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
    assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON))
    @Suppress("DEPRECATION")
    val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty()
    assertFalse(Manifest.permission.INTERNET in permissions)
    return context
}
