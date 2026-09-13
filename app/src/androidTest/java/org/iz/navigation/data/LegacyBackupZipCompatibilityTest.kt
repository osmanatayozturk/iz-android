package org.iz.navigation.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic files only; run on the disposable QA emulator because each test replaces its diary. */
@RunWith(AndroidJUnit4::class)
class LegacyBackupZipCompatibilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)
    private val backup = DiaryBackup(context, repository)
    private val photoBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
    private val archives = mutableListOf<File>()

    @Before fun setup() = runBlocking { repository.restore(DiarySnapshot()) }
    @After fun cleanup() = runBlocking {
        repository.restore(DiarySnapshot())
        archives.forEach { it.delete() }
    }

    @Test fun legacyZipRestoresRecordsAndPhotoBytesThenReexportsNeutralZipWithHealthOptIn() = runBlocking {
        // This fixture is independent of the current encoder: an old backup must remain readable
        // even after the exporter changes its format marker or field serialization.
        val legacyArchive = archive()
        writeLegacyArchive(legacyArchive, LegacyBackupFixture.manifest())
        for (includeHealth in listOf(false, true)) {
            backup.importFrom(uri(legacyArchive))
            val imported = repository.snapshot()
            assertRestored(LegacyBackupFixture.expected(), imported)
            assertNotEquals("photos/old.jpg", imported.photos.single().relativePath)
            assertFalse(imported.watchHealthSessions.single().acceptsUploads)
            assertEquals(ContributionStatus.UNKNOWN, imported.contributions.single().status)
            assertTrue(repository.healthSyncs.first().isEmpty())

            val neutralArchive = archive()
            if (includeHealth) backup.exportTo(uri(neutralArchive), includeHealth = true)
            else backup.exportTo(uri(neutralArchive))
            ZipFile(neutralArchive).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertEquals(setOf("manifest.json", imported.photos.single().relativePath), entries)
                val json = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                assertEquals("org.iz.navigation.backup", json.getString("format"))
                assertEquals(7, json.getInt("version"))
                for (key in listOf("healthSamples", "watchHealthSessions", "watchHealthSamples")) {
                    assertEquals(key, if (includeHealth) 1 else 0, json.getJSONArray(key).length())
                }
                assertArrayEquals(photoBytes, zip.getInputStream(zip.getEntry(imported.photos.single().relativePath)).use { it.readBytes() })
            }

            repository.restore(DiarySnapshot())
            backup.importFrom(uri(neutralArchive))
            val expected = LegacyBackupFixture.expected().let {
                if (includeHealth) it else it.copy(healthSamples = emptyList(),
                    watchHealthSessions = emptyList(), watchHealthSamples = emptyList())
            }
            assertRestored(expected, repository.snapshot())
            assertTrue(repository.healthSyncs.first().isEmpty())
        }
    }

    @Test fun unknownMarkerRejectsZipWithoutReplacingExistingDiaryOrPhoto() = runBlocking {
        val valid = archive()
        writeLegacyArchive(valid, LegacyBackupFixture.manifest())
        backup.importFrom(uri(valid))
        val before = repository.snapshot()
        val invalid = archive()
        writeLegacyArchive(invalid, LegacyBackupFixture.manifest().put("format", "other.backup"))
        var rejected = false
        try { backup.importFrom(uri(invalid)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("An unrecognized marker must reject the whole archive", rejected)
        assertEquals(before, repository.snapshot())
        assertArrayEquals(photoBytes, repository.photoFile(before.photos.single().relativePath).readBytes())
    }

    private fun assertRestored(expected: DiarySnapshot, actual: DiarySnapshot) {
        assertArrayEquals(photoBytes, repository.photoFile(actual.photos.single().relativePath).readBytes())
        // Import intentionally installs photos under fresh safe names, while preserving their IDs and links.
        val normalized = actual.copy(photos = actual.photos.map { it.copy(relativePath = "photos/old.jpg") })
        assertEquals(expected, normalized)
    }

    private fun archive(): File {
        val directory = File(context.cacheDir, "camera")
        check(directory.mkdirs() || directory.isDirectory)
        return File(directory, "identity-backup-${UUID.randomUUID()}.zip").also { archives += it }
    }

    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun writeLegacyArchive(file: File, manifest: JSONObject) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("photos/old.jpg"))
            zip.write(photoBytes)
            zip.closeEntry()
        }
    }
}
