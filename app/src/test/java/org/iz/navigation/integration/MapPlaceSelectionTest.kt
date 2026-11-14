package org.iz.navigation.integration

import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.junit.Assert.*
import org.junit.Test

class MapPlaceSelectionTest {
    private fun place(id: Long, latitude: Double, type: OsmType = OsmType.NODE) =
        MapPlaceCandidate(SelectedOsmPlace("Place $id", latitude, 0.0, OsmRef(type, id)), "Test type")

    @Test fun deduplicatesActualIdentityThenStableDistanceSortsBeforeCap() {
        val far = place(1, .0009)
        val near = place(2, .0001)
        val sameCenterBuilding = place(2, .0001, OsmType.WAY)
        val ranked = rankMapCandidates(listOf(far, near, near.copy(typeLabel = "Duplicate"), sameCenterBuilding), 0.0, 0.0)
        assertEquals(listOf(near.place.osmRef, sameCenterBuilding.place.osmRef, far.place.osmRef), ranked.candidates.map { it.place.osmRef })
        assertEquals("Test type", ranked.candidates.first().typeLabel)
        assertFalse(ranked.capped)
        val many = rankMapCandidates((1L..55L).map { place(it, (56 - it) / 100_000.0) }, 0.0, 0.0)
        assertEquals(50, many.candidates.size)
        assertEquals(55L, many.candidates.first().place.osmRef!!.id)
        assertTrue(many.capped)
        assertTrue(many.candidates.first().distanceMeters < many.candidates.last().distanceMeters)
    }

    @Test fun invalidObjectsAreRemovedButSameNameAndCoordinatesDoNotMergeIdentities() {
        val first = place(1, 0.0)
        val second = place(2, 0.0).copy(place = first.place.copy(osmRef = OsmRef(OsmType.NODE, 2)))
        val result = rankMapCandidates(listOf(first, second, first.copy(place = first.place.copy(osmRef = null)), place(3, Double.NaN)), 0.0, 0.0)
        assertEquals(2, result.candidates.size)
        assertEquals(listOf(1L, 2L), result.candidates.map { it.place.osmRef!!.id })
    }

    @Test fun collisionFiltersActualIdsWithoutFirstHitSelectionOrChangingMapNumbers() {
        val all = rankMapCandidates(listOf(place(1, .0001), place(2, .0002), place(3, .0003)), 0.0, 0.0).candidates
        val hits = mapCandidateHits(all, listOf(all[2].place, all[0].place, all[2].place))
        assertEquals(listOf(1L, 3L), hits.map { it.place.osmRef!!.id })
        assertTrue(mapCandidateHits(all, listOf(all[0].place.copy(latitude = 10.0))).isEmpty())
        assertEquals(listOf(1, 3), hits.map { all.indexOf(it) + 1 })
    }
}
