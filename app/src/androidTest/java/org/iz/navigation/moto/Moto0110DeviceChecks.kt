package org.iz.navigation.moto

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.lean.*
import org.iz.navigation.tracking.TrackingService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Independent synthetic integration checks. No UI, real sensor registration or estimator-accuracy claim.
 * Isolated Room instances and UUID-owned migration DB/archive files only; no default diary replacement.
 * Framework patterns: developer.android.com/training/data-storage/room/migrating-db-versions
 * and developer.android.com/reference/androidx/core/content/FileProvider#getUriForFile
 */
@RunWith(AndroidJUnit4::class)
class Moto0110DeviceChecks {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val syntheticUtc = System.currentTimeMillis() - 5_000
    private val databaseNames = mutableListOf<String>()
    private val archives = mutableListOf<File>()
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DiaryDatabase::class.java)

    @Before fun requireDedicatedIdleQaPackage() {
        assertEquals("org.iz.navigation.batterycheck", context.packageName)
        assertFalse(TrackingService.isRunning)
        assertTrue("Synthetic QA must not read real health providers", listOf("READ_HEART_RATE", "READ_TOTAL_CALORIES_BURNED",
            "READ_STEPS", "READ_EXERCISE", "READ_DISTANCE").none {
            ContextCompat.checkSelfPermission(context, "android.permission.health.$it") == PackageManager.PERMISSION_GRANTED
        })
        val nav = (context.applicationContext as IzApplication).navigation.state.value
        assertFalse("No actual QA guidance/recorder/location session may be used", nav.guidance || nav.recording || nav.locationActive || nav.loading)
    }

    @After fun cleanupOnlyOwnedFiles() {
        databaseNames.forEach { name ->
            check(name.startsWith("qa-moto-0110-") && '/' !in name && '\\' !in name)
            if (context.getDatabasePath(name).exists()) assertTrue(context.deleteDatabase(name))
        }
        val camera = File(context.cacheDir, "camera").canonicalFile
        archives.forEach { file ->
            check(file.canonicalFile.parentFile == camera && file.name.startsWith("qa-moto-0110-"))
            if (file.exists()) assertTrue(file.isFile && file.delete())
        }
    }

    /** Newly constructed repository first-use only. Do not pre-await Analysis readiness in the test.
     * A warm application singleton does not prove cold-process initializer/Raw scheduling.
     * Core's controlled ordering unit covers deterministic contention; a cold QA run needs its own lease.
     */
    @Test fun firstUseRestoreAndSaveJourneyCompleteWithinBoundedTimeout() = runBlocking(Dispatchers.IO) {
        withRoom { restoredDb, restoredDiary, _ ->
            withRoom { mutatedDb, mutatedDiary, _ ->
                val prior = journey().copy(transport = Transport.WALK, endedAt = syntheticUtc + 1_000,
                    note = "Synthetic first-use restore")
                val active = journey()
                withTimeout(10_000) {
                    coroutineScope {
                        // Distinct real isolated DBs avoid conflating lock order with conflicting diary writes.
                        val restore = async { restoredDiary.restore(DiarySnapshot(journeys = listOf(prior))) }
                        val mutation = async { mutatedDiary.saveJourney(active) }
                        restore.await()
                        mutation.await()
                    }
                }
                assertEquals(listOf(prior), restoredDiary.snapshot().journeys)
                assertNull(restoredDiary.activeJourney())
                assertEquals(active, mutatedDiary.getJourney(active.id))
                assertEquals(active.id, mutatedDiary.activeJourney()?.id)
                assertTrue(restoredDb.leanDao().allSessions().isEmpty())
                assertTrue(mutatedDb.leanDao().allSessions().isEmpty())
                assertEquals(0L, restoredDb.leanDao().rawBytes())
                assertEquals(0L, mutatedDb.leanDao().rawBytes())
            }
        }
    }

    @Test fun sharedShortcutsRefreshCoordinatesAndDeletionWithoutChangingJourneyModeOrSession() = runBlocking(Dispatchers.IO) {
        withRoom { db, diary, library ->
            val moto = journey()
            db.diaryDao().save(moto)
            val originalNav = (context.applicationContext as IzApplication).navigation.state.value
            val home = Place(id = id(), name = "Synthetic home", latitude = 0.0, longitude = 0.0)
            val work = Place(id = id(), name = "Synthetic work", latitude = 0.001, longitude = 0.001)
            val expectedWork = work.copy(sortOrder = 1L)
            diary.savePlace(home); diary.savePlace(work)
            library.setDestinationShortcut(DestinationShortcutKind.HOME, home.id)
            library.setDestinationShortcut(DestinationShortcutKind.WORK, work.id)
            val two = withTimeout(5_000) { library.destinationShortcuts.first { it.size == 2 } }
            assertEquals(setOf(home.id, work.id), two.map { it.place.id }.toSet())

            val edited = home.copy(name = "Synthetic edited home", latitude = 0.002, longitude = 0.003)
            diary.savePlace(edited)
            val refreshed = withTimeout(5_000) { library.destinationShortcuts.first { values ->
                values.any { it.kind == DestinationShortcutKind.HOME && it.place == edited }
            } }
            assertEquals(edited, refreshed.single { it.kind == DestinationShortcutKind.HOME }.place)
            diary.deletePlace(home.id)
            val remaining = withTimeout(5_000) { library.destinationShortcuts.first { it.size == 1 } }
            assertEquals(DestinationShortcutKind.WORK, remaining.single().kind)
            assertEquals(expectedWork, remaining.single().place)
            assertTrue(db.travelLibraryDao().allShortcuts().none { it.placeId == home.id })
            library.setDestinationShortcut(DestinationShortcutKind.WORK, null)
            assertTrue(withTimeout(5_000) { library.destinationShortcuts.first { it.isEmpty() } }.isEmpty())

            assertEquals(moto, diary.getJourney(moto.id))
            assertEquals(moto.id, diary.activeJourney()?.id)
            val currentNav = (context.applicationContext as IzApplication).navigation.state.value
            assertEquals(originalNav.sessionTransport, currentNav.sessionTransport)
            assertEquals(originalNav.sessionId, currentNav.sessionId)
            assertEquals(originalNav.recording, currentNav.recording)
            assertEquals(originalNav.guidance, currentNav.guidance)
            // Shared persistence/resolve invariance only; no phone/Auto preview or Start input is claimed.
        }
    }

    @Test fun roomSevenToEightPreservesOldDiaryAndCreatesNoManufacturedLean() = runBlocking(Dispatchers.IO) {
        val name = "qa-moto-0110-${UUID.randomUUID()}.db".also { databaseNames += it }
        migration.createDatabase(name, 7).use { db ->
            db.execSQL("INSERT INTO journeys (id,title,transport,status,startedAt,endedAt,expiresAt,note,interrupted,stepCount) VALUES ('legacy','Keep walk','WALK','CONFIRMED',1000,5000,NULL,'Keep note',0,23)")
            db.execSQL("INSERT INTO track_points (id,journeyId,latitude,longitude,recordedAt,accuracy,speed,altitude,breakBefore) VALUES ('point','legacy',0.0,0.0,2000,5.0,NULL,NULL,0)")
            db.execSQL("INSERT INTO places (id,name,latitude,longitude,googlePlaceId,osmType,osmId,source,sortOrder) VALUES ('place','Keep place',0.0,0.0,NULL,NULL,NULL,'LEGACY',4)")
            db.execSQL("INSERT INTO visits (id,placeId,journeyId,visitedAt,note,rating) VALUES ('visit','place','legacy',3000,'Keep visit',4)")
        }
        migration.runMigrationsAndValidate(name, 8, true, DiaryDatabase.MIGRATION_7_8).close()
        val db = Room.databaseBuilder(context, DiaryDatabase::class.java, name)
            .addMigrations(DiaryDatabase.MIGRATION_7_8).build()
        try {
            val dao = db.diaryDao()
            assertEquals("Keep note", dao.journey("legacy")!!.note)
            assertEquals(Transport.WALK, dao.journey("legacy")!!.transport)
            assertEquals(23L, dao.journey("legacy")!!.stepCount)
            assertEquals("legacy", dao.allPoints().single().journeyId)
            assertEquals(4L, dao.allPlaces().single().sortOrder)
            assertEquals("place", dao.allVisits().single().placeId)
            assertTrue(db.leanDao().allSessions().isEmpty())
            assertTrue(db.leanDao().allResults().isEmpty())
            assertEquals(0L, db.leanDao().rawBytes())
            assertTrue(TravelLibraryRepository(db).destinationShortcuts.first().isEmpty())
            val report = awaitReport(LeanStore(context, db), "legacy")
            assertFalse(report.pending)
            assertNull(report.leftDegrees); assertNull(report.rightDegrees)
            assertTrue(report.curves.isEmpty()); assertTrue(report.algorithmVersions.isEmpty())
        } finally { db.close() }
    }

    @Test fun legacyZipSevenThenZipEightRoundTripsShortcutsPendingRawAndDurableResults() = runBlocking(Dispatchers.IO) {
        withRoom { db, diary, library ->
            val backup = DiaryBackup(context, diary)
            val legacy = archive()
            ZipOutputStream(legacy.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(legacySeven().toString().toByteArray()); zip.closeEntry()
            }
            backup.importFrom(uri(legacy))
            val old = requireNotNull(diary.getJourney("legacy"))
            assertEquals("Keep old note", old.note)
            assertEquals(23L, old.stepCount)
            assertTrue(db.leanDao().allSessions().isEmpty())
            assertNull(awaitReport(LeanStore(context, db), old.id).leftDegrees)
            library.setDestinationShortcut(DestinationShortcutKind.HOME, "legacy-place")
            val work = Place(id = id(), name = "Synthetic work", latitude = 0.001, longitude = 0.002)
            diary.savePlace(work)
            library.setDestinationShortcut(DestinationShortcutKind.WORK, work.id)

            val store = LeanStore(context, db)
            val pending = sealedInput(db, store, "capture_interrupted")
            val complete = sealedInput(db, store)
            val completeEstimate = estimate(complete.second)
            assertTrue(store.commitResult(complete.second.token.id, complete.second.inputHash, completeEstimate) {})
            val pendingChunks = db.leanDao().chunks(pending.second.token.id)
            val completeChunks = db.leanDao().chunks(complete.second.token.id)
            assertTrue(pendingChunks.isNotEmpty())
            assertTrue("COMPLETE may still own raw until cleanup; ZIP must preserve this phase", completeChunks.isNotEmpty())
            val resultBefore = awaitReport(store, complete.first.id) { !it.pending && it.algorithmVersions.contains("qa-lifecycle-v1") }
            val before = diary.snapshot()
            val zipEight = archive()
            backup.exportTo(uri(zipEight))
            ZipFile(zipEight).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                assertEquals(8, manifest.getInt("version"))
                assertEquals(2, manifest.getJSONArray("leanSessions").length())
                assertTrue("Sealed pending raw must have a typed binary ZIP member", zip.entries().asSequence().any { it.name != "manifest.json" && !it.isDirectory })
            }
            diary.restore(DiarySnapshot()) // This repository owns only the in-memory test DB.
            assertTrue(db.leanDao().allSessions().isEmpty())
            backup.importFrom(uri(zipEight))
            val restored = diary.snapshot()
            assertEquals(before.journeys, restored.journeys)
            assertEquals(before.points, restored.points)
            assertEquals(before.places, restored.places)
            assertEquals(before.destinationShortcuts, restored.destinationShortcuts)
            assertEquals(2, library.destinationShortcuts.first().size)
            assertEquals(old, diary.getJourney(old.id))
            val restoredPending = store.pendingInputs().single { it.token.id == pending.second.token.id }
            // Pinned restore contract preserves immutable capture identity and never resumes capture.
            assertEquals(pending.second.token, restoredPending.token)
            assertEquals(pending.second.inputHash, restoredPending.inputHash)
            assertEquals(pending.second.startedAtUtc, restoredPending.startedAtUtc)
            assertEquals(pending.second.startedAtElapsedNanos, restoredPending.startedAtElapsedNanos)
            assertEquals(pending.second.endedAtElapsedNanos, restoredPending.endedAtElapsedNanos)
            assertEquals(pending.second.partialReasons, restoredPending.partialReasons)
            assertEquals(pending.second.droppedSamples, restoredPending.droppedSamples)
            assertEquals(pending.second.calibration.epoch, restoredPending.calibration.epoch)
            val restoredChunks = db.leanDao().chunks(pending.second.token.id)
            assertEquals(pendingChunks.map { it.sha256 }, restoredChunks.map { it.sha256 })
            pendingChunks.zip(restoredChunks).forEach { (a, b) -> assertArrayEquals(a.payload, b.payload) }
            assertEquals(resultBefore, awaitReport(store, complete.first.id) { !it.pending && it.algorithmVersions.contains("qa-lifecycle-v1") })
            val restoredCompleteChunks = db.leanDao().chunks(complete.second.token.id)
            assertEquals(completeChunks.map { it.sha256 }, restoredCompleteChunks.map { it.sha256 })
            completeChunks.zip(restoredCompleteChunks).forEach { (a, b) -> assertArrayEquals(a.payload, b.payload) }
            assertFalse("Imported sealed/partial input cannot resume an old callback", store.append(pending.second.token, records(START + 2_000_000_000L)))
            store.cleanupCommittedRaw()
            assertTrue(db.leanDao().chunks(complete.second.token.id).isEmpty())
            assertTrue(db.leanDao().chunks(pending.second.token.id).isNotEmpty())
            assertEquals(resultBefore, awaitReport(store, complete.first.id) { !it.pending && it.algorithmVersions.contains("qa-lifecycle-v1") })
            assertNull(diary.activeJourney())
        }
    }

    @Test fun producerReplacementKeepsOldPrefixAndRejectsOldAppendStopAndSeal() = runBlocking(Dispatchers.IO) {
        withRoom { db, _, _ ->
            val moto = journey(); db.diaryDao().save(moto)
            val store = LeanStore(context, db)
            val reference = calibration(moto.startedAt)
            val predecessor = requireNotNull(store.begin(moto.id, reference, "qa-boot", moto.startedAt, START))
            val oldRecords = records().map { if (it is LeanImuRecord) it.copy(sequence = it.sequence - 1) else it }
            assertTrue(store.append(predecessor, oldRecords))
            val oldChunks = db.leanDao().chunks(predecessor.id)
            assertTrue(oldChunks.isNotEmpty())

            val nextStart = START + 1_000_000_000L
            val successor = requireNotNull(store.begin(moto.id, reference, "qa-boot", moto.startedAt + 1_000, nextStart))
            assertNotEquals(predecessor.id, successor.id)
            assertNotEquals(predecessor.generation, successor.generation)
            val oldPrefix = requireNotNull(db.leanDao().session(predecessor.id))
            assertEquals("PARTIAL", oldPrefix.state)
            assertTrue(LeanCodec.reasons(oldPrefix.reasonsJson).contains("PRODUCER_REPLACED"))
            val liveBeforeOldStop = requireNotNull(db.leanDao().session(successor.id))
            assertEquals("CAPTURING", liveBeforeOldStop.state)
            assertNull(liveBeforeOldStop.endedAtElapsedNanos)
            assertTrue(db.leanDao().chunks(successor.id).isEmpty())

            val successorRecords = records(nextStart).map { if (it is LeanImuRecord) it.copy(sequence = it.sequence - 1) else it }
            assertFalse(store.append(predecessor, successorRecords))
            store.stopAt(predecessor, nextStart + 2_000_000_000L)
            store.seal(predecessor, nextStart + 2_000_000_000L, "late_old_stop", droppedSamples = 19)
            assertEquals(liveBeforeOldStop, db.leanDao().session(successor.id))
            assertEquals(oldPrefix, db.leanDao().session(predecessor.id))
            assertTrue(store.append(successor, successorRecords))
            val successorChunk = db.leanDao().chunks(successor.id).single()
            val decoded = LeanCodec.decodeRecords(successorChunk.payload).toList()
            assertEquals(successorRecords, decoded)
            assertEquals(0L, decoded.filterIsInstance<LeanImuRecord>().first().sequence)
            val retained = db.leanDao().chunks(predecessor.id)
            assertEquals(oldChunks.map { it.sha256 }, retained.map { it.sha256 })
            oldChunks.zip(retained).forEach { (a, b) -> assertArrayEquals(a.payload, b.payload) }
            assertEquals("PARTIAL", db.leanDao().session(predecessor.id)?.state)
            assertEquals("CAPTURING", db.leanDao().session(successor.id)?.state)
            // Store ownership only: no physical SensorEvent/controller stop race is manufactured here.
        }
    }

    @Test fun leanStoreRequiresConfirmedOpenMotoAndBodyReference() = runBlocking(Dispatchers.IO) {
        withRoom { db, _, _ ->
            val store = LeanStore(context, db)
            val moto = journey()
            for (invalid in listOf(moto.copy(transport = Transport.CAR), moto.copy(status = JourneyStatus.TEMPORARY),
                moto.copy(endedAt = moto.startedAt + 1_000), moto.copy(interrupted = true))) {
                db.diaryDao().save(invalid)
                assertNull(store.begin(moto.id, calibration(moto.startedAt), "qa-boot", moto.startedAt, START))
            }
            db.diaryDao().save(moto)
            val invalidReference = try {
                store.begin(moto.id, calibration(moto.startedAt).copy(bodyConfirmed = false), "qa-boot", moto.startedAt, START)
                null
            } catch (error: IllegalArgumentException) { error }
            assertNotNull("Invalid body reference must not create a session", invalidReference)
            assertTrue(db.leanDao().allSessions().isEmpty())
            assertNotNull(store.begin(moto.id, calibration(moto.startedAt), "qa-boot", moto.startedAt, START))
            assertEquals(0L, db.leanDao().rawBytes())
        }
    }

    @Test fun smallRawBudgetPreservesUnprocessedChunksAndOrdinaryDiaryPointWrites() = runBlocking(Dispatchers.IO) {
        withRoom { db, diary, _ ->
            val store = LeanStore(context, db, byteBudget = 16 * 1024L)
            val moto = journey(); db.diaryDao().save(moto)
            val token = requireNotNull(store.begin(moto.id, calibration(moto.startedAt), "qa-boot", moto.startedAt, START))
            assertTrue(store.append(token, records()))
            val retained = db.leanDao().chunks(token.id)
            val rawBefore = db.leanDao().rawBytes()
            assertTrue(rawBefore > 0)
            val oversizedForQuota = List(160) { i -> LeanImuRecord(LeanSensorKind.ACCELEROMETER,
                10L + i, START + 100_000_000L + i * 20_000_000L, 0.0, 0.0, 9.80665, 3) }
            assertFalse(store.append(token, oversizedForQuota))
            assertEquals(rawBefore, db.leanDao().rawBytes())
            val stillRetained = db.leanDao().chunks(token.id)
            assertEquals(retained.map { it.sha256 }, stillRetained.map { it.sha256 })
            retained.zip(stillRetained).forEach { (a, b) -> assertArrayEquals(a.payload, b.payload) }
            val point = TrackPoint(id = id(), journeyId = moto.id, latitude = 0.0, longitude = 0.0,
                recordedAt = moto.startedAt + 1_000, accuracy = 5f)
            diary.addPoint(point)
            assertEquals(listOf(point), diary.journeyPoints(moto.id))
            assertEquals(moto, diary.getJourney(moto.id))
            store.seal(token, START + 4_000_000_000L)
            db.diaryDao().save(moto.copy(endedAt = moto.startedAt + 4_000))
            val pending = store.pendingInputs().single { it.token.id == token.id }
            assertTrue("Capacity refusal must remain explicitly partial", pending.partialReasons.isNotEmpty())
            assertTrue(db.leanDao().chunks(token.id).isNotEmpty())
            assertNull(awaitReport(store, moto.id).leftDegrees)
            // Small configured quota exercises the real admission path; not a 256MiB load/energy test.
        }
    }

    @Test fun partialResultIsDurableBeforeRawCleanupAndRecoveryIsIdempotent() = runBlocking(Dispatchers.IO) {
        withRoom { db, diary, _ ->
            val store = LeanStore(context, db)
            val (moto, input) = sealedInput(db, store, "capture_interrupted", dropped = 2)
            assertTrue(input.partialReasons.contains("capture_interrupted"))
            assertEquals(2L, input.droppedSamples)
            val beforeRaw = db.leanDao().chunks(input.token.id)
            assertTrue(beforeRaw.isNotEmpty())
            val result = estimate(input)
            assertTrue(store.commitResult(input.token.id, input.inputHash, result) {})
            assertNotNull(db.leanDao().result(input.token.id))
            assertEquals(beforeRaw.size, db.leanDao().chunks(input.token.id).size)
            val published = awaitReport(store, moto.id) { !it.pending && it.algorithmVersions.contains("qa-lifecycle-v1") }
            assertEquals(result.leftDegrees, published.leftDegrees)
            assertEquals(result.rightDegrees, published.rightDegrees)
            assertTrue(published.algorithmVersions.contains(result.algorithmVersion))
            val recovered = LeanStore(context, db) // Reconstruct at the committed-result/raw-not-unlinked boundary.
            recovered.cleanupCommittedRaw()
            assertTrue(db.leanDao().chunks(input.token.id).isEmpty())
            recovered.cleanupCommittedRaw()
            assertEquals(published, awaitReport(recovered, moto.id) { !it.pending && it.algorithmVersions.contains("qa-lifecycle-v1") })
            assertTrue(recovered.pendingInputs().isEmpty())
            diary.deleteJourney(moto.id)
            assertNull(diary.getJourney(moto.id))
            assertNull(db.leanDao().session(input.token.id))
            assertNull(db.leanDao().result(input.token.id))
            assertFalse(recovered.commitResult(input.token.id, input.inputHash, result) {})
            val removed = awaitReport(recovered, moto.id)
            assertNull(removed.leftDegrees); assertTrue(removed.algorithmVersions.isEmpty())
            // Logical Room phase recovery, not a process kill or forensic erasure claim.
        }
    }

    @Test fun wrongHashModeChangeAndDeletionRejectStalePublication() = runBlocking(Dispatchers.IO) {
        withRoom { db, diary, _ ->
            val store = LeanStore(context, db)
            val (moto, input) = sealedInput(db, store)
            val result = estimate(input)
            val wrong = if (input.inputHash != "a".repeat(64)) "a".repeat(64) else "b".repeat(64)
            assertFalse(store.commitResult(input.token.id, wrong, result) {})
            assertNull(db.leanDao().result(input.token.id))
            assertTrue(db.leanDao().chunks(input.token.id).isNotEmpty())
            db.diaryDao().save(moto.copy(transport = Transport.CAR))
            assertFalse(store.commitResult(input.token.id, input.inputHash, result) {})
            assertNull(db.leanDao().result(input.token.id))
            assertNull(awaitReport(store, moto.id).leftDegrees)
            diary.deleteJourney(moto.id)
            assertFalse(store.append(input.token, records(START + 1_000_000_000L)))
            assertFalse(store.commitResult(input.token.id, input.inputHash, result) {})
            assertTrue(db.leanDao().chunks(input.token.id).isEmpty())
        }
    }

    @Test fun processingCancellationPreservesPendingRawThenLifecycleEstimatorCommits() = runBlocking(Dispatchers.IO) {
        withRoom { db, _, _ ->
            val store = LeanStore(context, db)
            val (moto, input) = sealedInput(db, store)
            val before = db.leanDao().chunks(input.token.id)
            val estimator = object : LeanEstimator {
                override fun estimate(session: LeanInputSession, records: Sequence<LeanRecord>, checkAllowed: () -> Unit): LeanEstimate {
                    checkAllowed()
                    val values = records.toList()
                    assertEquals(this@Moto0110DeviceChecks.records(), values)
                    val gnss = values.filterIsInstance<LeanGnssRecord>().single()
                    assertNull(gnss.speedMps); assertNull(gnss.bearingDegrees)
                    assertNull(gnss.speedAccuracyMps); assertNull(gnss.elapsedUncertaintyNanos)
                    return this@Moto0110DeviceChecks.estimate(session)
                }
            }
            val cancelled = try {
                store.processPending(estimator) { throw CancellationException("synthetic QA gate closed") }
                false
            } catch (_: CancellationException) { true }
            assertTrue(cancelled)
            assertNull(db.leanDao().result(input.token.id))
            assertEquals(before.map { it.sha256 }, db.leanDao().chunks(input.token.id).map { it.sha256 })
            assertTrue(store.pendingInputs().any { it.token.id == input.token.id })
            store.processPending(estimator) {}
            val report = awaitReport(store, moto.id) { !it.pending && it.algorithmVersions.contains("qa-lifecycle-v1") }
            assertEquals(setOf("qa-lifecycle-v1"), report.algorithmVersions)
            assertEquals(12.0, report.leftDegrees!!, 0.0)
            assertTrue(db.leanDao().chunks(input.token.id).isEmpty())
            // Deterministic estimator exercises persistence/cancellation only, never production estimator accuracy.
        }
    }

    @Test fun basicCaptureEligibilityAndMissingSensorFallbackKeepOrdinaryRecordingData() = runBlocking(Dispatchers.IO) {
        withRoom { db, diary, _ ->
            val moto = journey(); db.diaryDao().save(moto)
            val settings = LeanCaptureSettings(enabled = true, mount = LeanMount.BODY, calibration = calibration(moto.startedAt))
            assertTrue(leanCaptureEligible(moto, settings))
            assertFalse(leanCaptureEligible(null, settings))
            assertFalse(leanCaptureEligible(moto, settings.copy(enabled = false)))
            assertFalse(leanCaptureEligible(moto, settings.copy(mount = LeanMount.HANDLEBAR)))
            assertFalse(leanCaptureEligible(moto, settings.copy(mount = LeanMount.UNKNOWN)))
            assertFalse(leanCaptureEligible(moto, settings.copy(calibration = null)))
            assertFalse(leanCaptureEligible(moto.copy(transport = Transport.CAR), settings))
            assertFalse(leanCaptureEligible(moto.copy(status = JourneyStatus.TEMPORARY), settings))
            assertFalse(leanCaptureEligible(moto.copy(endedAt = moto.startedAt + 1_000), settings))
            val absentSensors = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getSystemService(name: String): Any? = if (name == Context.SENSOR_SERVICE) null else super.getSystemService(name)
            }
            var callbacks = 0
            val capture = LeanSensorCapture(absentSensors, { callbacks++ })
            try { assertFalse(capture.start()); assertEquals(0, callbacks) } finally { capture.stop() }
            val point = TrackPoint(id = id(), journeyId = moto.id, latitude = 0.0, longitude = 0.0,
                recordedAt = moto.startedAt + 1_000, accuracy = 5f)
            diary.addPoint(point)
            assertEquals(listOf(point), diary.journeyPoints(moto.id))
            assertEquals(moto, diary.getJourney(moto.id))
            // Local missing-service facade only; no global sensor/GPS/battery override or real listener registration.
        }
    }

    private suspend fun awaitReport(store: LeanStore, journeyId: String,
        accept: (LeanJourneyReport) -> Boolean = { true }): LeanJourneyReport =
        withTimeout(5_000) { store.observeReport(journeyId).first(accept) }

    private suspend fun withRoom(block: suspend (DiaryDatabase, DiaryRepository, TravelLibraryRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try { withTimeout(30_000) { block(db, DiaryRepository(context, db), TravelLibraryRepository(db)) } } finally { db.close() }
    }
    private suspend fun sealedInput(db: DiaryDatabase, store: LeanStore, reason: String? = null, dropped: Long = 0): Pair<Journey, LeanInputSession> {
        val moto = journey(); db.diaryDao().save(moto)
        val token = requireNotNull(store.begin(moto.id, calibration(moto.startedAt), "qa-boot", moto.startedAt, START))
        assertTrue(store.append(token, records()))
        store.seal(token, START + 1_000_000_000L, reason, dropped)
        val ended = moto.copy(endedAt = moto.startedAt + 1_000); db.diaryDao().save(ended)
        return ended to store.pendingInputs().single { it.token.id == token.id }
    }
    private fun journey() = Journey(id = id(), transport = Transport.MOTORCYCLE, startedAt = syntheticUtc)
    private fun calibration(at: Long) = LeanCalibration(UUID.randomUUID().toString(), at - 1_000,
        listOf(0.0, 0.0, 9.80665), listOf(0.0, 0.0, 0.0), bodyConfirmed = true)
    private fun records(start: Long = START): List<LeanRecord> = listOf(
        LeanImuRecord(LeanSensorKind.ACCELEROMETER, 1, start, 0.0, 0.0, 9.80665, 3),
        LeanImuRecord(LeanSensorKind.GYROSCOPE, 2, start + 20_000_000L, 0.0, 0.0, 0.0, 3),
        LeanImuRecord(LeanSensorKind.ACCELEROMETER, 3, start + 40_000_000L, 0.0, 0.0, 9.80665, 3),
        LeanImuRecord(LeanSensorKind.GYROSCOPE, 4, start + 60_000_000L, 0.0, 0.0, 0.0, 3),
        LeanGnssRecord(start + 80_000_000L, syntheticUtc + (start - START) / 1_000_000L + 80, 0.0, 0.0, null, null, null, null, null, null, false, null),
    )
    private fun estimate(input: LeanInputSession) = LeanEstimate("qa-lifecycle-v1", input.calibration.epoch,
        12.0, 7.0, 40, 80, input.partialReasons,
        listOf(LeanCurveResult(START, START + 80_000_000L, 12.0, 7.0, 0.0, 0.0)),
        listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    private fun id() = "qa-moto-0110-${UUID.randomUUID()}"
    private fun archive(): File {
        val folder = File(context.cacheDir, "camera").apply { check(isDirectory || mkdirs()) }.canonicalFile
        check(folder.parentFile == context.cacheDir.canonicalFile)
        return File(folder, "qa-moto-0110-${UUID.randomUUID()}.zip").also { check(it.createNewFile()); archives += it }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private fun legacySeven() = JSONObject("""
        {"format":"org.iz.navigation.backup","version":7,"exportedAt":6000,
         "journeys":[{"id":"legacy","title":"Old walk","transport":"WALK","status":"CONFIRMED","startedAt":1000,"endedAt":5000,"expiresAt":null,"note":"Keep old note","interrupted":false,"stepCount":23}],
         "points":[{"id":"legacy-point","journeyId":"legacy","latitude":0.0,"longitude":0.0,"recordedAt":2000,"accuracy":5.0,"speed":null,"altitude":null,"breakBefore":false}],
         "places":[{"id":"legacy-place","name":"Old place","latitude":0.0,"longitude":0.0,"googlePlaceId":null,"osmType":null,"osmId":null,"source":"LEGACY","sortOrder":0}],
         "visits":[],"photos":[],"drafts":[],"contributions":[],"healthSamples":[],"mapEdits":[],
         "watchHealthSessions":[],"watchHealthSamples":[],"savedPlans":[],"importedTracks":[],"collections":[],"memberships":[]}
    """)
    companion object { private const val START = 1_000_000_000L }
}
