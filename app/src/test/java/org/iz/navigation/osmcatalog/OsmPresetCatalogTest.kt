package org.iz.navigation.osmcatalog

import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmPresetCatalogTest {
    private val raw = File(System.getProperty("iz.catalogue") ?: "src/main/assets/osm-light-catalogue-v1.json").readText()
    private fun catalog() = OsmPresetCatalog.fromJson(raw)

    @Test fun pinnedLoadResolvesTurkishNamesAndKeepsGeometrySpecificStableIds() {
        val catalog = OsmPresetCatalog.load(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
        assertEquals("f4cd7759d2ef509ae0360662988208d16a21374c", catalog.commit)
        assertEquals("Kafe", catalog.resolve("amenity/cafe")!!.name)
        assertTrue(catalog.resolve("amenity/cafe/teahouse")!!.turkishAliases.contains("Çay Ocağı"))
        assertFalse(catalog.resolve("building/house")!!.geometry.contains(OsmPresetGeometry.POINT))
        assertNull(catalog.resolve("not/a/catalogue/id"))
    }

    @Test fun searchAcceptsTurkishAliasesAndKeyValueAndBoostsFavoritesWithoutOfferingAreasAsPoints() {
        val catalog = catalog()
        assertTrue(catalog.search("ÇAY OCAĞI", OsmPresetGeometry.POINT).any { it.id == "amenity/cafe/teahouse" })
        assertTrue(catalog.search("amenity=cafe", OsmPresetGeometry.POINT).any { it.id == "amenity/cafe" })
        assertEquals("amenity/pharmacy", catalog.search("", OsmPresetGeometry.POINT, setOf("amenity/pharmacy", "missing" )).first().id)
        assertFalse(catalog.search("building=house", OsmPresetGeometry.POINT).any { it.id == "building/house" })
    }

    @Test fun selectedRuleSnapshotDoesNotChangeWhenTheLoadedCatalogueIsReplaced() {
        val catalog = catalog()
        val snapshot = catalog.snapshot("amenity/cafe", OsmPresetGeometry.POINT)
        val changed = JSONObject(raw)
        val presets = changed.getJSONArray("presets")
        for (i in 0 until presets.length()) if (presets.getJSONObject(i).getString("id") == "amenity/cafe") {
            presets.getJSONObject(i).getJSONObject("addTags").put("amenity", "bar")
        }
        val replacement = OsmPresetCatalog.fromJson(changed.toString())
        assertNotEquals(catalog.sha256, replacement.sha256)
        assertEquals("cafe", OsmPresetTagTransition.apply(emptyMap(), null, snapshot)["amenity"])
        assertEquals("bar", OsmPresetTagTransition.apply(emptyMap(), null,
            replacement.snapshot("amenity/cafe", OsmPresetGeometry.POINT))["amenity"])
        assertEquals(snapshot, OsmPresetRuleSnapshot.fromJson(snapshot.toJson()))
    }

    @Test fun businessTypeChangePreservesBuildingAddressAndUntouchedPhoneAliases() {
        val catalog = catalog()
        val before = mapOf("amenity" to "cafe", "building" to "apartments", "addr:street" to "Test",
            "name" to "Local", "phone" to "+9000000000", "contact:phone" to "+9000000001")
        val next = OsmPresetTagTransition.apply(before, catalog.snapshot("amenity/cafe", OsmPresetGeometry.AREA),
            catalog.snapshot("amenity/pharmacy", OsmPresetGeometry.AREA))
        assertEquals(before + mapOf("amenity" to "pharmacy", "healthcare" to "pharmacy"), next)
        assertEquals("cafe", before["amenity"])
    }

    @Test fun wildcardMatchingAndCreationNeverProduceLiteralStarAndRecognitionOnlyPresetCannotBeSelected() {
        val catalog = catalog()
        assertTrue(catalog.identify(mapOf("building" to "apartments"), OsmPresetGeometry.AREA).any { it.id == "building" })
        val generic = catalog.snapshot("building", OsmPresetGeometry.AREA)
        assertEquals(mapOf("building" to "yes"), OsmPresetTagTransition.apply(emptyMap(), null, generic))
        assertEquals(mapOf("building" to "apartments"), OsmPresetTagTransition.apply(mapOf("building" to "apartments"), null, generic))
        assertTrue(catalog.identify(mapOf("addr:street" to "Test"), OsmPresetGeometry.POINT).any { it.id == "address" })
        assertThrows(IllegalArgumentException::class.java) { catalog.snapshot("address", OsmPresetGeometry.POINT) }
    }

    @Test fun lineAreaPresetAddsAreaMarkerOnlyForAreaAndRejectsUnsupportedGeometry() {
        val catalog = catalog()
        val area = OsmPresetTagTransition.apply(emptyMap(), null, catalog.snapshot("advertising", OsmPresetGeometry.AREA))
        assertEquals("yes", area["area"])
        assertEquals("yes", area["advertising"])
        assertFalse(OsmPresetTagTransition.apply(emptyMap(), null, catalog.snapshot("advertising", OsmPresetGeometry.POINT)).containsKey("area"))
        assertThrows(IllegalArgumentException::class.java) { catalog.snapshot("building/house", OsmPresetGeometry.POINT) }
    }

    @Test fun explicitRemovalHonorsExactValuesAndWildcardWithoutDeletingUnrelatedCrop() {
        val catalog = catalog()
        val old = catalog.snapshot("landuse/vineyard", OsmPresetGeometry.AREA)
        val next = catalog.snapshot("amenity/cafe", OsmPresetGeometry.AREA)
        assertEquals(mapOf("amenity" to "cafe", "name" to "Test"), OsmPresetTagTransition.apply(
            mapOf("landuse" to "vineyard", "crop" to "grape", "grape_variety" to "merlot", "name" to "Test"), old, next))
        assertEquals("apple", OsmPresetTagTransition.apply(mapOf("landuse" to "vineyard", "crop" to "apple"), old, next)["crop"])
    }

    @Test fun pinnedHashAndBoundedLoaderRejectTamperingAndOversizeBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            OsmPresetCatalog.load(ByteArrayInputStream(raw.replace("Kafe", "Other").toByteArray(Charsets.UTF_8)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OsmPresetCatalog.load(ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1)))
        }
    }
}
