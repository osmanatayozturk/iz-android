package org.iz.navigation.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaceOrderMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DiaryDatabase::class.java)
    @Test fun allLegacySchemasKeepPlacesAndVisitsAndInitializeTheVisibleOrder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..5) {
            val name = "place-order-migration-$version.db"
            try {
                helper.createDatabase(name, version).apply {
                    execSQL("INSERT INTO places (id,name,latitude,longitude,googlePlaceId) VALUES ('c','Alpha',NULL,NULL,'legacy-c'),('b','Beta',41.0,29.0,NULL),('a','alpha',NULL,NULL,NULL)")
                    execSQL("INSERT INTO visits (id,placeId,journeyId,visitedAt,note,rating) VALUES ('old','c',NULL,50,'keep',NULL),('new','b',NULL,100,'new',NULL),('same','a',NULL,50,'same',NULL)")
                    close()
                }
                helper.runMigrationsAndValidate(name, 6, true, DiaryDatabase.MIGRATION_1_2, DiaryDatabase.MIGRATION_2_3,
                    DiaryDatabase.MIGRATION_3_4, DiaryDatabase.MIGRATION_4_5, DiaryDatabase.MIGRATION_5_6).use { db ->
                    val ids = mutableListOf<String>()
                    val ranks = mutableListOf<Long>()
                    db.query("SELECT id,sortOrder FROM places ORDER BY sortOrder,name COLLATE NOCASE,id").use { cursor ->
                        while (cursor.moveToNext()) { ids += cursor.getString(0); ranks += cursor.getLong(1) }
                    }
                    assertEquals(listOf("b", "a", "c"), ids)
                    assertEquals(listOf(0L, 1L, 2L), ranks)
                    db.query("SELECT note FROM visits WHERE id='old'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("keep", cursor.getString(0)) }
                    db.query("SELECT googlePlaceId FROM places WHERE id='c'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("legacy-c", cursor.getString(0)) }
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
