package org.iz.navigation.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.*
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmSurveyDraftEntryTest {
    private val ref = OsmRef(OsmType.NODE, 700)
    private fun target() = OsmCorrectionElement(ref, 13, 99, 30, "41.0", "29.0", emptyList(),
        mapOf("amenity" to "cafe", "name" to "Sentetik kafe", "opening_hours" to "24/7"))
    private fun place(id: String) = Place(id = id, name = "Sentetik kafe", latitude = 41.0,
        longitude = 29.0, osmType = ref.type, osmId = ref.id)

    @Test fun separateEntryRoutesReuseRepositoryDraftIdentityRawPayloadAndObservationDate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val repository = DiaryRepository(context, database)
            val payload = SurveyObservation(12, "2024-01-02", hours = SurveyFieldObservation(
                action = SurveyAction.EDIT, value = "Mo 99:99-", before = "Mo-Fr 09:00-18:00")).encode()
            val saved = MapEditDraft(latitude = 41.0, longitude = 29.0, targetType = ref.type, targetId = ref.id,
                observedAt = 1_704_153_600_000L, surveyPayload = payload)
            repository.saveMapEdit(saved)
            var reads = 0
            for (entry in listOf("marker", "list", "saved-place-detail")) {
                val result = openSurveyDraftEntry(repository, place(entry), UUID.randomUUID().toString(), 41,
                    isCurrent = { true }, readTarget = { reads++; target() })
                assertEquals(saved.id, result.draft.id)
                assertEquals(payload, result.draft.surveyPayload)
                assertEquals(saved.observedAt, result.draft.observedAt)
                assertEquals("2024-01-02", result.draft.surveyObservation()?.observedOn)
                assertEquals(12L, result.draft.surveyObservation()?.baseVersion)
                assertEquals(13L, result.target.version)
            }
            assertEquals(3, reads)
            assertEquals(1, repository.mapEdits.first().size)
        } finally { database.close() }
    }

    @Test fun staleOwnerAfterFreshReadCannotCreateANewDraft() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val repository = DiaryRepository(context, database)
            var current = true
            try {
                openSurveyDraftEntry(repository, place("marker"), UUID.randomUUID().toString(), 41,
                    isCurrent = { current }, readTarget = { current = false; target() },
                    findExisting = { _, _ -> error("Stale entry must not select or write a draft") })
                fail("Stale entry accepted")
            } catch (_: CancellationException) { }
            assertTrue(repository.mapEdits.first().isEmpty())
        } finally { database.close() }
    }

    @Test fun selectionUsesLoadedRowsAndDeterministicObservationThenIdOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java).build()
        try {
            val repository = DiaryRepository(context, database)
            val payload = SurveyObservation(12, "2024-01-02").encode()
            fun row(n: Int, time: Long = 2000) = MapEditDraft(
                id = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}",
                latitude = 41.0, longitude = 29.0, targetType = ref.type, targetId = ref.id,
                observedAt = time, surveyPayload = payload)
            val visible = row(3).copy(status = MapEditStatus.FAILED, submittedBy = 41)
            val rows = listOf(row(1, 1000), row(2), visible,
                row(4, 9999).copy(status = MapEditStatus.SENT),
                row(5, 9999).copy(status = MapEditStatus.UNKNOWN),
                row(6, 9999).copy(status = MapEditStatus.SENDING),
                row(7, 9999).copy(status = MapEditStatus.FAILED, submittedBy = 42),
                row(8, 9999).copy(surveyPayload = null), row(9, 9999).copy(targetId = 701))
            for (draft in rows) database.mapEditDao().save(draft)
            assertEquals(visible.id, findEditableSurveyDraft(repository, ref, 41)?.id)
            assertEquals(row(2).id, findEditableSurveyDraft(repository, ref, null)?.id)
            assertEquals(payload, findEditableSurveyDraft(repository, ref, 41)?.surveyPayload)
        } finally { database.close() }
    }
}
