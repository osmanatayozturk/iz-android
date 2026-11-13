package org.iz.navigation.data

import java.io.File
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MapEditDisplayLabelTest {
    private val catalog by lazy {
        val file = System.getProperty("iz.catalogue")?.let(::File)
            ?: File("src/main/assets/osm-light-catalogue-v1.json").takeIf { it.isFile }
            ?: File("app/src/main/assets/osm-light-catalogue-v1.json")
        OsmPresetCatalog.load(file.inputStream())
    }

    @Test fun unnamedNonEnumTypeUsesItsFrozenNameWithoutPublishingItAsAnOsmName() {
        val rule = catalog.snapshot("natural/tree", OsmPresetGeometry.POINT)
        val frozenName = "Taslakta sabit ağaç"
        val frozen = rule.copy(ruleJson = JSONObject(rule.ruleJson).put("name", frozenName).toString())
        val draft = newMapEditDraft(39.93, 32.85).selectPreset(frozen)
        assertEquals(MapPlacePreset.CAFE, draft.preset)
        assertEquals("natural/tree", draft.presetId)
        assertNotEquals(frozenName, catalog.resolve("natural/tree")!!.name)
        assertEquals(frozenName, draft.displayLabel())
        assertEquals(mapOf("natural" to "tree"), draft.publicTags())
        assertFalse(draft.publicTags().containsKey("name"))
    }

    @Test fun anUnselectedV2DraftUsesAGenericLabelAndNeverTheLegacyCafeDefault() {
        assertEquals("Tür seçilmemiş OSM mekânı", newMapEditDraft(39.93, 32.85).displayLabel())
    }

    @Test fun anExplicitNameAndLegacyTypeFallbackKeepTheirExistingDisplayMeaning() {
        val legacy = MapEditDraft(latitude = 39.93, longitude = 32.85, preset = MapPlacePreset.DRINKING_WATER)
        assertEquals("İçme suyu", legacy.displayLabel())
        assertEquals("Yerel ad", legacy.copy(name = "Yerel ad").displayLabel())
        assertEquals("Yerel ağaç", newMapEditDraft(39.93, 32.85, "Yerel ağaç").displayLabel())
    }
}
