package org.iz.navigation.lean

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.LocationResult
import org.iz.navigation.ui.IzTheme
import org.iz.navigation.ui.LeanJourneyReportContent
import org.iz.navigation.ui.LiveLeanDial
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.DiaryBackup
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.WeatherCoordinate
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class LiveHandlebarAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DiaryDatabase::class.java)

    @Test
    fun liveDecisionPublishesSignedTurnsAndClearsTheDialAtRejectionStopAndExpiry() {
        requireLiveHandlebarQa()
        val dial = mutableStateOf<LiveLeanReading?>(null)
        compose.setContent { IzTheme { LiveLeanDial(NAVIGATION_ID, dial.value) } }

        // The engine uses virtual nanoseconds. Anchor its observable output to the Compose dial's real clock.
        fun show(reading: LiveLeanReading?, label: String) {
            compose.runOnIdle {
                dial.value = reading?.copy(measuredElapsedMillis = SystemClock.elapsedRealtime())
            }
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        fun missing(h: TestLiveEngine) {
            assertNull(h.reading)
            show(null, "Tahmini yatış, ölçüm yok")
        }

        val live = TestLiveEngine()
        val turn = SyntheticArc(START, UTC, listOf(.30, .30, .30, .30, .23, -.24, -.24, -.24, -.24, -.24))
        val close = live.attach(RECORDING_ID)
        try {
            for (fix in 0..3) {
                live.fix(turn, fix)
                missing(live)
            }
            live.fix(turn, 4)
            val early = live.assertDegrees(turn.expectedDegrees(3), estimated = true)
            assertEquals(START / 1_000_000 + 4_000, early.measuredElapsedMillis)
            show(early, estimateLabel(turn.expectedDegrees(3)))

            live.fix(turn, 5)
            val smaller = live.assertDegrees(turn.expectedDegrees(4), estimated = true)
            assertTrue(smaller.signedDegrees < early.signedDegrees)
            show(smaller, estimateLabel(turn.expectedDegrees(4)))

            for (fix in 6..9) {
                live.fix(turn, fix)
                missing(live)
            }
            live.fix(turn, 10)
            val opposite = live.assertDegrees(turn.expectedDegrees(9), estimated = true)
            show(opposite, estimateLabel(turn.expectedDegrees(9)))

            val beforeExpiry = START + 10 * SECOND + 1_500_000_000L - 1
            live.advanceTo(beforeExpiry)
            assertEquals(opposite, live.reading)
            live.advanceTo(beforeExpiry + 1)
            missing(live)
            live.advanceTo(beforeExpiry + 4 * SECOND)
            assertEquals(1, live.rejections(LiveHandlebarReason.GNSS_STALE))
        } finally {
            close()
            live.unbind()
        }

        val rejects = listOf(
            Rejection("opposite gyro", LiveHandlebarReason.SENSOR_DISAGREEMENT, gyroScale = -1.0),
            Rejection("batch", LiveHandlebarReason.GNSS_BATCH, batched = true),
            Rejection("receipt over 250 ms", LiveHandlebarReason.GNSS_RECEIPT_STALE, age = 251_000_000L),
            Rejection("time uncertainty out of range", LiveHandlebarReason.GNSS_TIME_UNCERTAIN,
                change = { elapsedRealtimeUncertaintyNanos = 100_000_001.0 }),
            Rejection("missing speed accuracy", LiveHandlebarReason.GNSS_QUALITY, change = { removeSpeedAccuracy() }),
            Rejection("stop", LiveHandlebarReason.GNSS_MOTION, change = { speed = 0f }),
            Rejection("sensor gap", LiveHandlebarReason.SENSOR_STREAM_GAP, omitLastImu = true),
        )
        for (scenario in rejects) {
            val h = TestLiveEngine()
            val good = SyntheticArc(START, UTC, List(5) { .30 })
            val candidate = SyntheticArc(START, UTC, List(5) { .30 }, gyroScale = scenario.gyroScale)
            val detach = h.attach(RECORDING_ID)
            try {
                for (fix in 0..4) h.fix(good, fix)
                h.assertDegrees(good.expectedDegrees(3), estimated = true)
                h.fix(candidate, 5, batched = scenario.batched, age = scenario.age,
                    omitImu = scenario.omitLastImu, change = scenario.change)
                assertNull(scenario.label, h.reading)
                assertEquals(scenario.label, scenario.reason, (h.events.last() as LiveHandlebarDecision.Rejected).reason)
            } finally {
                detach()
                h.unbind()
            }
        }

        val constant = SyntheticArc(START, UTC, List(2) { -.30 })
        val bodyCalibration = handlebarTimingCalibration.copy(bodyConfirmed = true, steeringAxis = emptyList())
        for (calibration in listOf(handlebarTimingCalibration, bodyCalibration)) {
            val measured = TestLiveEngine()
            val detach = measured.attach(RECORDING_ID, calibration = calibration)
            try {
                measured.fix(constant, 0, uncertainty = 2_000_000.0)
                measured.fix(constant, 1, uncertainty = 2_000_000.0)
                val reading = measured.assertDegrees(constant.expectedDegrees(0), estimated = false)
                show(reading, "Tahmini yatış, Sol 20 derece")
                detach()
                missing(measured)
            } finally {
                measured.unbind()
            }
        }
    }

    @Test
    fun onlyVisibleCurrentRecordingOwnsReasonsAndRoomReopenAndZipRetainThem() {
        val context = requireLiveHandlebarQa()
        val diaryName = "qa-live-summary-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, DiaryDatabase::class.java, diaryName).build()
        val archiveDir = File(context.cacheDir, "camera").apply { check(isDirectory || mkdirs()) }
        val archive = File.createTempFile("qa-live-summary-", ".zip", archiveDir)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
        val active = Journey(id = RECORDING_ID, transport = Transport.MOTORCYCLE, startedAt = UTC)
        val finished = active.copy(endedAt = UTC + 12_000L)
        val calibration = handlebarTimingCalibration
        val saved = try {
            runBlocking(Dispatchers.IO) {
                val beforeClose: DiaryDatabase = open()
                val expected = try {
                    val store = LeanStore(context, beforeClose)
                    beforeClose.diaryDao().save(active)
                    val rawOff = LeanCaptureSettings(false, LeanMount.HANDLEBAR, calibration)
                    assertFalse(leanCaptureEligible(active, rawOff))
                    val owner = requireNotNull(store.openLiveDecisions(active.id, requireNotNull(rawOff.liveReference()),
                        "qa-recording-owner", UTC, START, dialOpened = false))
                    val token = owner.token
                    val live = TestLiveEngine()
                    val events = mutableListOf<LiveHandlebarDecision?>()
                    val recorder = LiveDecisionRecorder(owner, calibration, this, store)
                    live.engine.bindRecorder(token, calibration.epoch) { decision ->
                        events.add(decision)
                        recorder.event(decision)
                    }
                    live.advanceTo(START + 2 * SECOND)
                    assertTrue("A recorder alone is not a visible dial", events.isEmpty())
                    assertFalse(owner.summary.dialOpened)

                    val wrongRide = live.attach(journey = "qa-different-journey", initialFix = false, bind = false)
                    live.advanceTo(START + 3 * SECOND)
                    wrongRide()
                    val wrongEpoch = live.attach(active.id, initialFix = false, bind = false,
                        calibration = calibration.copy(epoch = "qa-other-epoch"))
                    wrongEpoch()
                    assertTrue(events.isEmpty())

                    val current = live.attach(active.id, bind = false)
                    val arc = SyntheticArc(START + 5 * SECOND, UTC + 5_000L, List(5) { .30 })
                    try {
                        live.advanceTo(arc.start)
                        assertEquals(listOf(null, LiveHandlebarDecision.Rejected(LiveHandlebarReason.GNSS_NEVER_RECEIVED)), events)
                        for (fix in 0..4) live.fix(arc, fix)
                        assertNull(events.first())
                        live.fix(arc, 5, batched = true)
                        assertNull(live.reading)
                    } finally {
                        current()
                    }

                    val beforeClosedTimeout = events.toList()
                    live.advanceTo(START + 14 * SECOND)
                    assertEquals(beforeClosedTimeout, events)
                    assertFalse(store.checkpointLiveDecisions(token, "qa-other-epoch", LeanLiveDecisionSummary(true)))
                    live.engine.unbindRecorder(token)
                    assertFalse(store.append(token, arc.imu(0L, SECOND)))
                    recorder.finish(START + 12 * SECOND)
                    beforeClose.diaryDao().save(finished)
                    store.processPending(LeanEstimatorV1()) {}
                    assertTrue(beforeClose.leanDao().chunkInfos(token.id).isEmpty())
                    assertNull(beforeClose.leanDao().result(token.id))
                    val report = requireNotNull(store.observeReport(finished.id).first().handlebar)
                    val details = report.sessionDetails.single()
                    val summary = requireNotNull(details.liveDecisions)
                    assertTrue(details.liveOnly)
                    assertTrue(summary.dialOpened)
                    assertEquals(1L, summary.count(LeanLiveDecisionCode.GNSS_NEVER_RECEIVED))
                    assertEquals(1L, summary.count(LeanLiveDecisionCode.ACCEPTED_ESTIMATED))
                    assertEquals(1L, summary.count(LeanLiveDecisionCode.GNSS_BATCH))
                    assertEquals(0L, summary.count(LeanLiveDecisionCode.GNSS_STALE))
                    assertFalse(store.checkpointLiveDecisions(token, calibration.epoch, summary))
                    report
                } finally {
                    beforeClose.close()
                }

                val reopened: DiaryDatabase = open()
                try {
                    val store = LeanStore(context, reopened)
                    val report = requireNotNull(store.observeReport(finished.id).first().handlebar)
                    assertEquals(expected, report)
                    DiaryBackup(context, DiaryRepository(context, reopened)).exportTo(uri)
                } finally {
                    reopened.close()
                }
                ZipFile(archive).use { zip ->
                    val entry = requireNotNull(zip.getEntry("manifest.json"))
                    val manifest = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                    val session = manifest.getJSONArray("leanSessions").getJSONObject(0)
                    assertEquals("LIVE_ONLY", session.getString("state"))
                    assertEquals(0, session.getJSONArray("chunks").length())
                    assertEquals(setOf("version", "dialOpened", "counts"),
                        session.getJSONObject("liveDecisions").keys().asSequence().toSet())
                }
                withTimingRoom(context) { restored, store ->
                    DiaryBackup(context, DiaryRepository(context, restored)).importFrom(uri)
                    assertEquals(listOf(finished.id), restored.diaryDao().allJourneys().map { it.id })
                    val report = requireNotNull(store.observeReport(finished.id).first().handlebar)
                    assertEquals(expected, report)
                }
                expected
            }
        } finally {
            archive.delete()
            context.deleteDatabase(diaryName)
        }
        compose.setContent {
            IzTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LeanJourneyReportContent(finished,
                        LeanJourneyReport(false, null, null, 0L, 0L, emptySet(), emptyList(), emptySet(), handlebar = saved))
                }
            }
        }
        compose.onNodeWithTag("handlebar-lean-report-coverage").performScrollTo().performClick()
        compose.onNodeWithText("Canlı yatış kadranı").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Geçerli tahmin · 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GPS konumu hiç alınmadı · 1 kez").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Birden fazla GPS konumu birlikte geldi · 1 kez").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unrecordedRouteNeverPersistsAndLegacyJourneyRemainsReadable() {
        val context = requireLiveHandlebarQa()
        val name = "qa-gidon-legacy-${UUID.randomUUID()}.db"
        val oldJourney = Journey(id = "qa-older-v1", transport = Transport.MOTORCYCLE,
            startedAt = UTC - 20_000L, endedAt = UTC - 12_000L)
        val oldReport = try {
            runBlocking(Dispatchers.IO) {
                seedV13Report(name, oldJourney)
                val db: DiaryDatabase = Room.databaseBuilder(context, DiaryDatabase::class.java, name)
                    .addMigrations(DiaryDatabase.MIGRATION_13_14).build()
                try {
                    legacyAndNoRecording(db, LeanStore(context, db), oldJourney.id)
                } finally {
                    db.close()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
        compose.setContent {
            IzTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LeanJourneyReportContent(oldJourney,
                        LeanJourneyReport(false, null, null, 0L, 0L, emptySet(), emptyList(), emptySet(), handlebar = oldReport))
                }
            }
        }
        compose.onNodeWithText("En büyük geçerli sol tahmin: 22°").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("handlebar-lean-report-coverage").performScrollTo().performClick()
        compose.onNodeWithText("Canlı kadran bilgisi bilinmiyor.").performScrollTo().assertIsDisplayed()
    }

    private fun seedV13Report(name: String, old: Journey) {
        val calibration = handlebarTimingCalibration
        val curve = LeanCurveResult(START + SECOND, START + 5 * SECOND, 22.0, null, null, null)
        val estimate = LeanEstimate(HandlebarLeanEstimator.LEGACY_VERSION, calibration.epoch, 22.0, null,
            4_000L, 8_000L, emptySet(), listOf(curve))
        migrations.createDatabase(name, 13).use { oldDb ->
            oldDb.execSQL(
                "INSERT INTO journeys(id,title,transport,status,startedAt,endedAt,note,interrupted) " +
                    "VALUES(?,'','MOTORCYCLE','CONFIRMED',?,?,'',0)",
                arrayOf(old.id, old.startedAt, old.endedAt),
            )
            oldDb.execSQL(
                "INSERT INTO lean_sessions(id,journeyId,generation,calibrationJson,bootId,startedAtUtc," +
                    "startedAtElapsedNanos,endedAtElapsedNanos,state,reasonsJson,droppedSamples) " +
                    "VALUES(?,?,1,?,'qa-legacy',?,?,?,'COMPLETE','[]',0)",
                arrayOf("qa-lean-old", old.id, LeanCodec.calibrationJson(calibration), old.startedAt, START, START + 8 * SECOND),
            )
            oldDb.execSQL(
                "INSERT INTO lean_results(sessionId,journeyId,inputHash,algorithmVersion,calibrationEpoch,payload) VALUES(?,?,?,?,?,?)",
                arrayOf("qa-lean-old", old.id, "a".repeat(64), estimate.algorithmVersion, estimate.calibrationEpoch,
                    LeanCodec.estimateJson(estimate)),
            )
        }
    }

    private suspend fun legacyAndNoRecording(db: DiaryDatabase, store: LeanStore, oldId: String): LeanJourneyReport {
        val previous = requireNotNull(store.observeReport(oldId).first().handlebar)
        assertEquals(22.0, requireNotNull(previous.leftDegrees), 0.0)
        assertNull(previous.sessionDetails.single().liveDecisions)

        val noRecording = NavigationState(sessionId = NAVIGATION_ID,
            sessionTransport = Transport.MOTORCYCLE, gpsStale = false,
            fix = NavigationFix(WeatherCoordinate(0.0, 0.0), UTC, 1f, 12f))
        assertNull(noRecording.liveLeanCallbackSession(NAVIGATION_ID, fromRecording = false))
        val guiding = noRecording.copy(guidance = true)
        assertEquals(NAVIGATION_ID, guiding.liveLeanCallbackSession(NAVIGATION_ID, fromRecording = false))
        assertNull(guiding.copy(paused = true).liveLeanCallbackSession(NAVIGATION_ID, fromRecording = false))
        val live = TestLiveEngine()
        val detach = live.attach(journey = null, bind = false)
        try {
            val arc = SyntheticArc(START, UTC, List(5) { .30 })
            for (fix in 0..4) live.fix(arc, fix)
            live.assertDegrees(arc.expectedDegrees(3), estimated = true)
            assertNull(live.engine.recordingTarget.value)
            assertTrue(live.events.isEmpty())
            detach()
            assertNull(live.reading)
            live.fix(arc, 5)
            assertNull(live.reading)
        } finally {
            detach()
        }
        assertEquals(listOf(oldId), db.diaryDao().allJourneys().map { it.id })
        assertEquals(listOf(oldId), store.snapshot().map { it.input.token.journeyId })
        assertEquals(previous, store.observeReport(oldId).first().handlebar)
        return previous
    }

    private data class Rejection(
        val label: String,
        val reason: LiveHandlebarReason,
        val gyroScale: Double = 1.0,
        val age: Long = 30_000_000L,
        val batched: Boolean = false,
        val omitLastImu: Boolean = false,
        val change: Location.() -> Unit = {},
    )

    private fun estimateLabel(degrees: Double): String =
        "Yatış tahmini, ${if (degrees < 0) "Sol" else "Sağ"} ${abs(degrees).roundToInt()} derece"
}

private class TestLiveEngine {
    private var now = START
    private val timeouts = linkedMapOf<Runnable, Long>()
    private var sensor: ((LeanImuRecord) -> Unit)? = null
    private var journeyId: String? = null
    val events = mutableListOf<LiveHandlebarDecision?>()
    val engine = LiveLeanEngine(
        elapsed = { now },
        postTimeout = { action, delayMs -> timeouts[action] = now + delayMs * 1_000_000L },
        cancelTimeout = { action -> timeouts.remove(action); Unit },
    )
    var reading: LiveLeanReading? = null
        private set

    private val token = LeanSessionToken("live-token", RECORDING_ID, 1L)

    fun attach(
        journey: String?,
        calibration: LeanCalibration = handlebarTimingCalibration,
        initialFix: Boolean = true,
        bind: Boolean = true,
    ): () -> Unit {
        journeyId = journey
        if (bind) engine.bindRecorder(token, handlebarTimingCalibration.epoch) { events.add(it) }
        return engine.attach(NAVIGATION_ID, calibration, journey, initialFix, subscribe = { onImu, _ ->
            sensor = onImu
            val detachSensors: () -> Unit = { sensor = null }
            detachSensors
        }, publish = { reading = it })
    }

    fun unbind() = engine.unbindRecorder(token)

    fun advanceTo(target: Long) {
        check(target >= now)
        while (true) {
            val (action, due) = timeouts.minByOrNull { it.value } ?: break
            if (due > target) break
            now = due
            timeouts.remove(action)
            action.run()
        }
        now = target
    }

    fun fix(
        arc: SyntheticArc,
        index: Int,
        uncertainty: Double? = null,
        age: Long = 30_000_000L,
        batched: Boolean = false,
        omitImu: Boolean = false,
        change: Location.() -> Unit = {},
    ) {
        val offset = index * SECOND
        val location = arc.location(offset, uncertainty).apply(change)
        val received = location.elapsedRealtimeNanos + age
        advanceTo(received)
        if (index > 0 && !omitImu) arc.imu(offset - SECOND, offset).forEach { sensor?.invoke(it) }
        val locations = if (batched) listOf(arc.location(offset - SECOND), location) else listOf(location)
        val callback = LocationResult.create(locations)
        engine.accept(NAVIGATION_ID, journeyId, location, callback.singleFixReceipt(received), locations.size > 1)
    }

    fun assertDegrees(expected: Double, estimated: Boolean): LiveLeanReading =
        requireNotNull(reading) { "No live reading. Last decision: ${events.lastOrNull()}" }.also {
            assertEquals(NAVIGATION_ID, it.sessionId)
            assertEquals(estimated, it.estimated)
            assertEquals(expected, it.signedDegrees, .15)
        }

    fun rejections(reason: LiveHandlebarReason): Int =
        events.filterIsInstance<LiveHandlebarDecision.Rejected>().count { it.reason == reason }
}

private const val NAVIGATION_ID = "qa-live-navigation"
private const val RECORDING_ID = "qa-live-journey"
private const val START = 20_000_000_000L
private const val UTC = 1_740_000_000_000L

private fun requireLiveHandlebarQa(): Context {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = "org.iz.navigation.qacanligidon20261127"
    assertEquals(target, context.packageName)
    assertEquals(target, InstrumentationRegistry.getArguments().getString("qaTargetPackage"))
    assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
    @Suppress("DEPRECATION")
    val permissions = context.packageManager.getPackageInfo(target, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty()
    assertFalse(Manifest.permission.INTERNET in permissions)
    return context
}

private const val SECOND = 1_000_000_000L

private class SyntheticArc(
    val start: Long,
    val utc: Long,
    private val yawBySecond: List<Double>,
    private val speed: Double = 12.0,
    private val gyroScale: Double = 1.0,
) {
    fun expectedDegrees(interval: Int): Double =
        Math.toDegrees(atan2(speed * yawBySecond[interval], GRAVITY))

    private data class Pose(val north: Double, val east: Double, val heading: Double)

    private fun poseAt(offset: Long): Pose {
        var pose = Pose(0.0, 0.0, 0.0)
        var remaining = offset
        for (rate in yawBySecond) {
            if (remaining == 0L) break
            val step = minOf(remaining, SECOND)
            val seconds = step / SECOND.toDouble()
            val heading = pose.heading + rate * seconds
            val north = if (rate == 0.0) speed * seconds * cos(pose.heading)
                else speed / rate * (sin(heading) - sin(pose.heading))
            val east = if (rate == 0.0) speed * seconds * sin(pose.heading)
                else speed / rate * (cos(pose.heading) - cos(heading))
            pose = Pose(pose.north + north, pose.east + east, heading)
            remaining -= step
        }
        check(remaining == 0L)
        return pose
    }

    fun location(offset: Long, uncertainty: Double? = null): Location {
        val pose = poseAt(offset)
        return Location("gps").apply {
            elapsedRealtimeNanos = start + offset
            time = utc + offset / 1_000_000L
            latitude = Math.toDegrees(pose.north / EARTH_RADIUS)
            longitude = Math.toDegrees(pose.east / EARTH_RADIUS)
            accuracy = 1f
            speed = this@SyntheticArc.speed.toFloat()
            speedAccuracyMetersPerSecond = .20f
            bearing = ((Math.toDegrees(pose.heading) + 360.0) % 360.0).toFloat()
            bearingAccuracyDegrees = 1f
            if (uncertainty != null) elapsedRealtimeUncertaintyNanos = uncertainty
        }
    }

    fun imu(from: Long, until: Long): List<LeanImuRecord> = buildList {
        for ((kind, hz) in listOf(LeanSensorKind.ACCELEROMETER to 115, LeanSensorKind.GYROSCOPE to 46)) {
            val startIndex = from * hz / SECOND
            val stopIndex = until * hz / SECOND
            for (i in startIndex..stopIndex) {
                val offset = i * SECOND / hz
                if (offset < from || offset >= until) continue
                val elapsed = start + offset
                val yaw = yawBySecond[(offset / SECOND).toInt()]
                val bank = atan2(speed * yaw, GRAVITY)
                val row = if (kind == LeanSensorKind.ACCELEROMETER) {
                    LeanImuRecord(kind, i, elapsed, 0.0, 0.0, hypot(GRAVITY, speed * yaw), 3)
                } else {
                    val gy = -yaw * sin(bank) * gyroScale
                    val gz = .02 - yaw * cos(bank) * gyroScale
                    LeanImuRecord(kind, i, elapsed, 0.0, gy, gz, 3)
                }
                add(row)
            }
        }
    }.sortedBy { it.elapsedNanos }

    companion object {
        private const val GRAVITY = 9.80665
        private const val EARTH_RADIUS = 6_371_000.0
    }
}
