package org.iz.navigation.moto

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.lean.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HandlebarSessionIntegrationTest {
    @Test fun savedHandlebarSurvivesRestartUntilMountIsReset() {
        assumeTrue(HandlebarLeanGate.enabled)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val ownPreferences = "qa-handlebar-" + UUID.randomUUID()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(ownPreferences, mode)
        }
        try {
            val settings = LeanSettingsStore(context)
            val bars = LeanCalibration("fixture", 1, listOf(0.0, 0.0, 9.81), listOf(0.0, 0.0, 0.0),
                bodyConfirmed = false, steeringAxis = listOf(-0.6, 0.0, 0.8))
            settings.mount(LeanMount.HANDLEBAR)
            settings.enabled(true)
            settings.reference(bars)
            val reopened = LeanSettingsStore(context)
            assertEquals(LeanCaptureSettings(true, LeanMount.HANDLEBAR, bars), reopened.state.value)
            reopened.mount(LeanMount.HANDLEBAR)
            assertEquals(bars, reopened.state.value.calibration)
            reopened.clearCalibration()
            assertNull(LeanSettingsStore(context).state.value.calibration)
            reopened.reference(bars)
            reopened.mount(LeanMount.BODY)
            reopened.mount(LeanMount.HANDLEBAR)
            assertNull(LeanSettingsStore(context).state.value.calibration)
        } finally {
            app.deleteSharedPreferences(ownPreferences)
        }
    }

    @Test fun mixedMountJourneyKeepsParkedHandlebarOutOfBodyPeaks() = runBlocking(Dispatchers.IO) {
        assumeTrue(HandlebarLeanGate.enabled)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val store = LeanStore(context, db)
            val utc = 1_700_000_000_000L
            val trip = Journey(transport = Transport.MOTORCYCLE, startedAt = utc)
            db.diaryDao().save(trip)
            val body = LeanCalibration("body", utc, listOf(0.0, 0.0, 9.81), listOf(0.0, 0.0, 0.0))
            val bar = body.copy(epoch = "bars", bodyConfirmed = false, steeringAxis = listOf(-0.6, 0.0, 0.8))
            val first = requireNotNull(store.begin(trip.id, body, "boot", utc, 0))
            store.seal(first, 1_000_000_000L)
            val second = requireNotNull(store.begin(trip.id, bar, "boot", utc + 1000, 1_000_000_000L))
            val parked = buildList<LeanRecord> {
                for (i in 0..200) {
                    val t = 1_000_000_000L + i * 20_000_000L
                    add(LeanImuRecord(LeanSensorKind.ACCELEROMETER, i.toLong(), t, 0.0, 0.0, 9.81, 3))
                    add(LeanImuRecord(LeanSensorKind.GYROSCOPE, i.toLong(), t, -0.36, 0.0, 0.48, 3))
                    if (i % 50 == 0) add(LeanGnssRecord(t, utc + t / 1_000_000L,
                        0.0, 0.0, 1.0, 0.0, 0.0, 0.1, 1.0, 0.0, false, "gps"))
                }
            }
            assertTrue(store.append(second, parked))
            store.seal(second, 5_000_000_000L)
            db.diaryDao().save(trip.copy(endedAt = utc + 5000))
            store.processPending(object : LeanEstimator {
                override fun estimate(session: LeanInputSession, records: Sequence<LeanRecord>, checkAllowed: () -> Unit): LeanEstimate {
                    assertEquals(body, session.calibration)
                    return LeanEstimate("qa-body", body.epoch, 12.0, null, 1000, 1000, emptySet(), emptyList())
                }
            }) {}
            val report = store.observeReport(trip.id).first()
            val bars = requireNotNull(report.handlebar)
            assertEquals(setOf("qa-body"), report.algorithmVersions)
            assertEquals(12.0, requireNotNull(report.leftDegrees), 0.0)
            assertEquals(setOf(HandlebarLeanEstimator.VERSION), bars.algorithmVersions)
            assertFalse(bars.pending)
            assertNull(bars.leftDegrees)
            assertNull(bars.rightDegrees)
            assertTrue(bars.curves.isEmpty())
            assertEquals(0L, bars.validMillis)
            val backup = store.snapshot()
            assertEquals(2, backup.size)
            assertEquals(backup, LeanBackupCodec.decode(LeanBackupCodec.encode(backup)))
        } finally {
            db.close()
        }
    }
}
