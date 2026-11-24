package org.iz.navigation.wear

import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle
import org.junit.Assert.*
import org.junit.Test

class WearGarageSelectionTest {
    private val a = GarageVehicle("a", "Bike A", Transport.MOTORCYCLE, 12.0)
    private val b = a.copy(id = "b", name = "Bike B")
    private val car = a.copy(id = "car", transport = Transport.CAR)
    @Test fun multipleVehiclesRequirePhoneChoiceAndNeverPickTheFirst() {
        assertEquals(WearGarageSelection.NeedsPhone, wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a, b), null))
        assertEquals(WearGarageSelection.Ready("b"), wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a, b), "b"))
        assertEquals(WearGarageSelection.Invalid, wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a), "b"))
        assertEquals(WearGarageSelection.Invalid, wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a), "car"))
    }
    @Test fun EmptyGarageAndOtherActivitiesKeepTheOriginalStartPath() {
        assertEquals(WearGarageSelection.Ready(null), wearGarageSelection(Transport.MOTORCYCLE, emptyList(), null))
        assertEquals(WearGarageSelection.Ready("a"), wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a), null))
        assertEquals(WearGarageSelection.Ready(null), wearGarageSelection(Transport.WALK, listOf(a, b), null))
    }
}
