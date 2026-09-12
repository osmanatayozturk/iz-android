package org.iz.navigation.data

import org.junit.Assert.*
import org.junit.Test

class PlaceOrderRulesTest {
    private val a = Place(id = "a", name = "Alpha", sortOrder = 10)
    private val b = Place(id = "b", name = "Beta", sortOrder = 20)
    private val c = Place(id = "c", name = "Alpha", sortOrder = 30)
    @Test fun legacyOrderUsesImportedVisitsThenAsciiCaseFoldedNameAndId() {
        val places = listOf(c, a, b)
        val result = PlaceOrderRules.legacy(places, listOf(Visit(placeId = "b", visitedAt = 5), Visit(placeId = "b", visitedAt = 10)))
        assertEquals(listOf("b", "a", "c"), PlaceOrderRules.ordered(result).map { it.id })
        assertEquals(listOf(0L, 1L, 2L), PlaceOrderRules.ordered(result).map { it.sortOrder })
    }
    @Test fun duplicateRanksNormalizeDeterministicallyButUniqueRanksRemainExact() {
        assertEquals(listOf(c, a, b), PlaceOrderRules.normalizeDuplicates(listOf(c, a, b)))
        val normalized = PlaceOrderRules.normalizeDuplicates(listOf(c.copy(sortOrder = 0), b.copy(sortOrder = 0), a.copy(sortOrder = 0)))
        assertEquals(listOf("a", "c", "b"), PlaceOrderRules.ordered(normalized).map { it.id })
        assertEquals(3, normalized.map { it.sortOrder }.toSet().size)
    }
    @Test fun reorderCompactsRanksWithoutChangingPlaceMetadata() {
        val result = PlaceOrderRules.reorder(listOf(c, a, b), listOf("a", "b", "c"), listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), result.map { it.id })
        assertEquals(listOf(0L, 1L, 2L), result.map { it.sortOrder })
        assertEquals(c.name, result.first().name)
    }
    @Test fun concurrentSameIdReorderAndAddedDeletedPlacesAreRejected() {
        assertThrows(IllegalStateException::class.java) { PlaceOrderRules.reorder(listOf(a, b, c), listOf("b", "a", "c"), listOf("c", "a", "b")) }
        assertThrows(IllegalStateException::class.java) { PlaceOrderRules.reorder(listOf(a, b, c), listOf("a", "b"), listOf("b", "a")) }
        assertThrows(IllegalArgumentException::class.java) { PlaceOrderRules.reorder(listOf(a, b, c), listOf("a", "b", "c"), listOf("a", "a", "c")) }
    }
    @Test fun invalidRankNeverReachesBackupOrRestore() {
        for (rank in listOf(-1L, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            DiaryRules.validate(DiarySnapshot(places = listOf(a.copy(sortOrder = rank))))
        }
    }
}
