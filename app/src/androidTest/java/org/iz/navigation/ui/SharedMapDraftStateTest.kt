package org.iz.navigation.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.car.*
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedMapDraftStateTest {
    @Test fun unusuallyLongSelectedNameIsNormalizedOnlyAtSharedSelectionBoundary() {
        val selected = org.iz.navigation.data.SelectedOsmPlace("  " + "Uzun ad ".repeat(100) + "  ", 41.0, 29.0)
        val stop = sharedSelectedStop(selected)
        assertEquals(selected.name.trim().take(300), stop.name)
        assertEquals(300, stop.name.length)
        assertEquals(stop.name, org.iz.navigation.weather.RouteStop(stop.name, requireNotNull(stop.coordinate)).label)
        assertEquals(WeatherCoordinate(41.0, 29.0), stop.coordinate)
    }
    @Test fun blankOrControlOnlySelectedNameUsesSafeNonblankFallback() {
        val stop = sharedSelectedStop(org.iz.navigation.data.SelectedOsmPlace(" \n\t ", 41.0, 29.0))
        assertEquals("Hedef", stop.name)
    }
    @Test fun repeatedUnresolvedOccurrencesRequireIndividualSelectionsAndRestoreInOrder() {
        val input = SharedMapInput.Itinerary(null, listOf(SharedMapStop("A"), SharedMapStop("A")), SharedMapStop("B"))
        val first = requireNotNull(input.draft())
        assertTrue(first.dynamicOrigin)
        assertFalse(first.complete)
        val chosen = first.choose(0, SharedMapStop("A", WeatherCoordinate(41.0, 29.0)))
        assertNull(chosen.stops[1].coordinate)
        assertEquals(listOf("A", "A", "B"), chosen.stops.map { it.name })
        assertEquals(input, decodeSharedMapInput(encodeSharedMapInput(input)))
    }
    @Test fun restoredUrlCannotCreateAnArbitrarySourceAppActionOrFakeCoordinate() {
        val input = SharedMapInput.Unsupported("Yer belirlenemedi", "https://example.test/private")
        val restored = decodeSharedMapInput(encodeSharedMapInput(input)) as SharedMapInput.Unsupported
        assertNull(restored.originalUrl)
        assertNull(restored.draft())
        val invalid = SharedMapInput.Single(SharedMapStop("x".repeat(301)))
        assertNull(decodeSharedMapInput(encodeSharedMapInput(invalid)))
    }
}
