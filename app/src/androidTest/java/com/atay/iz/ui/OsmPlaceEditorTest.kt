package com.atay.iz.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmPlaceEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedSchoolSavesPrivateVisitWithoutPublicReview() {
        var savedName: String? = null
        var savedNote: String? = null
        compose.setContent { MaterialTheme {
            PlaceEditor(GeoCoordinate(39.9, 32.8), SelectedOsmPlace("Örnek Okulu", 39.9, 32.8, OsmRef(OsmType.WAY, 42)), emptyList(), {}, {}, {}) { name, note, existing ->
                savedName = name; savedNote = note; assertNull(existing)
            }
        } }
        compose.onNodeWithText("Bu ziyarete özel not").performTextInput("Kişisel not")
        compose.onNodeWithText("Ziyareti kaydet").performClick()
        compose.runOnIdle { assertEquals("Örnek Okulu", savedName); assertEquals("Kişisel not", savedNote) }
    }

    @Test fun sameOsmObjectReusesSavedLocalPlace() {
        val saved = Place(name = "Benim okulum", osmType = OsmType.WAY, osmId = 42, source = PlaceSource.OSM)
        var selected: Place? = null
        compose.setContent { MaterialTheme {
            PlaceEditor(null, SelectedOsmPlace("Örnek Okulu", 39.9, 32.8, OsmRef(OsmType.WAY, 42)), listOf(saved), {}, {}, {}) { _, _, existing -> selected = existing }
        } }
        compose.onNodeWithText("Benim okulum").assertExists()
        compose.onNodeWithText("Ziyareti kaydet").performClick()
        compose.runOnIdle { assertEquals(saved.id, selected?.id) }
    }

    @Test fun visitEditorReturnsEditedPrivateNote() {
        val visit = Visit(placeId = "local", note = "Eski not", rating = 4)
        var saved: String? = null
        compose.setContent { MaterialTheme { VisitEditor(visit, {}) { saved = it } } }
        compose.onNodeWithText("Yalnızca sana özel").performTextReplacement("Yeni not")
        compose.onNodeWithText("Kaydet").performClick()
        compose.runOnIdle { assertEquals("Yeni not", saved) }
    }
}
