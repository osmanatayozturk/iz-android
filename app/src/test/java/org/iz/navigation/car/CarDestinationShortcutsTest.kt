package org.iz.navigation.car

import org.iz.navigation.data.DestinationShortcut
import org.iz.navigation.data.DestinationShortcutKind
import org.iz.navigation.data.Place
import org.junit.Assert.*
import org.junit.Test

class CarDestinationShortcutsTest {
    @Test fun explicitlySelectedSharedPlaceProvidesTargetAndMissingCoordinatesNeverBecomeZero() {
        val place = Place(id = "home-place", name = "Selected home", latitude = 41.0, longitude = 29.0)
        val stop = carShortcutStop(DestinationShortcut(DestinationShortcutKind.HOME, place))!!
        assertEquals("Selected home", stop.label)
        assertEquals(41.0, stop.coordinate.latitude, 0.0)
        assertEquals(29.0, stop.coordinate.longitude, 0.0)
        assertNull(carShortcutStop(DestinationShortcut(DestinationShortcutKind.HOME, place.copy(latitude = null))))
        assertNull(carShortcutStop(DestinationShortcut(DestinationShortcutKind.HOME, place.copy(longitude = Double.NaN))))
        assertNull(carShortcutStop(DestinationShortcut(DestinationShortcutKind.HOME, place.copy(latitude = 91.0))))
    }

    @Test fun sharedPlaceEditsProduceUpdatedCoordinateAndBlankNameHasExplicitKindLabel() {
        val first = DestinationShortcut(DestinationShortcutKind.WORK, Place(name = "Office", latitude = 40.0, longitude = 28.0))
        val edited = first.copy(place = first.place.copy(name = " ", latitude = 0.0, longitude = 0.0))
        assertEquals("İş", carShortcutStop(edited)!!.label)
        assertEquals(0.0, carShortcutStop(edited)!!.coordinate.latitude, 0.0)
        assertEquals(0.0, carShortcutStop(edited)!!.coordinate.longitude, 0.0)
        assertNotEquals(carShortcutStop(first), carShortcutStop(edited))
    }
}
