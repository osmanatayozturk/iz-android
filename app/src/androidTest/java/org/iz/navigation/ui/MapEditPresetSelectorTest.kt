package org.iz.navigation.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.osmcatalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapEditPresetSelectorTest {
    @get:Rule val compose = createComposeRule()
    private val catalog by lazy {
        OsmPresetCatalog.load(ApplicationProvider.getApplicationContext<Context>().assets.open(OsmPresetCatalog.BUNDLED_ASSET))
    }

    @Test fun favoriteToggleDoesNotSelectAndSelectingDoesNotToggleFavorite() {
        val cafe = requireNotNull(catalog.resolve("amenity/cafe"))
        var selected by mutableStateOf<OsmPresetRuleSnapshot?>(null)
        var favorites by mutableStateOf(emptySet<String>())
        var toggles = 0
        compose.setContent { MaterialTheme {
            MapEditPresetSelector(catalog, OsmPresetGeometry.POINT, selected?.toJson(), favorites, true,
                { selected = it }, { id, enabled ->
                    toggles++
                    favorites = if (enabled) favorites + id else favorites - id
                })
        } }
        compose.onNodeWithTag("osm-map-type").performClick()
        compose.onNodeWithText("Tür ara").performTextInput("amenity=cafe")
        compose.onNodeWithContentDescription("${cafe.name} favorilere ekle").performClick()
        compose.runOnIdle { assertNull(selected); assertEquals(setOf("amenity/cafe"), favorites); assertEquals(1, toggles) }
        compose.onNodeWithText("Favoriler").assertIsDisplayed()
        compose.onNodeWithText(cafe.name).performClick()
        compose.runOnIdle { assertEquals("amenity/cafe", selected?.presetId); assertEquals(1, toggles) }
    }

    @Test fun closingSearchPreservesTheAreaSnapshot() {
        val initial = catalog.snapshot("amenity/cafe", OsmPresetGeometry.AREA)
        var selected = initial
        compose.setContent { MaterialTheme {
            MapEditPresetSelector(catalog, OsmPresetGeometry.AREA, initial.toJson(), emptySet(), true,
                { selected = it }, { _, _ -> error("Search must not toggle favorites") })
        } }
        compose.onNodeWithTag("osm-map-type").performClick()
        compose.onNodeWithText("Tür ara").performTextInput("zzzz-no-area-match")
        compose.onNodeWithText("Tür bulunamadı. Başka bir ad veya etiket dene.").assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { assertEquals(initial, selected) }
        compose.onNodeWithTag("osm-map-type").assertIsDisplayed()
    }
}
