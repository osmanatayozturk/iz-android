package org.iz.navigation.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelLibraryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DiaryDatabase::class.java)
    private val migrations = arrayOf(DiaryDatabase.MIGRATION_1_2, DiaryDatabase.MIGRATION_2_3, DiaryDatabase.MIGRATION_3_4,
        DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6, DiaryDatabase.MIGRATION_6_7)
    @After fun cleanup() { names.forEach { context.deleteDatabase(it) } }

    @Test fun everyLegacySchemaReachesSevenWithoutLosingDiaryRelationships() = runBlocking {
        for (version in 1..6) {
            val name = "library-migration-${UUID.randomUUID()}.db".also { names += it }
            helper.createDatabase(name, version).use { db ->
                db.execSQL("INSERT INTO journeys (id,title,transport,status,startedAt,endedAt,expiresAt,note,interrupted) VALUES ('trip','Keep','WALK','CONFIRMED',1000,5000,NULL,'Note',0)")
                db.execSQL("INSERT INTO track_points VALUES ('point','trip',10.0,20.0,2000,5.0,NULL,NULL,0)")
                db.execSQL("INSERT INTO places (id,name,latitude,longitude,googlePlaceId) VALUES ('place','Place',10.0,20.0,NULL)")
                db.execSQL("INSERT INTO visits VALUES ('visit','place','trip',3000,'Visit note',4)")
                db.execSQL("INSERT INTO photos VALUES ('photo','photos/synthetic.jpg','trip','visit',3000,NULL,NULL,'Caption')")
                db.execSQL("INSERT INTO share_drafts VALUES ('draft','visit','Review',4,'photo',NULL)")
            }
            helper.runMigrationsAndValidate(name, 7, true, *migrations).close()
            val db = Room.databaseBuilder(context, DiaryDatabase::class.java, name).addMigrations(*migrations).build()
            try {
                val diary = db.diaryDao()
                assertEquals("Note", diary.journey("trip")!!.note)
                assertEquals("trip", diary.allPoints().single().journeyId)
                assertEquals("place", diary.allVisits().single().placeId)
                assertEquals("visit", diary.allPhotos().single().visitId)
                assertEquals(listOf("photo"), diary.allDrafts().single().photoIds)
                val library = TravelLibraryRepository(db)
                assertTrue(library.savedPlans.first().isEmpty()); assertTrue(library.importedTracks.first().isEmpty())
                assertTrue(library.collections.first().isEmpty()); assertTrue(library.memberships.first().isEmpty())
                library.saveCollection(JourneyCollection(id = "collection", name = "Trip"))
                library.setCollectionJourneys("collection", listOf("trip"))
                assertEquals("trip", library.memberships.first().single().journeyId)
            } finally { db.close() }
        }
    }
}
