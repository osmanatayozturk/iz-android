package org.iz.navigation.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.lean.HandlebarLeanEstimator
import org.iz.navigation.lean.LeanEstimate
import org.iz.navigation.lean.LeanJourneyReport
import org.iz.navigation.lean.handlebarTimingCalibration
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeDiaryBackupAcceptanceTest {
    @Test fun exportsAndRestoresConfirmedDiaryAboveFormerManifestLimit() = runBlocking(Dispatchers.IO) {
        val app = requireBackupEmulator()
        val source = TestDiary(app)
        val destination = TestDiary(app)
        val archive = source.archive()
        val startedAt = 1_740_000_000_000L
        val journey = Journey(
            id = "71298b7d-c805-4892-88d2-b66fd106ce2b",
            title = "QA Sürüş",
            note = "Sentetik günlük ve rota",
            transport = Transport.MOTORCYCLE,
            startedAt = startedAt,
            endedAt = startedAt + (POINT_COUNT + 1L) * 1_000L,
        )
        val health = ImportedHealthSample(
            "qa-pulse", HealthRules.SAMSUNG_HEALTH_PACKAGE, 1, "Synthetic", "QA Watch",
            HealthMetric.HEART_RATE_BPM, startedAt + 10_000L, startedAt + 10_000L, 92.0,
        ).forJourney(journey.id)
        val oldJourney = journey.copy(id = "obsolete-destination", title = "Replace this")
        val place = Place(id = "qa-large-place", name = "Sentetik durak", latitude = .24, longitude = -.37)
        val visit = Visit(id = "qa-large-visit", placeId = place.id, journeyId = journey.id,
            visitedAt = startedAt + 20_000L, note = "Sentetik ziyaret notu")
        val draft = ShareDraft(id = "qa-large-note", visitId = visit.id, text = "Sentetik not", rating = 4,
            photoIds = emptyList())
        val leanTrip = journey.copy(id = "qa-large-lean", title = "Sentetik yatış",
            startedAt = checkNotNull(journey.endedAt) + 60_000L, endedAt = null)
        val track = ImportedTrack(id = "qa-book-track", name = "Sentetik kütüphane izi", createdAt = startedAt,
            segments = listOf(
                TrackSegment("İlk", listOf(WeatherCoordinate(.24, -.37), WeatherCoordinate(.2405, -.37)), "İz", 0),
                TrackSegment("İkinci", listOf(WeatherCoordinate(.242, -.373), WeatherCoordinate(.242, -.3735)), "İz", 0),
            ))
        try {
            seedPoints(source.db, journey)
            source.db.diaryDao().saveHealthSamples(listOf(health))
            source.db.diaryDao().save(place)
            source.db.diaryDao().save(visit)
            source.db.diaryDao().save(draft)
            source.db.travelLibraryDao().storeTrack(track)
            val leanReport = seedLeanSummary(source, leanTrip)
            destination.db.diaryDao().save(oldJourney)
            val sourceFingerprint = fingerprint(source.db, journey.id)
            assertEquals(POINT_COUNT, sourceFingerprint.count)
            assertEquals(pointId(0), sourceFingerprint.first)
            assertEquals(pointId(POINT_COUNT - 1), sourceFingerprint.last)

            // These are the same preparation and URI save operations used by the diary UI.
            val prepared = source.backup.prepareExport(includeHealth = true)
            try {
                source.backup.savePrepared(prepared, source.uri(archive))
                assertEquals(prepared.sizeBytes, archive.length())
                assertEquals(prepared.sha256, sha256(archive))
            } finally {
                source.backup.discardPrepared(prepared)
            }
            val v13PointBytes = ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val manifest = checkNotNull(zip.getEntry("manifest.json"))
                assertTrue("Paged manifest must stay small", manifest.size in 1L..MAX_PAGE_BYTES)
                val index = zip.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                assertEquals(14, index.getInt("version"))
                val directory = index.getJSONArray("bulkPages")
                val groups = List(directory.length()) { directory.getJSONObject(it) }.associateBy { it.getString("type") }
                assertEquals(directory.length(), groups.size)
                assertEquals(POINT_COUNT, groups.getValue("points").getInt("records"))
                assertEquals(1, groups.getValue("healthSamples").getInt("records"))
                assertEquals(0, groups.getValue("watchHealthSamples").getInt("records"))
                assertEquals(1, groups.getValue("importedTracks").getInt("records"))
                assertEquals(2, groups.getValue("trackSegments").getInt("records"))
                assertEquals(4, groups.getValue("trackCoordinates").getInt("records"))
                val indexedPaths = mutableSetOf<String>()
                var pointPages = 0
                var pointBytes = 0L
                for ((type, group) in groups) {
                    assertEquals(0, index.getJSONArray(type).length())
                    val parts = group.getJSONArray("parts")
                    assertEquals(group.getInt("partCount"), parts.length())
                    var rows = 0
                    for (sequence in 0 until parts.length()) {
                        val part = parts.getJSONObject(sequence)
                        val path = "bulk/$type/${sequence.toString().padStart(6, '0')}.json"
                        assertEquals(sequence, part.getInt("sequence"))
                        assertEquals(path, part.getString("path"))
                        assertTrue(indexedPaths.add(path))
                        val page = checkNotNull(zip.getEntry(path))
                        assertTrue("Bulk page violates the 1 MiB cap", page.size in 1L..MAX_PAGE_BYTES)
                        assertEquals(part.getLong("bytes"), page.size)
                        val payload = zip.getInputStream(page).use { it.readBytes() }
                        assertEquals(part.getString("sha256"), sha256(payload))
                        val count = JSONArray(payload.toString(Charsets.UTF_8)).length()
                        assertTrue("Bulk page violates the row cap", count in 1..512)
                        assertEquals(part.getInt("records"), count)
                        rows += count
                        if (type == "points") {
                            pointPages++
                            pointBytes += page.size
                        }
                    }
                    assertEquals(group.getInt("records"), rows)
                }
                assertEquals(entries.filter { it.name.startsWith("bulk/") }.map { it.name }.toSet(), indexedPaths)
                assertTrue(pointPages > 1)
                // Joining these real JSON arrays uses exactly one comma in place of each pair of brackets.
                pointBytes - (pointPages - 1L)
            }
            assertTrue("The v13-equivalent GPS array alone must exceed the former 32 MiB cap", v13PointBytes > OLD_CAP)

            var replaced = 0
            destination.backup.importFrom(source.uri(archive)) { replaced++ }
            assertEquals(1, replaced)
            assertNull(destination.repo.getJourney(oldJourney.id))
            assertEquals(journey, destination.repo.getJourney(journey.id))
            assertEquals(sourceFingerprint, fingerprint(destination.db, journey.id))
            assertEquals(listOf(health), destination.db.diaryDao().healthSamples().first())
            assertEquals(listOf(place), destination.repo.places.first())
            assertEquals(listOf(visit), destination.repo.visits.first())
            assertEquals(listOf(draft), destination.repo.drafts.first())
            assertEquals(leanReport, destination.repo.leanStore.observeReport(leanTrip.id).first())
            assertEquals(listOf(track), destination.db.travelLibraryDao().allTracks().map { it.model() })
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "\nLarge backup QA: format=14, v13PointBytes=$v13PointBytes, points=${sourceFingerprint.count}, " +
                    "gpsDigest=${sourceFingerprint.sha256}, healthCount=1\n")
            })
        } finally {
            destination.close()
            source.close()
        }
    }

    @Test fun exportsLongDiaryNotesInV14WithNoGpsRows() = runBlocking(Dispatchers.IO) {
        val app = requireBackupEmulator()
        val source = TestDiary(app)
        val destination = TestDiary(app)
        val fullNote = "QA diary note. ".repeat(560)
        val count = 4_250
        try {
            source.db.withTransaction {
                for (index in 0 until count) {
                    source.db.diaryDao().save(Journey(id = "qa-note-$index", title = "Diary $index",
                        note = fullNote, startedAt = index * 10_000L, endedAt = index * 10_000L + 1_000L))
                }
            }
            val archive = source.archive()
            source.backup.exportTo(source.uri(archive))
            val v13JourneyBytes = ZipFile(archive).use { zip ->
                val entry = checkNotNull(zip.getEntry("manifest.json"))
                assertTrue(entry.size in 1L..MAX_PAGE_BYTES)
                val manifest = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                assertEquals(14, manifest.getInt("version"))
                val directory = manifest.getJSONArray("bulkPages")
                val groups = List(directory.length()) { directory.getJSONObject(it) }.associateBy { it.getString("type") }
                assertEquals(0, groups.getValue("points").getInt("records"))
                val journeys = groups.getValue("journeys")
                assertEquals(count, journeys.getInt("records"))
                val parts = journeys.getJSONArray("parts")
                var rawBytes = 0L
                for (i in 0 until parts.length()) {
                    val name = parts.getJSONObject(i).getString("path")
                    val page = checkNotNull(zip.getEntry(name))
                    assertTrue(name.startsWith("bulk/journeys/") && page.size in 1L..MAX_PAGE_BYTES)
                    rawBytes += page.size
                }
                rawBytes - (parts.length() - 1L)
            }
            assertTrue(v13JourneyBytes > OLD_CAP)
            destination.backup.importFrom(source.uri(archive))
            var restored = 0
            destination.db.openHelper.readableDatabase.query(
                "SELECT id,note,startedAt,endedAt FROM journeys ORDER BY startedAt,id",
            ).use { row ->
                while (row.moveToNext()) {
                    assertEquals("qa-note-$restored", row.getString(0))
                    assertTrue("Long diary note changed at row $restored", fullNote == row.getString(1))
                    assertEquals(restored * 10_000L, row.getLong(2))
                    assertEquals(restored * 10_000L + 1_000L, row.getLong(3))
                    restored++
                }
            }
            assertEquals(count, restored)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "\nLarge backup QA: metadataOnly=14, journeys=$count, restored=$restored, v13JourneyBytes=$v13JourneyBytes, gpsRows=0\n")
            })
        } finally {
            destination.close()
            source.close()
        }
    }

    @Test fun restoresLegacyZipAndRejectsLateCorruptionAndOversizeWithoutChangingTheDiary() = runBlocking(Dispatchers.IO) {
        val app = requireBackupEmulator()
        val pointWrites = AtomicInteger()
        val target = TestDiary(app, pointWrites)
        val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 4, 8, 16, 0xff.toByte(), 0xd9.toByte())
        try {
            val legacy = target.archive()
            writeArchive(legacy, LegacyBackupFixture.manifest(), photo)
            target.backup.importFrom(target.uri(legacy))
            val before = target.repo.snapshot()
            val installedPhoto = before.photos.single().relativePath
            assertEquals(
                LegacyBackupFixture.expected(),
                before.copy(photos = before.photos.map { it.copy(relativePath = "photos/old.jpg") }),
            )
            assertArrayEquals(photo, target.repo.photoFile(installedPhoto).readBytes())
            val small = target.archive()
            target.backup.exportTo(target.uri(small), includeHealth = true)
            ZipFile(small).use { zip ->
                val manifest = checkNotNull(zip.getEntry("manifest.json"))
                val json = zip.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                assertEquals(13, json.getInt("version"))
                assertTrue(!json.has("bulkPages"))
            }

            val duplicate = target.archive()
            val corrupt = LegacyBackupFixture.manifest().put("format", "org.iz.navigation.backup").put("version", 14)
            corrupt.getJSONArray("journeys").getJSONObject(0).put("title", "Replacement that must roll back")
            val template = corrupt.getJSONArray("points").getJSONObject(0).toString()
            val firstPage = JSONArray()
            repeat(512) { index ->
                firstPage.put(JSONObject(template).put("id", "late-$index").put("recordedAt", 2_000 + index))
            }
            val lastPage = JSONArray().put(JSONObject(template).put("id", "late-0").put("recordedAt", 3_300))
            val pages = linkedMapOf<String, ByteArray>()
            val directory = JSONArray()
            for (type in BackupBulkPages.TYPES) {
                val prior = corrupt.optJSONArray(type) ?: JSONArray()
                corrupt.put(type, JSONArray())
                val arrays = when {
                    type == "points" -> listOf(firstPage, lastPage)
                    prior.length() > 0 -> listOf(prior)
                    else -> emptyList()
                }
                val parts = JSONArray()
                arrays.forEachIndexed { sequence, values ->
                    val path = "bulk/$type/${sequence.toString().padStart(6, '0')}.json"
                    val bytes = values.toString().toByteArray(Charsets.UTF_8)
                    pages[path] = bytes
                    parts.put(JSONObject().put("sequence", sequence).put("path", path).put("records", values.length())
                        .put("bytes", bytes.size).put("sha256", sha256(bytes)))
                }
                directory.put(JSONObject().put("type", type).put("records", arrays.sumOf { it.length() })
                    .put("partCount", parts.length()).put("parts", parts))
            }
            corrupt.put("bulkPages", directory)
            writeArchive(duplicate, corrupt, photo, pages = pages)
            pointWrites.set(0)
            assertRejectedWithoutChange(target, duplicate, before, installedPhoto, photo, "UNIQUE constraint")
            val lateWrites = pointWrites.get()
            assertTrue("A full page must be written before the final duplicate is rejected", lateWrites >= 512)

            val tooLarge = target.archive()
            writeArchive(tooLarge, LegacyBackupFixture.manifest(), photo, oversizedPhoto = true)
            pointWrites.set(0)
            assertRejectedWithoutChange(target, tooLarge, before, installedPhoto, photo, "boyutu aşıyor")
            assertEquals("Attachment limit must be checked before any replacement writes", 0, pointWrites.get())
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "\nLarge backup QA: legacy=5, rollback=14, pointWritesBeforeRejection=$lateWrites, oversizeWrites=0\n")
            })
        } finally {
            target.close()
        }
    }

    private suspend fun assertRejectedWithoutChange(
        target: TestDiary,
        archive: File,
        before: DiarySnapshot,
        installedPhoto: String,
        photo: ByteArray,
        expectedError: String,
    ) {
        var commits = 0
        val failure = runCatching { target.backup.importFrom(target.uri(archive)) { commits++ } }.exceptionOrNull()
        assertNotNull("Restore unexpectedly committed", failure)
        assertTrue("Restore did not report the expected validation error: " + failure?.message,
            generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(expectedError, ignoreCase = true) })
        assertEquals(0, commits)
        assertEquals(before, target.repo.snapshot())
        assertArrayEquals(photo, target.repo.photoFile(installedPhoto).readBytes())
        assertEquals(
            listOf(target.repo.photoFile(installedPhoto).name),
            target.repo.photoFile(installedPhoto).parentFile?.list()?.toList(),
        )
    }

    private fun writeArchive(
        file: File,
        manifest: JSONObject,
        photo: ByteArray,
        oversizedPhoto: Boolean = false,
        pages: Map<String, ByteArray> = emptyMap(),
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("photos/old.jpg"))
            if (oversizedPhoto) {
                val block = ByteArray(64 * 1024)
                repeat(50 * 1024 / 64) { zip.write(block) }
                zip.write(0)
            } else zip.write(photo)
            zip.closeEntry()
            for ((path, bytes) in pages) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private suspend fun seedPoints(db: DiaryDatabase, journey: Journey) = db.withTransaction {
        db.diaryDao().save(journey)
        val sql = "INSERT INTO track_points(id,journeyId,latitude,longitude,recordedAt,accuracy,speed,altitude,breakBefore) " +
            "VALUES (?,?,?,?,?,?,?,?,?)"
        db.openHelper.writableDatabase.compileStatement(sql).use { statement ->
            statement.bindString(2, journey.id)
            repeat(POINT_COUNT) { i ->
                statement.bindString(1, pointId(i))
                statement.bindDouble(3, .24 + (i % 20_000) * .000_025)
                statement.bindDouble(4, -.37 - (i / 20_000) * .000_025)
                statement.bindLong(5, journey.startedAt + i * 1_000L)
                statement.bindDouble(6, 5.0)
                statement.bindDouble(7, 2.5 + i % 5)
                if (i % 2 == 0) statement.bindDouble(8, 20.0 + i % 100) else statement.bindNull(8)
                statement.bindLong(9, if (i % 20_000 == 0) 1L else 0L)
                statement.executeInsert()
            }
        }
    }

    private suspend fun seedLeanSummary(source: TestDiary, trip: Journey): LeanJourneyReport {
        val elapsed = 1_000_000_000L
        val store = source.repo.leanStore
        source.db.diaryDao().save(trip)
        val token = checkNotNull(store.begin(trip.id, handlebarTimingCalibration, "qa-boot", trip.startedAt, elapsed))
        store.seal(token, elapsed + 10_000_000_000L)
        source.db.diaryDao().save(trip.copy(endedAt = trip.startedAt + 10_000L))
        val pending = store.pendingInputs().single { it.token.id == token.id }
        val estimate = LeanEstimate(HandlebarLeanEstimator.VERSION, handlebarTimingCalibration.epoch,
            null, 24.0, 5_000L, 10_000L, emptySet(), emptyList())
        assertTrue(store.commitResult(token.id, pending.inputHash, estimate) {})
        return store.observeReport(trip.id).first().also { assertNotNull(it.handlebar) }
    }

    private data class PointFingerprint(val count: Int, val first: String?, val last: String?, val sha256: String)

    private fun fingerprint(db: DiaryDatabase, journeyId: String): PointFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
        var count = 0
        var first: String? = null
        var last: String? = null
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            db.openHelper.readableDatabase.query(
                "SELECT id,journeyId,latitude,longitude,recordedAt,accuracy,speed,altitude,breakBefore " +
                    "FROM track_points WHERE journeyId=? ORDER BY recordedAt,id",
                arrayOf(journeyId),
            ).use { row ->
                while (row.moveToNext()) {
                    val id = row.getString(0)
                    if (count++ == 0) first = id
                    last = id
                    out.writeUTF(id)
                    out.writeUTF(row.getString(1))
                    out.writeDouble(row.getDouble(2))
                    out.writeDouble(row.getDouble(3))
                    out.writeLong(row.getLong(4))
                    out.writeDouble(row.getDouble(5))
                    out.writeDouble(row.getDouble(6))
                    out.writeBoolean(row.isNull(7))
                    if (!row.isNull(7)) out.writeDouble(row.getDouble(7))
                    out.writeInt(row.getInt(8))
                }
            }
        }
        return PointFingerprint(count, first, last, digest.digest().hex())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private class TestDiary(private val app: Context, pointWrites: AtomicInteger? = null) {
        private val id = "qa-large-backup-${UUID.randomUUID()}"
        private val base = File(app.cacheDir, id)
        private val archives = mutableListOf<File>()
        private val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(base, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(base, "private").apply { mkdirs() }
            override fun getCacheDir(): File = File(base, "cache").apply { mkdirs() }
        }
        val db = checkNotNull(Room.databaseBuilder(app, DiaryDatabase::class.java, "$id.db").apply {
            if (pointWrites != null) setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (sqlQuery.startsWith("INSERT", ignoreCase = true) &&
                        sqlQuery.contains("`track_points`")) pointWrites.incrementAndGet()
                }
            }, Executor { it.run() })
        }.build())
        val repo = DiaryRepository(isolated, db)
        val backup = DiaryBackup(isolated, repo)

        fun archive(): File {
            val camera = File(app.cacheDir, "camera")
            check(camera.isDirectory || camera.mkdirs())
            return File(camera, "$id-${archives.size}.zip").also { archives.add(it) }
        }

        fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

        fun close() {
            db.close()
            app.deleteDatabase("$id.db")
            archives.forEach { it.delete() }
            base.deleteRecursively()
        }
    }

    private fun requireBackupEmulator(): Context {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = InstrumentationRegistry.getArguments().getString("qaTargetPackage")
        assertEquals("org.iz.navigation.qabuyukyedek20261127", app.packageName)
        if (requested != null) assertEquals(requested, app.packageName)
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(1, Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertEquals(0, Settings.Global.getInt(app.contentResolver, Settings.Global.WIFI_ON))
        return app
    }

    private companion object {
        const val POINT_COUNT = 200_000
        const val OLD_CAP = 32L * 1024 * 1024
        const val MAX_PAGE_BYTES = 1024L * 1024
        fun pointId(index: Int) = "73f7e78f-b71f-406d-a6b7-" + index.toString().padStart(12, '0')
    }
}
