package org.iz.navigation.lean

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class HandlebarLocationTimeAcceptanceTest {
    @Test
    fun androidMetadataAndCallbackTimeSurviveRoomAsSeparateFields() {
        runBlocking(Dispatchers.IO) {
            withTimingRoom(requireTimingQa(emulator = true)) { db, store ->
                val values = listOf(null, 0.0, 2_500_000.0, 100_000_000.0, 100_000_001.0,
                    -1.0, Double.NaN, Double.POSITIVE_INFINITY)
                val locations = values.mapIndexed { i, value -> syntheticFix(i, 1, value) }
                assertEquals(values.map { it != null }, locations.map { it.hasElapsedRealtimeUncertaintyNanos() })
                val supplied = locations.map { it.elapsedRealtimeNanos + 30_000_000L }
                val rows = locations.zip(supplied).map { (location, receipt) ->
                    val callback = LocationResult.create(listOf(location))
                    requireNotNull(location.toLeanFix(callback.singleFixReceipt(receipt)))
                }
                val batch = LocationResult.create(locations.take(2))
                assertNull(batch.singleFixReceipt(supplied[0]))
                val first = locations.first()
                assertNull(requireNotNull(first.toLeanFix(batch.singleFixReceipt(supplied[0]))).singleFixReceivedElapsedNanos)
                assertNull(requireNotNull(first.toLeanFix(first.elapsedRealtimeNanos - 1)).singleFixReceivedElapsedNanos)
                val (_, decoded) = persist(db, store, rows)
                val fixes = decoded.filterIsInstance<LeanGnssRecord>()
                assertEquals(rows, fixes)
                assertEquals(values.take(6) + listOf(null, null), fixes.map { it.elapsedUncertaintyNanos })
                assertEquals(List(6) { false } + listOf(true, true), fixes.map { it.invalidElapsedUncertainty })
                assertEquals(supplied.take(6) + listOf(null, null), fixes.map { it.singleFixReceivedElapsedNanos })
                for (index in listOf(6, 7)) {
                    assertEquals(setOf(LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE),
                        LeanGnssQuality.fixIssues(fixes[index], LeanMount.HANDLEBAR))
                }
            }
        }
    }

    @Test
    fun bothSignedTurnsReachRoomReportWithVerifiedCallbackOrReportedUncertainty() {
        runBlocking(Dispatchers.IO) {
            withTimingRoom(requireTimingQa(emulator = true)) { db, store ->
                for (direction in listOf(-1, 1)) {
                    for (uncertainty in listOf(null, 0.0, 2_500_000.0, 100_000_000.0)) {
                        val estimated = uncertainty == null
                        val rows = turn(direction, uncertainty, if (estimated) 30_000_000L else null,
                            usePhoneCadence = estimated && direction < 0)
                        val (token, decoded) = persist(db, store, rows)
                        val report = finishReport(db, store, token)
                        assertEquals(1, report.curves.size)
                        assertEquals(estimated, report.curves.single().timingEstimated)
                        assertEquals(estimated, "HANDLEBAR_TIMING_ESTIMATED" in report.partialReasons)
                        assertEquals(12_000L, report.observedMillis)
                        assertTrue("The whole supported arc is observed", report.validMillis >= 11_000L)
                        val peak = if (direction < 0) report.leftDegrees else report.rightDegrees
                        assertEquals(20.0, requireNotNull(peak), 0.0)
                        assertNull(if (direction < 0) report.rightDegrees else report.leftDegrees)
                        assertTrue(report.sessionDetails.single().gnssDiagnostics?.issues.orEmpty().isEmpty())
                        if (estimated) {
                            assertNull("Live display must require reported time quality", live(decoded))
                            assertEquals("handlebar-gnss-imu-v2", report.algorithmVersions.single())
                        } else {
                            assertEquals(direction * 20.158, requireNotNull(live(decoded)), .03)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun invalidOrUnsupportedTimingKeepsExplicitReasonThroughCompletedRoomReport() {
        runBlocking(Dispatchers.IO) {
            withTimingRoom(requireTimingQa(emulator = true)) { db, store ->
                val validReceipt = 30_000_000L
                val cases = listOf(
                    Reject(null, null, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_MISSING),
                    Reject(null, 250_000_001L, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_MISSING),
                    Reject(null, validReceipt, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_MISSING) {
                        if ((time - SIMULATED_UTC) / 1_000L % 2L == 1L) provider = "network"
                    },
                    Reject(100_000_001.0, validReceipt, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE),
                    Reject(-1.0, validReceipt, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE),
                    Reject(Double.NaN, validReceipt, LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE),
                    Reject(2_500_000.0, null, LeanGnssIssueCode.GNSS_SPEED_ACCURACY_MISSING) {
                        removeSpeedAccuracy()
                    },
                    Reject(2_500_000.0, null, LeanGnssIssueCode.GNSS_BEARING_ACCURACY_MISSING) {
                        removeBearingAccuracy()
                    },
                    Reject(2_500_000.0, null, LeanGnssIssueCode.CLOCK_DISCONTINUITY) {
                        time += 5_000L
                    },
                )
                for (case in cases) {
                    val rows = turn(1, case.uncertainty, case.receiptAge, changeLocation = case.changeLocation)
                    val (token, decoded) = persist(db, store, rows)
                    val report = finishReport(db, store, token)
                    assertEquals(0L, report.validMillis)
                    assertNull(report.leftDegrees)
                    assertNull(report.rightDegrees)
                    assertTrue(report.curves.isEmpty())
                    assertFalse("HANDLEBAR_TIMING_ESTIMATED" in report.partialReasons)
                    assertEquals(listOf(LeanGnssIssue(case.reason.name, "pair", 12, 12 * TIMING_SECOND)),
                        report.sessionDetails.single().gnssDiagnostics?.issues)
                    // Live's relative clock has a separate origin; a uniform UTC offset only invalidates the saved report.
                    if (case.reason != LeanGnssIssueCode.CLOCK_DISCONTINUITY) assertNull(case.reason.name, live(decoded))
                }
                assertEquals("GPS zaman doğruluğu bilgisi eksik",
                    leanGnssIssueLabel(LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_MISSING.name))
                assertEquals("GPS zaman doğruluğu yetersiz",
                    leanGnssIssueLabel(LeanGnssIssueCode.GNSS_TIME_UNCERTAINTY_OUT_OF_RANGE.name))
                assertEquals("GPS ölçüm saati tutarsız", leanGnssIssueLabel(LeanGnssIssueCode.CLOCK_DISCONTINUITY.name))
            }
        }
    }

    private data class Reject(
        val uncertainty: Double?,
        val receiptAge: Long?,
        val reason: LeanGnssIssueCode,
        val changeLocation: Location.() -> Unit = {},
    )

    private fun turn(
        direction: Int, uncertainty: Double?, receiptAge: Long?,
        changeLocation: Location.() -> Unit = {}, usePhoneCadence: Boolean = false,
    ): List<LeanRecord> {
        val yaw = direction * TURN_RATE
        val bank = atan(SPEED * yaw / GRAVITY)
        val accelHz = if (usePhoneCadence) 115 else 50
        val gyroHz = if (usePhoneCadence) 46 else 50
        val rows = buildList {
            for (i in 0..12 * accelHz) {
                add(LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(),
                    SIMULATED_START + i * TIMING_SECOND / accelHz,
                    0.0, 0.0, hypot(GRAVITY, SPEED * yaw), 3))
            }
            for (i in 0..12 * gyroHz) {
                add(LeanImuRecord(LeanSensorKind.GYROSCOPE, i.toLong(),
                    SIMULATED_START + i * TIMING_SECOND / gyroHz,
                    0.0, -yaw * sin(bank), .02 - yaw * cos(bank), 3))
            }
            for (i in 0..12) {
                val clockSkewMs = if (usePhoneCadence && i % 2 != 0) 20L else 0L
                val fix = syntheticFix(i, direction, uncertainty, clockSkewMs).apply(changeLocation)
                val batch = LocationResult.create(listOf(fix))
                val receipt = receiptAge?.let { batch.singleFixReceipt(fix.elapsedRealtimeNanos + it) }
                add(requireNotNull(fix.toLeanFix(receipt)))
            }
        }
        return rows.sortedBy { it.elapsedNanos }
    }

    private fun syntheticFix(second: Int, direction: Int, uncertainty: Double?, skewMs: Long = 0): Location {
        val yaw = direction * TURN_RATE
        val heading = yaw * (second + skewMs / 1000.0)
        val radius = SPEED / yaw
        return Location("gps").apply {
            latitude = Math.toDegrees(radius * sin(heading) / EARTH_RADIUS)
            longitude = Math.toDegrees(radius * (1 - cos(heading)) / EARTH_RADIUS)
            time = SIMULATED_UTC + second * 1_000L + skewMs
            elapsedRealtimeNanos = SIMULATED_START + second * TIMING_SECOND + skewMs * 1_000_000L
            speed = SPEED.toFloat()
            speedAccuracyMetersPerSecond = .2f
            bearing = ((Math.toDegrees(heading) + 360) % 360).toFloat()
            bearingAccuracyDegrees = 1f
            accuracy = 1f
            if (uncertainty != null) elapsedRealtimeUncertaintyNanos = uncertainty
        }
    }

    private fun live(rows: List<LeanRecord>): Double? {
        val (first, last) = rows.filterIsInstance<LeanGnssRecord>().take(2)
        val imu = rows.filterIsInstance<LeanImuRecord>().filter { it.elapsedNanos < last.elapsedNanos }
        return HandlebarLeanEstimator.liveDegrees(handlebarTimingCalibration, first, last, imu)
    }

    private suspend fun persist(
        db: DiaryDatabase, store: LeanStore, rows: List<LeanRecord>,
    ): Pair<LeanSessionToken, List<LeanRecord>> {
        val trip = Journey(transport = Transport.MOTORCYCLE, startedAt = SIMULATED_UTC)
        db.diaryDao().save(trip)
        val token = requireNotNull(store.begin(trip.id, handlebarTimingCalibration, "qa-boot",
            SIMULATED_UTC, SIMULATED_START))
        val slices = rows.chunked(360)
        for (slice in slices) assertTrue(store.append(token, slice))
        val decoded = readTimingChunks(db, token, slices.size)
        store.seal(token, SIMULATED_START + 12 * TIMING_SECOND)
        db.diaryDao().save(trip.copy(endedAt = SIMULATED_UTC + 12_000L))
        return token to decoded
    }

    private suspend fun finishReport(db: DiaryDatabase, store: LeanStore, token: LeanSessionToken): LeanJourneyReport {
        store.processPending(LeanEstimatorV1()) {}
        val report = requireNotNull(store.observeReport(token.journeyId).first().handlebar)
        assertFalse(report.pending)
        assertEquals(token.id, report.sessionDetails.single().sessionId)
        assertTrue("Completed report releases its raw chunks", db.leanDao().chunkInfos(token.id).isEmpty())
        return report
    }

    private companion object {
        const val SPEED = 12.0
        const val TURN_RATE = .30
        const val EARTH_RADIUS = 6_371_000.0
        const val SIMULATED_START = 20_000_000_000L
    }
}

internal const val TIMING_QA_PACKAGE = "org.iz.navigation.qagidonzaman20261126"
internal const val TIMING_SECOND = 1_000_000_000L
private const val SIMULATED_UTC = 1_700_000_000_000L
private const val GRAVITY = 9.80665

internal val handlebarTimingCalibration = LeanCalibration(
    "qa-handlebar-timing", SIMULATED_UTC, listOf(0.0, 0.0, GRAVITY), listOf(0.0, 0.0, .02),
    bodyConfirmed = false, steeringAxis = listOf(-.4, 0.0, .916515139),
)

internal fun requireTimingQa(emulator: Boolean): Context {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(TIMING_QA_PACKAGE, context.packageName)
    assertEquals(TIMING_QA_PACKAGE, InstrumentationRegistry.getArguments().getString("qaTargetPackage"))
    assertEquals(emulator, Build.HARDWARE in setOf("ranchu", "goldfish"))
    @Suppress("DEPRECATION")
    val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty()
    assertFalse(Manifest.permission.INTERNET in permissions)
    return context
}

internal suspend fun withTimingRoom(context: Context, block: suspend (DiaryDatabase, LeanStore) -> Unit) {
    val db: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
    try {
        block(db, LeanStore(context, db))
    } finally {
        db.close()
    }
}

internal suspend fun readTimingChunks(db: DiaryDatabase, token: LeanSessionToken, expected: Int): List<LeanRecord> {
    val parts = db.leanDao().chunkInfos(token.id)
    assertEquals(expected, parts.size)
    return parts.flatMap { part ->
        val bytes = requireNotNull(db.leanDao().readChunk(token.id, part.partIndex)).payload
        assertEquals(part.sha256, LeanCodec.sha256(bytes))
        LeanCodec.decodeRecords(bytes).toList().also { assertEquals(part.recordCount, it.size) }
    }
}
