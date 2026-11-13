package org.iz.navigation.ui

import org.iz.navigation.data.MapEditFieldAction
import org.junit.Assert.assertEquals
import org.junit.Test

class MapEditFieldIntentTest {
    @Test fun clearingAPopulatedFieldMeansDelete() {
        assertEquals(MapEditFieldAction.DELETE, fieldAction("  ", "Mevcut ad"))
    }
    @Test fun anEmptyOptionalFieldDoesNotMeanDelete() {
        assertEquals(MapEditFieldAction.UNTOUCHED, fieldAction("", ""))
    }
    @Test fun restoringTheOriginalValueRemovesEditIntent() {
        assertEquals(MapEditFieldAction.UNTOUCHED, fieldAction(" Mevcut ad ", "Mevcut ad"))
    }
    @Test fun replacingOrAddingAValueMeansSet() {
        assertEquals(MapEditFieldAction.SET, fieldAction("+90 312 555 01 23", "0312 555 99 99"))
        assertEquals(MapEditFieldAction.SET, fieldAction("Yeni ad", ""))
    }
}
