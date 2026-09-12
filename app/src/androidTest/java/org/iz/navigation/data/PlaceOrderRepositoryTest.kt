package org.iz.navigation.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PlaceOrderRepositoryTest {
    @Test fun appendReorderStaleEditVisitAndDeleteKeepPersistentRelativeOrder() = runBlocking {
        val repository = DiaryRepository(ApplicationProvider.getApplicationContext<Context>())
        val places = (1..3).map { Place(id = UUID.randomUUID().toString(), name = "Order test $it") }
        val ids = places.map { it.id }
        try {
            places.forEach { repository.savePlace(it) }
            val original = repository.places.first().map { it.id }
            assertEquals(ids, original.takeLast(3))
            val desired = original.dropLast(3) + ids.reversed()
            repository.reorderPlaces(original, desired)
            repository.savePlace(places.first().copy(name = "Renamed from stale editor"))
            repository.saveVisit(Visit(placeId = ids[1], visitedAt = 1000))
            assertEquals(desired, repository.places.first().map { it.id })
            try { repository.reorderPlaces(original, original); fail("A stale original order must not overwrite the new order") }
            catch (_: IllegalStateException) { }
            assertEquals(desired, repository.places.first().map { it.id })
            repository.deletePlace(ids[1])
            assertEquals(desired.filterNot { it == ids[1] }, repository.places.first().map { it.id })
        } finally { ids.forEach { repository.deletePlace(it) } }
    }
}
