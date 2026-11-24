package org.iz.navigation.moto

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.lean.*
import org.iz.navigation.ui.ActiveLeanRecordingNotice
import org.iz.navigation.ui.IzTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotorcycleLeanAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun waitingAndEnableAreVisibleWithoutSensorDataAndEnableSurvivesAStoreReopen() {
        val context = requireMotorcycleQaTarget()
        val settings = LeanSettingsStore.get(context)
        val original = settings.state.value
        val body = reference()
        try {
            settings.enabled(false)
            settings.mount(LeanMount.BODY)
            settings.reference(body)
            val sensorStatus = MutableStateFlow<LeanRecordingStatus?>(null)
            compose.setContent {
                val config by settings.state.collectAsState()
                val status by sensorStatus.collectAsState()
                IzTheme { ActiveLeanRecordingNotice("qa-current", config, status, settings) }
            }
            compose.onNodeWithTag("lean-recording-enable").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { LeanSettingsStore(context).state.value.enabled }
            compose.onNodeWithTag("lean-recording-status").assertTextContains("Sensör verisi bekleniyor", substring = true)
            compose.onNodeWithText("Yatış sensörleri kaydediliyor").assertDoesNotExist()

            sensorStatus.value = LeanRecordingStatus("qa-prior", body.epoch, LeanRecordingPhase.RECORDING)
            compose.onNodeWithText("Yatış sensörleri kaydediliyor").assertDoesNotExist()
            sensorStatus.value = LeanRecordingStatus("qa-current", "old-calibration", LeanRecordingPhase.RECORDING)
            compose.onNodeWithText("Yatış sensörleri kaydediliyor").assertDoesNotExist()

            sensorStatus.value = LeanRecordingStatus("qa-current", body.epoch, LeanRecordingPhase.GYROSCOPE_DATA_MISSING)
            compose.onNodeWithTag("lean-recording-status").assertTextContains("Jiroskop verisi kayda ulaşmıyor")
            sensorStatus.value = LeanRecordingStatus("qa-current", body.epoch, LeanRecordingPhase.STORAGE_UNAVAILABLE)
            compose.onNodeWithTag("lean-recording-status").assertTextContains("depolamaya yazılamıyor", substring = true)
            sensorStatus.value = LeanRecordingStatus("qa-current", body.epoch, LeanRecordingPhase.SENSOR_UNAVAILABLE)
            compose.onNodeWithTag("lean-recording-status").assertTextContains("sensörleri kullanılamıyor", substring = true)
            sensorStatus.value = LeanRecordingStatus("qa-current", body.epoch, LeanRecordingPhase.RECORDING)
            compose.onNodeWithTag("lean-recording-status").assertTextContains("Yatış sensörleri kaydediliyor")
            compose.runOnIdle { settings.clearCalibration() }
            compose.onNodeWithTag("lean-recording-status").assertTextContains("montaj kalibrasyonu gerekli", substring = true)
        } finally {
            settings.mount(original.mount)
            settings.clearCalibration()
            original.calibration?.let(settings::reference)
            settings.enabled(original.enabled)
        }
    }

    @Test fun persistedGnssAndSingleSensorAreInsufficientAndAStorageRejectionKeepsDiaryGps() = runBlocking<Unit>(Dispatchers.IO) {
        val context = requireMotorcycleQaTarget()
        val db: DiaryDatabase = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val journey = Journey(transport = Transport.MOTORCYCLE, startedAt = 1_000)
            val dao = db.diaryDao()
            dao.save(journey)
            val diaryPoint = TrackPoint(journeyId = journey.id, latitude = .27, longitude = -140.0,
                recordedAt = 1_500, accuracy = 3f, speed = 10f)
            dao.save(diaryPoint)
            val store = LeanStore(context, db, byteBudget = 2_048L)
            val start = 1_000_000_000L
            val token = requireNotNull(store.begin(journey.id, reference(), "qa-boot", 1_000, start))
            val gate = LeanSensorCommitProgress(start)
            val waiting = LeanRecordingPhase.WAITING_FOR_DATA

            val fix = LeanGnssRecord(start + 100_000_000, 1_500, .27, -140.0, 3.0, 10.0, null,
                null, null, null, false, "gps")
            gate.commit(requireNotNull(store.appendCaptureBatch(token, listOf(fix))))
            assertEquals(waiting, gate.phase(start + 200_000_000))

            val first = imu(LeanSensorKind.ACCELEROMETER, 1L, start + 300_000_000)
            gate.commit(requireNotNull(store.appendCaptureBatch(token, listOf(first))))
            assertEquals(waiting, gate.phase(start + 400_000_000))
            val staleGyro = imu(LeanSensorKind.GYROSCOPE, 2L, start - 1)
            gate.commit(requireNotNull(store.appendCaptureBatch(token, listOf(staleGyro))))
            assertEquals(waiting, gate.phase(start + 450_000_000))

            val freshGyro = imu(LeanSensorKind.GYROSCOPE, 3L, start + 500_000_000)
            gate.commit(requireNotNull(store.appendCaptureBatch(token, listOf(freshGyro))))
            assertEquals(LeanRecordingPhase.RECORDING, gate.phase(start + 600_000_000))

            val before = db.leanDao().chunks(token.id)
            val persisted = before.flatMap { LeanCodec.decodeRecords(it.payload).toList() }
            assertEquals(listOf(fix, first, freshGyro), persisted)
            val oversize = List(100) { index -> imu(LeanSensorKind.GYROSCOPE, 4L + index, start + 600_000_000 + index) }
            assertNull(store.appendCaptureBatch(token, oversize))
            assertEquals(before.map { it.sha256 }, db.leanDao().chunks(token.id).map { it.sha256 })
            assertEquals(listOf(diaryPoint), dao.journeyPoints(journey.id))
        } finally {
            db.close()
        }
    }

    private fun reference() = LeanCalibration("qa-reference", 500L, listOf(0.0, 0.0, 9.8),
        listOf(0.0, 0.0, 0.0))
    private fun imu(kind: LeanSensorKind, sequence: Long, elapsed: Long) =
        LeanImuRecord(kind, sequence, elapsed, .07, .17, .91, 3)
}
