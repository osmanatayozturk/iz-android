package org.iz.navigation.data

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Source/target Room files and document scratch space are unique to each test. Actual restore also
 * invokes global analysis/privacy hooks: run only in a separately allocated synthetic QA package,
 * explicitly named by instrumentation argument osmLightEditorZipQaPackage. The product package is
 * rejected before fixture creation/import. No application diary is replaced or singleton swapped.
 */
@RunWith(AndroidJUnit4::class)
class OsmLightEditorZipRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun streamedVersionTwoZipRestoresActionsFrozenRulesAndUnknownFavoritesAfterReopen() = runBlocking(Dispatchers.IO) {
        isolated { f ->
            val drafts = MapEditFieldAction.entries.map { action ->
                MapEditDraft(
                    latitude = 39.93, longitude = 32.85, observedAt = 1_000,
                    targetType = OsmType.WAY, targetId = 77, editorVersion = 2,
                    presetId = "fixture/unavailable-pharmacy",
                    presetSnapshot = rule("fixture/unavailable-pharmacy", "pharmacy", OsmPresetGeometry.AREA),
                    originalPresetSnapshot = rule("fixture/old-cafe", "cafe", OsmPresetGeometry.AREA), typeChanged = true,
                    nameAction = action, phoneAction = action,
                    name = when (action) {
                        MapEditFieldAction.UNTOUCHED -> "Original name"
                        MapEditFieldAction.SET -> "Changed name"
                        MapEditFieldAction.DELETE -> ""
                    },
                    phone = when (action) {
                        MapEditFieldAction.UNTOUCHED -> "111; 222"
                        MapEditFieldAction.SET -> "+90 312 000 00 00"
                        MapEditFieldAction.DELETE -> ""
                    },
                    street = "Hidden legacy column", houseNumber = "99",
                ).also { f.source.saveMapEdit(it) }
            }
            val sending = MapEditDraft(
                latitude = 39.93, longitude = 32.85, observedAt = 1_000, editorVersion = 2,
                presetId = "fixture/unavailable-pharmacy",
                presetSnapshot = rule("fixture/unavailable-pharmacy", "pharmacy", OsmPresetGeometry.POINT),
                phone = "+90 312 000 00 00", phoneAction = MapEditFieldAction.SET,
                surveyConfirmed = true, status = MapEditStatus.SENDING, stage = MapEditStage.UPLOAD_NODE,
                submittedAt = 2_000, submittedBy = 41, changesetId = 99,
                localAttemptId = "synthetic-local-attempt", localChangesetCreated = true,
            )
            validateMapEdit(sending)
            f.sourceDatabase.mapEditDao().save(sending)
            val expectedDrafts = drafts + sending.copy(status = MapEditStatus.UNKNOWN, localAttemptId = null, localChangesetCreated = false)
            val favoriteIds = listOf("amenity/cafe", "unavailable/alpha", "unavailable/zeta")
            for (id in favoriteIds.reversed()) f.source.setOsmPresetFavorite(id, true)
            f.source.setOsmPresetFavorite("unavailable/zeta", true)
            f.seedTarget()

            val archive = export(f, f.sourceBackup, "v2.zip", expectedDrafts.size)
            val manifest = manifest(archive)
            val records = manifest.getJSONArray("mapEdits")
            for (index in 0 until records.length()) {
                assertFalse(records.getJSONObject(index).has("localAttemptId"))
                assertFalse(records.getJSONObject(index).has("localChangesetCreated"))
            }
            var replacements = 0
            f.targetBackup.importFrom(uri(archive)) { replacements++ }
            assertEquals(1, replacements)
            assertRestored(f.target, expectedDrafts, favoriteIds)
            f.reopenTarget()
            assertRestored(f.target, expectedDrafts, favoriteIds)

            // Assert post-restore behavior from fixed before/after maps, not another codec output.
            val before = mapOf("amenity" to "cafe", "name" to "Original name", "phone" to "111", "contact:phone" to "222",
                "addr:street" to "Keep actual street", "operator" to "Keep operator")
            for (draft in drafts) {
                val actual = requireNotNull(f.target.getMapEdit(draft.id))
                val expected = mutableMapOf("amenity" to "pharmacy", "healthcare" to "pharmacy",
                    "addr:street" to "Keep actual street", "operator" to "Keep operator")
                when (draft.nameAction) {
                    MapEditFieldAction.UNTOUCHED -> expected["name"] = "Original name"
                    MapEditFieldAction.SET -> expected["name"] = "Changed name"
                    MapEditFieldAction.DELETE -> Unit
                }
                when (draft.phoneAction) {
                    MapEditFieldAction.UNTOUCHED -> { expected["phone"] = "111"; expected["contact:phone"] = "222" }
                    MapEditFieldAction.SET -> expected["phone"] = "+90 312 000 00 00"
                    MapEditFieldAction.DELETE -> Unit
                }
                assertEquals(expected, actual.editorTags(before))
            }
        }
    }

    @Test fun streamingExportAndRepositoryRestoreKeepVersionOneNewNodeAndAddressOnlyCorrection() = runBlocking(Dispatchers.IO) {
        isolated { f ->
            val legacy = legacyDrafts()
            legacy.forEach { f.source.saveMapEdit(it) }
            f.seedTarget()
            val archive = export(f, f.sourceBackup, "legacy-streamed-v12.zip", legacy.size)
            f.targetBackup.importFrom(uri(archive))
            assertRestored(f.target, legacy, emptyList())
            assertLegacyPublicIntent(f.target)
            f.reopenTarget()
            assertRestored(f.target, legacy, emptyList())
            assertLegacyPublicIntent(f.target)
        }
    }

    @Test fun independentlyWrittenVersionElevenZipImportsThenReexportsThroughThePagedExporter() = runBlocking(Dispatchers.IO) {
        isolated { f ->
            val archive = File(f.root, "legacy-v11.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(LEGACY_MANIFEST.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            f.seedTarget()
            f.targetBackup.importFrom(uri(archive))
            assertRestored(f.target, legacyDrafts(), emptyList())
            f.reopenTarget()
            assertRestored(f.target, legacyDrafts(), emptyList())
            assertLegacyPublicIntent(f.target)

            val reexported = export(f, f.targetBackup, "legacy-reexported-v12.zip", 2)
            f.sourceBackup.importFrom(uri(reexported))
            assertRestored(f.source, legacyDrafts(), emptyList())
            assertLegacyPublicIntent(f.source)
        }
    }

    private suspend fun isolated(block: suspend (Fixture) -> Unit) {
        val authorizedPackage = InstrumentationRegistry.getArguments().getString("osmLightEditorZipQaPackage")
        check(app.packageName !in setOf("org.iz.navigation", "com.atay.iz") && authorizedPackage == app.packageName) {
            "ZIP restore requires an explicitly allocated separate synthetic QA package (osmLightEditorZipQaPackage)."
        }
        val f = Fixture()
        try { withTimeout(30_000) { block(f) } } finally { f.close() }
    }

    private suspend fun export(f: Fixture, backup: DiaryBackup, fileName: String, count: Int): File {
        val pages = mutableListOf<Int>()
        // prepareExport -> DiaryBackupStages -> actual PagedBackupExporter under repository Room read.
        val prepared = backup.prepareExport(false, BackupExportOptions(pageSize = 1, onPage = { section, size ->
            if (section == "mapEdits") pages += size
        }))
        val archive = File(f.root, fileName)
        try {
            backup.savePrepared(prepared, uri(archive))
            assertEquals(prepared.sizeBytes, archive.length())
            val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(prepared.sha256.lowercase(), digest)
            assertTrue("Fixture must cross map-edit page boundaries", pages.count { it > 0 } > 1)
            assertTrue(pages.all { it in 0..1 })
            val json = manifest(archive)
            assertEquals("org.iz.navigation.backup", json.getString("format"))
            assertEquals(12, json.getInt("version"))
            assertEquals(count, json.getJSONArray("mapEdits").length())
            return archive
        } finally { backup.discardPrepared(prepared) }
    }

    private fun manifest(archive: File): JSONObject = ZipFile(archive).use { zip ->
        assertEquals(listOf("manifest.json"), zip.entries().asSequence().map { it.name }.toList())
        JSONObject(zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private suspend fun assertRestored(repository: DiaryRepository, drafts: List<MapEditDraft>, favoriteIds: List<String>) {
        val actual = repository.snapshot()
        assertEquals(drafts.sortedBy { it.id }, actual.mapEdits.sortedBy { it.id })
        assertEquals(favoriteIds, repository.osmPresetFavorites.first().map { it.presetId })
        assertEquals(favoriteIds, actual.osmPresetFavorites.map { it.presetId })
        assertEquals(favoriteIds.size, actual.osmPresetFavorites.map { it.presetId }.toSet().size)
        assertTrue("Import must replace the target-only sentinel", actual.places.isEmpty())
        assertTrue(actual.journeys.isEmpty())
        for (draft in actual.mapEdits) {
            assertNull(draft.localAttemptId)
            assertFalse(draft.localChangesetCreated)
        }
    }

    private suspend fun assertLegacyPublicIntent(repository: DiaryRepository) {
        val rows = repository.snapshot().mapEdits.associateBy { it.id }
        val node = requireNotNull(rows[NEW_ID])
        val correction = requireNotNull(rows[CORRECTION_ID])
        assertEquals(1, node.editorVersion)
        assertEquals(1, correction.editorVersion)
        assertEquals(mapOf("amenity" to "drinking_water", "name" to "Legacy fountain", "addr:street" to "Legacy street", "addr:housenumber" to "12A"), node.publicTags())
        assertEquals(mapOf("addr:street" to "Corrected street", "addr:housenumber" to "34B"), correction.publicTags())
        assertNull(node.presetId)
        assertNull(correction.presetId)
        assertFalse(node.legacyAddressIntent)
        assertFalse(correction.legacyAddressIntent)
    }

    private fun legacyDrafts() = listOf(
        MapEditDraft(id = NEW_ID, latitude = 39.93, longitude = 32.85, preset = MapPlacePreset.DRINKING_WATER,
            name = "Legacy fountain", street = "Legacy street", houseNumber = "12A", observedAt = 1_000),
        MapEditDraft(id = CORRECTION_ID, latitude = 39.93, longitude = 32.85,
            targetType = OsmType.NODE, targetId = 77, street = "Corrected street", houseNumber = "34B", observedAt = 1_000),
    )

    private fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

    private inner class Fixture {
        private val token = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "camera/osm-light-restore-$token").apply { check(mkdirs()) }
        private val sourceName = "osm-light-source-$token.db"
        private val targetName = "osm-light-target-$token.db"
        private val sourceContext = context("source")
        private val targetContext = context("target")
        val sourceDatabase = database(sourceName)
        private var targetDatabase = database(targetName)
        val source = DiaryRepository(sourceContext, sourceDatabase)
        var target = DiaryRepository(targetContext, targetDatabase)
            private set
        val sourceBackup = DiaryBackup(sourceContext, source)
        var targetBackup = DiaryBackup(targetContext, target)
            private set

        private fun context(label: String) = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "$label/files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "$label/private").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "$label/cache").apply { mkdirs() }
        }

        private fun database(name: String) = Room.databaseBuilder(app, DiaryDatabase::class.java, name).build()

        suspend fun seedTarget() {
            target.saveMapEdit(MapEditDraft(latitude = 1.0, longitude = 2.0, name = "Target-only sentinel", observedAt = 1_000))
            target.setOsmPresetFavorite("unavailable/target-only-sentinel", true)
            targetDatabase.diaryDao().save(Place(id = "target-only-place", name = "Target-only private sentinel"))
        }

        fun reopenTarget() {
            targetDatabase.close()
            targetDatabase = database(targetName)
            target = DiaryRepository(targetContext, targetDatabase)
            targetBackup = DiaryBackup(targetContext, target)
        }

        fun close() {
            sourceDatabase.close()
            targetDatabase.close()
            // Only uniquely generated fixture databases and its own document/scratch root are removed.
            app.deleteDatabase(sourceName)
            app.deleteDatabase(targetName)
            root.deleteRecursively()
        }
    }

    private fun rule(id: String, amenity: String, geometry: OsmPresetGeometry): String {
        val tags = if (amenity == "pharmacy") "\"amenity\":\"pharmacy\",\"healthcare\":\"pharmacy\"" else "\"amenity\":\"cafe\""
        val raw = """{
            "id":"$id","name":"Fixture $amenity","sourceName":"Fixture $amenity","trName":null,
            "geometry":["point","area"],"sourceGeometry":["point","area"],
            "tags":{$tags},"addTags":{$tags},"removeTags":{$tags},
            "aliases":{"tr":[],"en":[]},"terms":{"tr":[],"en":[]},
            "searchable":true,"selectable":true,"matchScore":1.0,"locationSet":null,
            "requiresLocationValidation":false,"normalizedSearch":"fixture $amenity"
        }""".trimIndent()
        return " \n" + OsmPresetRuleSnapshot("a".repeat(40), "b".repeat(64), id, geometry, raw).toJson() + "\n "
    }

    private companion object {
        const val NEW_ID = "10000000-0000-4000-8000-000000000021"
        const val CORRECTION_ID = "10000000-0000-4000-8000-000000000022"
        val LEGACY_MANIFEST = """{
            "format":"org.iz.navigation.backup","version":11,"exportedAt":2000,
            "journeys":[],"points":[],"places":[],"visits":[],"photos":[],"drafts":[],
            "contributions":[],"healthSamples":[],"watchHealthSessions":[],"watchHealthSamples":[],
            "savedPlans":[],"importedTracks":[],"collections":[],"memberships":[],"destinationShortcuts":[],"leanSessions":[],
            "mapEdits":[{
                "id":"$NEW_ID","placeId":null,"latitude":39.93,"longitude":32.85,"preset":"DRINKING_WATER",
                "name":"Legacy fountain","street":"Legacy street","houseNumber":"12A","observedAt":1000,
                "surveyConfirmed":false,"status":"DRAFT","stage":"NONE","submittedAt":null,"submittedBy":null,
                "changesetId":null,"remoteNodeId":null,"remoteNodeVersion":null,"changesetClosed":false,"error":null,
                "targetType":null,"targetId":null,"reviewedVersion":null,"reviewedFingerprint":null,"appliedVersion":null,"surveyPayload":null
            },{
                "id":"$CORRECTION_ID","placeId":null,"latitude":39.93,"longitude":32.85,"preset":"CAFE",
                "name":"","street":"Corrected street","houseNumber":"34B","observedAt":1000,
                "surveyConfirmed":false,"status":"DRAFT","stage":"NONE","submittedAt":null,"submittedBy":null,
                "changesetId":null,"remoteNodeId":null,"remoteNodeVersion":null,"changesetClosed":false,"error":null,
                "targetType":"NODE","targetId":77,"reviewedVersion":null,"reviewedFingerprint":null,"appliedVersion":null,"surveyPayload":null
            }]
        }""".trimIndent()
    }
}
