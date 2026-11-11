package org.iz.navigation.data

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingBackupZipTest {
    @Test fun preparedZipKeepsTiedPointsAndCorrectionProofThroughRestore() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID()
        val root = File(app.cacheDir, "streaming-backup-$id")
        val cameraCache = File(app.cacheDir, "camera").apply { mkdirs() }
        val destination = File(cameraCache, "streaming-backup-$id.zip")
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }
        }
        val queries = ConcurrentLinkedQueue<String>()
        val queryCallback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                queries.add(sqlQuery)
            }
        }
        val database = Room.inMemoryDatabaseBuilder(app, DiaryDatabase::class.java)
            .setQueryCallback(queryCallback, Executor { it.run() })
            .build()

        try {
            val repository = DiaryRepository(context, database)
            val backup = DiaryBackup(context, repository)
            val journey = Journey(
                id = "confirmed", title = "Çığlık ve \"İz\" 🧭",
                note = "Şanlıurfa\nGüzergâh \\ dönüş", startedAt = 1_000, endedAt = 5_000,
            )
            val temporary = journey.copy(id = "temporary", status = JourneyStatus.TEMPORARY, endedAt = null)
            val place = Place(id = "place", name = "Iğdır")
            val visits = listOf(
                Visit(id = "confirmed-visit", placeId = place.id, journeyId = journey.id, visitedAt = 3_000, note = "Öğle"),
                Visit(id = "temporary-visit", placeId = place.id, journeyId = temporary.id, visitedAt = 3_000),
            )
            // Reversed insertion order and one timestamp expose skips or duplicates at keyset ties.
            val points = List(2_049) { index ->
                TrackPoint(
                    id = "point-${index.toString().padStart(4, '0')}", journeyId = journey.id,
                    latitude = 40.0 + index / 100_000.0, longitude = 30.0,
                    recordedAt = 2_000, accuracy = 5f,
                )
            }
            val collection = JourneyCollection(id = "collection", name = "Günlük İzler")
            val membership = CollectionMembership(collection.id, journey.id, 0)
            val excludedMembership = CollectionMembership(collection.id, temporary.id, 1)
            val importedHealth = ImportedHealthSample(
                "source", HealthRules.SAMSUNG_HEALTH_PACKAGE, 1, "Samsung", "Saat",
                HealthMetric.HEART_RATE_BPM, 2_000, 2_000, 90.0,
            ).forJourney(journey.id)
            val watchSession = WatchHealthSession("session", journey.id, "watch", "Saat", 1_500)
            val watchSample = WatchHealthSample(
                watchSession.id, 1, journey.id, HealthMetric.HEART_RATE_BPM, 2_000, 2_000, 95.0,
            )
            val correction = MapEditDraft(latitude = 41.0, longitude = 29.0, name = "Düzeltilen ad",
                surveyConfirmed = true, status = MapEditStatus.SENDING, stage = MapEditStage.UPDATE_ELEMENT,
                targetType = OsmType.WAY, targetId = 17L, reviewedVersion = 8L, reviewedFingerprint = "c".repeat(64),
                submittedAt = 2_000, submittedBy = 3L, changesetId = 11L,
                localAttemptId = "fixture-export-attempt", localChangesetCreated = true,
                surveyPayload = SurveyObservation(8, "2024-02-29", hours = SurveyFieldObservation(SurveyAction.EDIT, "raw invalid advanced text", false),
                    wheelchair = SurveyFieldObservation(SurveyAction.UNKNOWN)).encode())
            val recovered = correction.copy(status = MapEditStatus.UNKNOWN, localAttemptId = null, localChangesetCreated = false)

            database.withTransaction {
                val dao = database.diaryDao()
                dao.save(journey)
                dao.save(temporary)
                dao.save(place)
                visits.forEach { dao.save(it) }
                points.asReversed().forEach { dao.save(it) }
                dao.save(points.first().copy(id = "temporary-point", journeyId = temporary.id))
                dao.saveHealthSamples(listOf(importedHealth))
                database.watchHealthDao().restoreSessions(listOf(watchSession))
                database.watchHealthDao().restoreSamples(listOf(watchSample))
                database.mapEditDao().save(correction)
                database.travelLibraryDao().save(JourneyCollectionEntity.from(collection))
                database.travelLibraryDao().saveMemberships(
                    listOf(membership, excludedMembership).map(CollectionMembershipEntity::from),
                )
            }

            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", destination)
            val expected = DiarySnapshot(
                journeys = listOf(journey), points = points, places = listOf(place),
                visits = listOf(visits[0], visits[1].copy(journeyId = null)),
                collections = listOf(collection), memberships = listOf(membership), mapEdits = listOf(recovered),
            )
            val rejected = runCatching {
                backup.prepareExport(includeHealth = false, options = BackupExportOptions(manifestLimit = 64L))
            }
            rejected.getOrNull()?.let { backup.discardPrepared(it) }
            assertTrue("Preparation must reject a manifest over its byte cap", rejected.isFailure)

            val healthTables = listOf("journey_health_samples", "watch_health_sessions", "watch_health_samples")
            for (includeHealth in listOf(false, true)) {
                queries.clear()
                val pageSize = 127
                val pointPageSizes = mutableListOf<Int>()
                val options = BackupExportOptions(pageSize = pageSize, onPage = { section, size ->
                    if (section == "points") pointPageSizes.add(size)
                })
                val prepared = backup.prepareExport(includeHealth, options)
                try {
                    assertTrue("Fixture must span multiple point pages", pointPageSizes.count { it > 0 } > 1)
                    assertTrue("Point page exceeds $pageSize rows", pointPageSizes.all { it in 0..pageSize })
                    for (table in healthTables) {
                        assertEquals(
                            "Preparation read from $table with includeHealth=$includeHealth",
                            includeHealth, queries.any { it.contains(table, ignoreCase = true) },
                        )
                    }
                    backup.savePrepared(prepared, uri)
                    assertEquals(prepared.sizeBytes, destination.length())
                    val digest = MessageDigest.getInstance("SHA-256").digest(destination.readBytes())
                    assertEquals(prepared.sha256.lowercase(), digest.joinToString("") { "%02x".format(it) })

                    val decoded = ZipFile(destination).use { zip ->
                        val entry = zip.getEntry("manifest.json") ?: error("Missing manifest.json")
                        val json = JSONObject(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals(11, json.getInt("version"))
                        val mapEdit = json.getJSONArray("mapEdits").getJSONObject(0)
                        assertTrue(!mapEdit.has("localAttemptId") && !mapEdit.has("localChangesetCreated"))
                        BackupJson.decode(json)
                    }
                    val expectedWithHealth = if (includeHealth) expected.copy(
                        healthSamples = listOf(importedHealth),
                        watchHealthSessions = listOf(watchSession.copy(acceptsUploads = false)),
                        watchHealthSamples = listOf(watchSample),
                    ) else expected
                    assertEquals(
                        "Decoded archive for includeHealth=$includeHealth",
                        expectedWithHealth,
                        decoded.copy(points = decoded.points.sortedBy { it.id }, visits = decoded.visits.sortedBy { it.id }),
                    )
                    if (includeHealth) {
                        backup.importFrom(uri)
                        assertEquals(recovered, repository.getMapEdit(correction.id))
                    }
                } finally {
                    backup.discardPrepared(prepared)
                    destination.delete()
                }
            }
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
