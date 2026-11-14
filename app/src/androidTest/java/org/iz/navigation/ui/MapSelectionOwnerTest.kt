package org.iz.navigation.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.integration.MapOutlineReadResult
import org.iz.navigation.integration.MapPlaceCandidate
import org.iz.navigation.integration.MapPlaceOutline
import org.junit.Assert.*
import org.junit.Test

/** Deterministic owner/cancellation cases; native map and real Overpass remain separate acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapSelectionOwnerTest {
    private val point = MapPlaceCandidate(SelectedOsmPlace("Point", 0.0, 0.0, OsmRef(OsmType.NODE, 1)), "Yer")
    private val way = MapPlaceCandidate(SelectedOsmPlace("Area", .001, .001, OsmRef(OsmType.WAY, 42)), "Bina")
    private val ring = MapPlaceOutline(listOf(GeoCoordinate(0.0, 0.0), GeoCoordinate(0.0, .001), GeoCoordinate(.001, .001), GeoCoordinate(0.0, 0.0)))

    @Test fun delayedNoncancellableGeometryStopsVisibleWaitAtTwentySecondsAndCannotReturnLate() {
        runTest {
            val release = CompletableDeferred<Unit>()
            val opened = mutableListOf<SelectedOsmPlace>()
            var reads = 0
            val owner = PlaceInformationDiscoveryOwner(this, { _, _ -> listOf(point, way) }, { opened += it }, { "session-a" },
                outline = { reads++; withContext(NonCancellable) { release.await() }; MapOutlineReadResult(ring) })
            try {
                owner.tap(0.0, 0.0, 17.0); runCurrent()
                owner.choose(way.place, owner.state.generation); runCurrent()
                assertTrue(owner.state.outlineBusy)
                advanceTimeBy(19_999); runCurrent(); assertTrue(owner.state.outlineBusy)
                advanceTimeBy(1); runCurrent()
                assertFalse(owner.state.outlineBusy)
                assertTrue(owner.state.outlineRetryable)
                assertEquals(way.place.osmRef, owner.state.selectedRef)
                assertEquals(2, owner.state.candidates.size)
                owner.openInformation(); assertEquals(listOf(way.place), opened)
                release.complete(Unit); runCurrent()
                assertNull(owner.state.selectedCandidate!!.outline)
                assertEquals(1, reads)
            } finally { release.complete(Unit); owner.close(); runCurrent() }
        }
    }
    @Test fun selectionReplacementClearsShapeAndRejectsAnIgnoredCancellationResult() {
        runTest {
            val release = CompletableDeferred<Unit>()
            val owner = PlaceInformationDiscoveryOwner(this, { _, _ -> listOf(point, way) }, {}, { "session" },
                outline = { withContext(NonCancellable) { release.await() }; MapOutlineReadResult(ring) })
            try {
                owner.tap(0.0, 0.0, 17.0); runCurrent()
                owner.choose(way.place, owner.state.generation); runCurrent()
                owner.choose(point.place, owner.state.generation)
                assertFalse(owner.state.outlineBusy)
                assertNull(owner.state.selectedCandidate!!.outline)
                release.complete(Unit); runCurrent()
                assertEquals(point.place.osmRef, owner.state.selectedRef)
                assertTrue(owner.state.candidates.all { it.outline == null })
            } finally { release.complete(Unit); owner.close(); runCurrent() }
        }
    }
    @Test fun selectionDoesNotOpenDetailsAndOpeningDetailsKeepsTheSameListAndGeneration() {
        runTest {
            val opened = mutableListOf<SelectedOsmPlace>()
            val owner = PlaceInformationDiscoveryOwner(this, { _, _ -> listOf(point, way) }, { opened += it }, { "session" })
            owner.tap(0.0, 0.0, 17.0); runCurrent()
            assertNull(owner.state.selectedRef); assertTrue(opened.isEmpty())
            owner.choose(point.place, owner.state.generation)
            assertTrue(opened.isEmpty())
            val before = owner.state
            owner.openInformation()
            assertEquals(before, owner.state)
            assertEquals(listOf(point.place), opened)
            owner.tap(1.0, 1.0, 17.0); runCurrent()
            owner.openInformation(before.generation, before.selectedRef)
            assertEquals(1, opened.size)
            owner.close()
        }
    }
    @Test fun aSingleWayOpensInformationDirectlyWithoutOutlineLookup() {
        runTest {
            var outlineCalls = 0
            val opened = mutableListOf<SelectedOsmPlace>()
            val owner = PlaceInformationDiscoveryOwner(this, { _, _ -> listOf(way) }, { opened += it }, { "session" },
                outline = { outlineCalls++; MapOutlineReadResult(ring) })
            owner.tap(0.0, 0.0, 17.0); runCurrent()
            assertEquals(listOf(way.place), opened)
            assertEquals(0, outlineCalls)
            assertFalse(owner.state.open)
            owner.close()
        }
    }
    @Test fun geometryFailureKeepsDetailsAndOnlyManualRetryReadsAgain() {
        runTest {
            var calls = 0
            val opened = mutableListOf<SelectedOsmPlace>()
            val owner = PlaceInformationDiscoveryOwner(this, { _, _ -> listOf(point, way) }, { opened += it }, { "session" },
                outline = { calls++; if (calls == 1) error("Synthetic budget exhaustion") else MapOutlineReadResult(ring) })
            owner.tap(0.0, 0.0, 17.0); runCurrent()
            owner.choose(way.place, owner.state.generation); runCurrent()
            assertFalse(owner.state.outlineBusy); assertTrue(owner.state.outlineRetryable)
            owner.openInformation(); assertEquals(listOf(way.place), opened)
            advanceTimeBy(20_001); runCurrent(); assertEquals(1, calls)
            owner.retryOutline(); runCurrent()
            assertEquals(2, calls); assertEquals(ring, owner.state.selectedCandidate!!.outline)
            assertFalse(owner.state.outlineRetryable)
            owner.close()
        }
    }
}
