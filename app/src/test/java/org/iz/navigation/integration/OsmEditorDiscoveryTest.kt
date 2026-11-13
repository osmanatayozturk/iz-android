package org.iz.navigation.integration

import java.io.File
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.junit.Assert.*
import org.junit.Test

class OsmEditorDiscoveryTest {
    @Test fun unnamedBuildingAndNonLegacyPointRetainActualOsmIdentity() {
        val found = parseOverpassPlaces("""{"elements":[
            {"type":"way","id":77,"center":{"lat":39.93,"lon":32.85},"tags":{"building":"house"}},
            {"type":"node","id":88,"lat":39.93,"lon":32.85,"tags":{"emergency":"defibrillator"}}
        ]}""")
        assertEquals(setOf(OsmRef(OsmType.WAY, 77), OsmRef(OsmType.NODE, 88)), found.map { it.osmRef }.toSet())
        assertTrue(found.all { it.name.isNotBlank() })
    }

    @Test fun discoveryUsesCatalogueMatchKeysRatherThanLegacyCategoryWhitelist() {
        val file = File(System.getProperty("iz.catalogue") ?: "src/main/assets/osm-light-catalogue-v1.json")
        val catalog = OsmPresetCatalog.load(file.inputStream())
        val keys = osmEditorMatchKeys(catalog)
        assertTrue(keys.containsAll(setOf("building", "amenity", "shop", "emergency", "power", "natural")))
        assertFalse(keys.any { '*' in it })
    }

    @Test fun publicTapQueryKeepsRadiusAndReadLimitsAndDoesNotFetchFullMapBbox() {
        assertEquals("[out:json][timeout:15][maxsize:16777216];nwr(around:100,39.9300,32.8500)[~\"^(amenity|building|emergency)$\"~\".\"];out center tags qt;",
            osmEditorNearbyQuery(39.93, 32.85, setOf("emergency", "amenity", "building")))
    }
}
