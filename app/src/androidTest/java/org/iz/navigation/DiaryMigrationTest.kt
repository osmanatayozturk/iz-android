package org.iz.navigation.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiaryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "steps-migration-test.db"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DiaryDatabase::class.java)

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun migratingVersionThreePreservesDiaryAndCreatesCascadingHealthTables() = runBlocking {
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO journeys VALUES ('trip', 'Keep run', 'WALK', 'CONFIRMED', 1000, 5000, NULL, 'Private note', 0, 42)")
            execSQL("INSERT INTO contribution_drafts VALUES ('draft', NULL, 41.0, 29.0, 1000, 'OTHER', 'Public observation', 'DRAFT', NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, DiaryDatabase.MIGRATION_3_4, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).close()
        val db = Room.databaseBuilder(context, DiaryDatabase::class.java, name).addMigrations(DiaryDatabase.MIGRATION_3_4, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).build()
        try {
            val dao = db.diaryDao()
            assertEquals(42L, dao.journey("trip")?.stepCount)
            assertEquals("Public observation", dao.allContributions().single().text)
            assertTrue(dao.allHealthSamples().isEmpty())
            val sample = ImportedHealthSample("source", HealthRules.SAMSUNG_HEALTH_PACKAGE, 1,
                metric = HealthMetric.HEART_RATE_BPM, startAt = 2000, endAt = 2000, value = 75.0).forJourney("trip")
            dao.saveHealthSamples(listOf(sample))
            dao.saveHealthSyncs(listOf(JourneyHealthSync("trip", HealthMetric.HEART_RATE_BPM, 6000)))
            assertEquals(listOf(sample), dao.allHealthSamples())
            dao.deleteJourney("trip")
            assertTrue(dao.allHealthSamples().isEmpty())
        } finally { db.close() }
    }

    @Test fun migratingVersionOnePreservesDiaryRelationshipsAndDoesNotInventSteps() = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO journeys VALUES ('walk', 'Existing walk', 'WALK', 'CONFIRMED', 100, 500, NULL, 'Keep my note', 0)")
            execSQL("INSERT INTO track_points VALUES ('point', 'walk', 41.0, 29.0, 200, 5.0, NULL, NULL, 0)")
            execSQL("INSERT INTO places VALUES ('place', 'Existing place', 41.0, 29.0, NULL)")
            execSQL("INSERT INTO visits VALUES ('visit', 'place', 'walk', 300, 'Visit note', 4)")
            execSQL("INSERT INTO photos VALUES ('photo', 'photos/existing.jpg', 'walk', 'visit', 300, NULL, NULL, 'Photo note')")
            execSQL("INSERT INTO share_drafts VALUES ('draft', 'visit', 'Saved review', 4, 'photo', NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, DiaryDatabase.MIGRATION_1_2, DiaryDatabase.MIGRATION_2_3, DiaryDatabase.MIGRATION_3_4, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).close()
        val db = Room.databaseBuilder(context, DiaryDatabase::class.java, name)
            .addMigrations(DiaryDatabase.MIGRATION_1_2, DiaryDatabase.MIGRATION_2_3, DiaryDatabase.MIGRATION_3_4, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).build()
        try {
            val dao = db.diaryDao()
            val old = dao.journey("walk")!!
            assertEquals("Keep my note", old.note)
            assertEquals(500L, old.endedAt)
            assertNull(old.stepCount)
            assertEquals("walk", dao.allPoints().single().journeyId)
            assertEquals("Existing place", dao.allPlaces().single().name)
            assertEquals(PlaceSource.LEGACY, dao.allPlaces().single().source)
            assertNull(dao.allPlaces().single().osmType)
            assertNull(dao.allPlaces().single().osmId)
            assertTrue(dao.allContributions().isEmpty())
            assertEquals("walk", dao.allVisits().single().journeyId)
            assertEquals("visit", dao.allPhotos().single().visitId)
            assertEquals(listOf("photo"), dao.allDrafts().single().photoIds)
            dao.save(old.copy(stepCount = 27))
            dao.save(Journey(id = "passenger", transport = Transport.PASSENGER, startedAt = 600, endedAt = 700))
            assertEquals(27L, dao.journey("walk")?.stepCount)
            assertEquals(Transport.PASSENGER, dao.journey("passenger")?.transport)
        } finally { db.close() }
    }

    @Test fun migratingVersionTwoRetainsGoogleIdentifiersLegacyReviewAndWalkingSteps() = runBlocking {
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO journeys VALUES ('walk', 'Saved walk', 'WALK', 'CONFIRMED', 100, 500, NULL, 'Private note', 0, 321)")
            execSQL("INSERT INTO track_points VALUES ('point', 'walk', 41.0, 29.0, 200, 5.0, NULL, 12.5, 1)")
            execSQL("INSERT INTO places VALUES ('place', 'Legacy place', 41.0, 29.0, 'google-legacy-id')")
            execSQL("INSERT INTO visits VALUES ('visit', 'place', 'walk', 300, 'Visit note', 4)")
            execSQL("INSERT INTO photos VALUES ('photo', 'photos/existing.jpg', 'walk', 'visit', 300, NULL, NULL, 'Photo note')")
            execSQL("INSERT INTO share_drafts VALUES ('draft', 'visit', 'Saved review', 4, 'photo', 400)")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, DiaryDatabase.MIGRATION_2_3, DiaryDatabase.MIGRATION_3_4, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).close()
        val db = Room.databaseBuilder(context, DiaryDatabase::class.java, name).addMigrations(DiaryDatabase.MIGRATION_2_3, DiaryDatabase.MIGRATION_3_4, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).build()
        try {
            val dao = db.diaryDao()
            assertEquals(321L, dao.journey("walk")?.stepCount)
            assertEquals("google-legacy-id", dao.allPlaces().single().googlePlaceId)
            assertEquals(PlaceSource.LEGACY, dao.allPlaces().single().source)
            assertEquals("Saved review", dao.allDrafts().single().text)
            assertEquals(400L, dao.allDrafts().single().markedSharedAt)
            assertEquals("photos/existing.jpg", dao.allPhotos().single().relativePath)
            assertTrue(dao.allPoints().single().breakBefore)
            assertTrue(dao.allContributions().isEmpty())
        } finally { db.close() }
    }

    @Test fun migratingVersionFourPreservesOsmDiaryAndHealthAndStartsNewTablesEmpty() = runBlocking {
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO journeys VALUES ('trip', 'Keep run', 'RUN', 'CONFIRMED', 1000, 5000, NULL, 'Private note', 0, 42)")
            execSQL("INSERT INTO places VALUES ('place', 'Existing OSM', 41.0, 29.0, NULL, 'NODE', 123, 'OSM')")
            execSQL("INSERT INTO visits VALUES ('visit', 'place', 'trip', 2000, 'Private visit', NULL)")
            execSQL("INSERT INTO contribution_drafts VALUES ('draft', 'place', 41.0, 29.0, 1000, 'OTHER', 'Public observation', 'DRAFT', NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
            execSQL("INSERT INTO journey_health_samples VALUES ('trip', 'source', 'com.sec.android.app.shealth', 1, 'Samsung', 'Watch8 Classic', 'HEART_RATE_BPM', 2000, 2000, 84.0)")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).close()
        val db = Room.databaseBuilder(context, DiaryDatabase::class.java, name).addMigrations(DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7).build()
        try {
            assertEquals("Private note", db.diaryDao().journey("trip")?.note)
            assertEquals(42L, db.diaryDao().journey("trip")?.stepCount)
            assertEquals(123L, db.diaryDao().allPlaces().single().osmId)
            assertEquals("Private visit", db.diaryDao().allVisits().single().note)
            assertEquals("Public observation", db.diaryDao().allContributions().single().text)
            assertEquals(84.0, db.diaryDao().allHealthSamples().single().value, 0.0)
            assertTrue(db.mapEditDao().all().isEmpty())
            assertTrue(db.watchHealthDao().allSessions().isEmpty())
            assertTrue(db.watchHealthDao().allSamples().isEmpty())
        } finally { db.close() }
    }
}
