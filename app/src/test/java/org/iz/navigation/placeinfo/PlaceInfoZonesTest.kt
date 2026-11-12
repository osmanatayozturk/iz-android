package org.iz.navigation.placeinfo

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PlaceInfoZonesTest {
    private val device = ZoneId.of("America/New_York")

    @Test fun validLocalOverrideHasPriorityOverOsmAndDevice() {
        val value = PlaceInfoZones.resolve("Europe/Istanbul", "Europe/Berlin", device)
        assertEquals(ZoneId.of("Europe/Istanbul"), value.zone)
        assertEquals(PlaceZoneSource.OVERRIDE, value.source)
    }

    @Test fun invalidPersistedOverrideIsIgnoredInFavorOfValidOsmZone() {
        for (override in listOf(null, "", "bad/zone", " Europe/Istanbul ")) {
            val value = PlaceInfoZones.resolve(override, "Europe/Berlin", device)
            assertEquals(ZoneId.of("Europe/Berlin"), value.zone)
            assertEquals(PlaceZoneSource.OSM, value.source)
        }
    }

    @Test fun missingOrInvalidBothSourcesFallBackToExactlyTheSuppliedDeviceZone() {
        for ((override, osm) in listOf(null to null, "bad/zone" to "also/bad", "" to "", null to "GMT+99:00")) {
            val value = PlaceInfoZones.resolve(override, osm, device)
            assertEquals(device, value.zone)
            assertEquals(PlaceZoneSource.DEVICE, value.source)
        }
    }

    @Test fun validOverrideWorksEvenWhenOsmIsInvalidAndPreservesFixedOffsetIdentity() {
        val value = PlaceInfoZones.resolve("+03:00", "bad/zone", device)
        assertEquals(ZoneId.of("+03:00"), value.zone)
        assertEquals(PlaceZoneSource.OVERRIDE, value.source)
    }

    @Test fun clearingOverrideImmediatelyUsesTheNextValidSource() {
        val overridden = PlaceInfoZones.resolve("Europe/Istanbul", "Europe/Berlin", device)
        val reset = PlaceInfoZones.resolve(null, "Europe/Berlin", device)
        assertNotEquals(overridden.zone, reset.zone)
        assertEquals(PlaceZoneSource.OSM, reset.source)
        val fallback = PlaceInfoZones.resolve(null, null, ZoneId.of("Pacific/Auckland"))
        assertEquals(ZoneId.of("Pacific/Auckland"), fallback.zone)
        assertEquals(PlaceZoneSource.DEVICE, fallback.source)
    }
}
