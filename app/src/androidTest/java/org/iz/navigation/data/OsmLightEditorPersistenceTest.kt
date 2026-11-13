package org.iz.navigation.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, uniquely named databases only; never opens DiaryDatabase.get or replaces the app diary. */
@RunWith(AndroidJUnit4::class)
class OsmLightEditorPersistenceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val name = "osm-light-editor-${UUID.randomUUID()}.db"
    private val root = File(app.cacheDir, name.removeSuffix(".db"))
    private val isolatedContext = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
    private var database: DiaryDatabase? = null
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DiaryDatabase::class.java)

    @After fun cleanup() {
        database?.close()
        app.deleteDatabase(name)
        // This root and database name are generated and owned by this test instance.
        root.deleteRecursively()
    }

    @Test fun migrationTenToElevenKeepsLegacyRowsAndAddsSafeEditorDefaultsAndPersistentFavorites() = runBlocking {
        val legacy = MapEditDraft(
            id = "10000000-0000-4000-8000-000000000010", latitude = 39.93, longitude = 32.85,
            preset = MapPlacePreset.DRINKING_WATER, name = "Legacy fountain", street = "Keep old street",
            houseNumber = "12A", observedAt = 1_000,
        )
        // Only the already checked-in v10 schema fixture is needed. The Room open below validates
        // against compiled v11 entities, not a not-yet-generated exported v11 schema JSON.
        helper.createDatabase(name, 10).use { old ->
            old.execSQL(
                "INSERT INTO map_edit_drafts (id,latitude,longitude,preset,name,street,houseNumber,observedAt," +
                    "surveyConfirmed,status,stage,changesetClosed,localChangesetCreated) " +
                    "VALUES (?,?,?,?,?,?,?,?,0,'DRAFT','NONE',0,0)",
                arrayOf(legacy.id, legacy.latitude, legacy.longitude, "DRINKING_WATER", legacy.name, legacy.street, legacy.houseNumber, legacy.observedAt),
            )
            old.execSQL("INSERT INTO places (id,name,latitude,longitude,googlePlaceId,osmType,osmId,source,sortOrder) " +
                "VALUES ('private-place','Keep private place',39.93,32.85,NULL,NULL,NULL,'LEGACY',0)")
            old.execSQL("INSERT INTO visits (id,placeId,journeyId,visitedAt,note,rating) " +
                "VALUES ('private-visit','private-place',NULL,1000,'Keep private note',4)")
        }
        val db = open()
        val repository = DiaryRepository(isolatedContext, db)
        assertEquals(11, db.openHelper.readableDatabase.version)
        val migrated = requireNotNull(repository.getMapEdit(legacy.id))
        assertEquals(legacy, migrated)
        assertEquals(1, migrated.editorVersion)
        assertNull(migrated.presetId)
        assertNull(migrated.presetSnapshot)
        assertNull(migrated.originalPresetSnapshot)
        assertEquals("", migrated.phone)
        assertEquals(MapEditFieldAction.UNTOUCHED, migrated.nameAction)
        assertEquals(MapEditFieldAction.UNTOUCHED, migrated.phoneAction)
        assertFalse(migrated.typeChanged)
        assertFalse(migrated.legacyAddressIntent)
        assertEquals("Keep private place", db.diaryDao().allPlaces().single().name)
        assertEquals("Keep private note", db.diaryDao().allVisits().single().note)
        assertTrue(favoriteIds(repository).isEmpty())
        repository.setOsmPresetFavorite("unavailable/retained-after-migration", true)

        val reopened = reopen()
        val reopenedRepository = DiaryRepository(isolatedContext, reopened)
        assertEquals(legacy, reopenedRepository.getMapEdit(legacy.id))
        assertEquals(listOf("unavailable/retained-after-migration"), favoriteIds(reopenedRepository))
        assertEquals("Keep private note", reopened.diaryDao().allVisits().single().note)
    }

    @Test fun phoneAndNameActionsAndExactUnavailableCatalogSnapshotsSurviveDatabaseReopen() = runBlocking {
        val repository = DiaryRepository(isolatedContext, open())
        val next = rule("fixture/unavailable-pharmacy", "pharmacy")
        val old = rule("fixture/old-cafe", "cafe")
        val drafts = MapEditFieldAction.entries.map { action ->
            MapEditDraft(
                latitude = 39.93, longitude = 32.85, observedAt = 1_000, targetType = OsmType.WAY, targetId = 77,
                editorVersion = 2, presetId = "fixture/unavailable-pharmacy", presetSnapshot = next,
                originalPresetSnapshot = old, typeChanged = true, nameAction = action, phoneAction = action,
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
                street = "Keep legacy address", houseNumber = "12A",
            )
        } + MapEditDraft(
            latitude = 39.93, longitude = 32.85, observedAt = 1_000, targetType = OsmType.WAY, targetId = 77,
            editorVersion = 2, presetId = "fixture/old-cafe", presetSnapshot = old, originalPresetSnapshot = old,
            legacyAddressIntent = true, street = "Retained address edit", houseNumber = "12A",
        )
        drafts.forEach { repository.saveMapEdit(it) }
        val reopened = DiaryRepository(isolatedContext, reopen())
        for (expected in drafts) {
            val actual = requireNotNull(reopened.getMapEdit(expected.id))
            assertEquals("action=${expected.phoneAction}", expected, actual)
            assertEquals(expected.presetSnapshot, actual.presetSnapshot)
            assertEquals(expected.originalPresetSnapshot, actual.originalPresetSnapshot)
        }
        assertEquals(drafts.map { it.id }.toSet(), reopened.mapEdits.first().map { it.id }.toSet())
    }

    @Test fun savingAndReopeningAnUnselectedNewDraftNeverSelectsTheLegacyCafeDefault() = runBlocking {
        val draft = newMapEditDraft(39.93, 32.85).copy(observedAt = 1_000)
        DiaryRepository(isolatedContext, open()).saveMapEdit(draft)
        val restored = requireNotNull(DiaryRepository(isolatedContext, reopen()).getMapEdit(draft.id))
        assertEquals(draft, restored)
        assertEquals(2, restored.editorVersion)
        assertNull(restored.presetId)
        assertNull(restored.presetSnapshot)
        assertEquals(MapEditFieldAction.UNTOUCHED, restored.nameAction)
        assertEquals(MapEditFieldAction.UNTOUCHED, restored.phoneAction)
        validateMapEdit(restored)
        assertThrows(IllegalArgumentException::class.java) { validateMapEditForPublish(restored.copy(surveyConfirmed = true)) }
    }

    @Test fun repeatedFavoriteTogglesAreUniqueSortedAndKeepUnknownIdsAfterReopen() = runBlocking {
        val repository = DiaryRepository(isolatedContext, open())
        repository.setOsmPresetFavorite("unavailable/zeta", true)
        repository.setOsmPresetFavorite("unavailable/zeta", true)
        repository.setOsmPresetFavorite("amenity/cafe", true)
        repository.setOsmPresetFavorite("unavailable/alpha", true)
        val expected = listOf("amenity/cafe", "unavailable/alpha", "unavailable/zeta")
        assertEquals(expected, favoriteIds(repository))
        assertEquals(expected, repository.snapshot().osmPresetFavorites.map { it.presetId })

        val reopened = DiaryRepository(isolatedContext, reopen())
        assertEquals(expected, favoriteIds(reopened))
        reopened.setOsmPresetFavorite("amenity/cafe", false)
        reopened.setOsmPresetFavorite("amenity/cafe", false)
        val unknownOnly = listOf("unavailable/alpha", "unavailable/zeta")
        assertEquals(unknownOnly, favoriteIds(reopened))
        val again = DiaryRepository(isolatedContext, reopen())
        val actual = favoriteIds(again)
        assertEquals(unknownOnly, actual)
        assertEquals(actual.size, actual.toSet().size)
        assertTrue(again.mapEdits.first().isEmpty())
    }

    @Test fun confirmedUnnamedTreeWithLegacyCafeCarryoverCreatesFrozenLabelPlaceAndSurvivesReopen() = runBlocking {
        val catalog = OsmPresetCatalog.load(app.assets.open(OsmPresetCatalog.BUNDLED_ASSET))
        val tree = catalog.snapshot("natural/tree", OsmPresetGeometry.POINT)
        val frozenName = "Frozen synthetic tree"
        val frozen = tree.copy(ruleJson = JSONObject(tree.ruleJson).put("name", frozenName).toString())
        val draft = newMapEditDraft(39.9301234, 32.8505678).selectPreset(frozen)
            .copy(observedAt = 1_000, surveyConfirmed = true)
        assertEquals(MapPlacePreset.CAFE, draft.preset) // Legacy enum column must not label this v2 object.
        assertEquals("", draft.name)
        assertEquals(mapOf("natural" to "tree"), draft.publicTags())
        assertNotEquals(catalog.resolve("natural/tree")!!.name, frozenName)
        val repository = DiaryRepository(isolatedContext, open())
        repository.saveMapEdit(draft)
        val claim = requireNotNull(repository.beginMapEditSend(draft.id, 41, 2_000))
        assertEquals(MapEditStage.CREATE_CHANGESET, claim.stage)
        assertNotNull(claim.localAttemptId)
        assertFalse(claim.localChangesetCreated)
        val receipt = requireNotNull(repository.recordLocalMapEditChangeset(claim, 700))
        assertEquals(MapEditStage.UPLOAD_NODE, receipt.stage)
        assertTrue(receipt.localChangesetCreated)
        assertEquals(claim.localAttemptId, receipt.localAttemptId)

        // Reopen before the simulated remote success: the real Room claim/receipt must be durable.
        val resumed = DiaryRepository(isolatedContext, reopen())
        assertEquals(receipt, resumed.getMapEdit(draft.id))
        val sent = receipt.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET,
            remoteNodeId = 770031, remoteNodeVersion = 1)
        assertTrue(resumed.compareAndSetMapEdit(receipt, sent))
        assertFalse(resumed.compareAndSetMapEdit(receipt, sent))
        val place = resumed.snapshot().places.single()
        assertEquals(frozenName, place.name) // Independent literal, never displayLabel() as the oracle.
        assertNotEquals(MapPlacePreset.CAFE.label, place.name)
        assertEquals(OsmType.NODE, place.osmType)
        assertEquals(770031L, place.osmId)
        assertEquals(draft.latitude, requireNotNull(place.latitude), 0.0)
        assertEquals(draft.longitude, requireNotNull(place.longitude), 0.0)
        assertEquals(PlaceSource.OSM, place.source)
        assertEquals("", sent.name)
        assertEquals(mapOf("natural" to "tree"), sent.publicTags())

        val reopened = DiaryRepository(isolatedContext, reopen())
        val restoredDraft = requireNotNull(reopened.getMapEdit(draft.id))
        assertEquals(sent, restoredDraft)
        assertEquals(draft.presetSnapshot, restoredDraft.presetSnapshot)
        assertEquals(MapPlacePreset.CAFE, restoredDraft.preset)
        assertEquals("", restoredDraft.name)
        assertEquals(MapEditFieldAction.UNTOUCHED, restoredDraft.nameAction)
        assertEquals(mapOf("natural" to "tree"), restoredDraft.publicTags())
        assertEquals(listOf(place), reopened.snapshot().places)
    }

    private fun open(): DiaryDatabase = Room.databaseBuilder(app, DiaryDatabase::class.java, name)
        .addMigrations(DiaryDatabase.MIGRATION_10_11)
        .build().also { database = it }

    private fun reopen(): DiaryDatabase {
        database?.close()
        return open()
    }

    private suspend fun favoriteIds(repository: DiaryRepository): List<String> = withTimeout(5_000) {
        repository.osmPresetFavorites.first().map { it.presetId }
    }

    private fun rule(id: String, amenity: String): String {
        val tags = if (amenity == "pharmacy") "\"amenity\":\"pharmacy\",\"healthcare\":\"pharmacy\"" else "\"amenity\":\"cafe\""
        val rule = """{
            "id":"$id","name":"Fixture $amenity","sourceName":"Fixture $amenity","trName":null,
            "geometry":["point","area"],"sourceGeometry":["point","area"],
            "tags":{$tags},"addTags":{$tags},"removeTags":{$tags},
            "aliases":{"tr":[],"en":[]},"terms":{"tr":[],"en":[]},
            "searchable":true,"selectable":true,"matchScore":1.0,"locationSet":null,
            "requiresLocationValidation":false,"normalizedSearch":"fixture $amenity"
        }""".trimIndent()
        return " \n" + OsmPresetRuleSnapshot("a".repeat(40), "b".repeat(64), id, OsmPresetGeometry.AREA, rule).toJson() + "\n "
    }
}
