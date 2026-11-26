package org.iz.navigation.wear

import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.garage.GarageRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class WearGarageSelectionTest {
    private val a = GarageVehicle("a", "Bike A", Transport.MOTORCYCLE, 12.0)
    private val b = a.copy(id = "b", name = "Bike B")
    private val car = a.copy(id = "car", transport = Transport.CAR)
    @Test fun multipleVehiclesRequirePhoneChoiceAndNeverPickTheFirst() {
        assertEquals(WearGarageSelection.NeedsPhone, wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a, b), null))
        assertEquals(WearGarageSelection.Ready("b", explicit = true), wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a, b), "b"))
        assertEquals(WearGarageSelection.Invalid, wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a), "b"))
        assertEquals(WearGarageSelection.Invalid, wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a), "car"))
    }
    @Test fun EmptyGarageAndOtherActivitiesKeepTheOriginalStartPath() {
        assertEquals(WearGarageSelection.Ready(null), wearGarageSelection(Transport.MOTORCYCLE, emptyList(), null))
        assertEquals(WearGarageSelection.Ready("a"), wearGarageSelection(Transport.MOTORCYCLE, listOf(car, a), null))
        assertEquals(WearGarageSelection.Ready(null), wearGarageSelection(Transport.WALK, listOf(a, b), null))
    }
    @Test fun singleAutomaticVehicleAndARealPhoneChoiceKeepDifferentWriteIntents() {
        assertEquals(WearGarageSelection.Ready("a", explicit = false), wearGarageSelection(Transport.MOTORCYCLE, listOf(a), null))
        assertEquals(WearGarageSelection.Ready("a", explicit = true), wearGarageSelection(Transport.MOTORCYCLE, listOf(a), "a"))
    }
    @Test fun inheritedStartChoiceUsesTheLatestExplicitVehicleOfTheSameSession() {
        val state = NavigationState(sessionId = "session", sessionTransport = Transport.MOTORCYCLE)
        val latest = GarageRepository.Binding("session", "b", explicitChoice = true)
        fun choice(current: NavigationState = state, binding: GarageRepository.Binding? = latest,
            source: String? = "session") = wearStartChoiceForBinding(null, source, Transport.MOTORCYCLE, "new", current, binding)
        assertEquals("b", choice())
        assertEquals("b", choice(state.copy(journey = Journey(id = "new", transport = Transport.MOTORCYCLE))))
        assertNull(choice(binding = latest.copy(explicitChoice = false)))
        assertNull(choice(binding = latest.copy(sessionId = "other")))
        assertNull(choice(binding = null))
        assertNull(choice(source = null))
        assertNull(choice(state.copy(sessionId = "other")))
        assertNull(choice(state.copy(sessionTransport = Transport.CAR)))
        assertNull(choice(state.copy(journey = Journey(id = "other", transport = Transport.MOTORCYCLE))))
        assertNull(choice(state.copy(simulation = true)))
    }
    @Test fun phoneChoiceTakesPriorityWhileAutomaticSingletonDoesNotWriteAfterStart() {
        val state = NavigationState(sessionId = "session", sessionTransport = Transport.MOTORCYCLE)
        val previous = GarageRepository.Binding("session", "b", explicitChoice = true)
        assertEquals("a", wearStartChoiceForBinding("a", "session", Transport.MOTORCYCLE, "new", state, previous))
        assertNull(wearStartChoiceForBinding("a", "session", Transport.MOTORCYCLE, "new", state.copy(simulation = true), previous))
        assertNull(wearStartChoiceForBinding(null, null, Transport.MOTORCYCLE, "new", state, previous))
    }
}
