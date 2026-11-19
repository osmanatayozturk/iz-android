package org.iz.navigation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.iz.navigation.data.DestinationShortcut
import org.iz.navigation.data.DestinationShortcutKind
import org.iz.navigation.data.Place
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlacesOrderUiTest {
    @get:Rule val compose = createComposeRule()
    private val places = listOf(Place(id = "a", name = "Alpha"), Place(id = "b", name = "Beta"), Place(id = "c", name = "Gamma"))
    @Test fun accessibleMoveIsDraftUntilSaveAndCancelDoesNotPersist() {
        var saved: List<String>? = null
        compose.setContent { IzTheme { PlacesScreen(DiaryState(places = places), {}, {}, { _, order -> saved = order }) } }
        compose.onNodeWithTag("places-sort").performClick()
        compose.onNodeWithTag("place-up-b").performScrollTo().performClick()
        compose.onNodeWithTag("places-sort-cancel").performScrollTo().performClick()
        assertNull(saved)
        compose.onNodeWithTag("places-sort").performClick()
        compose.onNodeWithTag("place-up-b").performScrollTo().performClick()
        compose.onNodeWithTag("places-sort-save").performScrollTo().performClick()
        compose.waitUntil { saved != null }
        assertEquals(listOf("b", "a", "c"), saved)
    }
    @Test fun filteredListCannotStartReordering() {
        compose.setContent { IzTheme { PlacesScreen(DiaryState(places = places), {}, {}, { _, _ -> }) } }
        compose.onNodeWithTag("places-search").performTextInput("Alpha")
        compose.onNodeWithTag("places-sort").assertIsNotEnabled()
        compose.onNodeWithText("Sıralamak için aramayı temizle.").assertIsDisplayed()
    }
    @Test fun homeAliasFiltersSavedPlacesAndOpensTheAssignedRecord() {
        val home = places[1].copy(latitude = 39.9, longitude = 32.8)
        var opened: Place? = null
        compose.setContent { IzTheme {
            PlacesScreen(DiaryState(places = listOf(places[0], home, places[2])),
                onPlace = { opened = it }, onAdd = {}, onReorder = { _, _ -> },
                shortcuts = listOf(DestinationShortcut(DestinationShortcutKind.HOME, home)))
        } }
        compose.onNodeWithTag("places-search").performTextInput("Ev")
        compose.onNodeWithTag("place-row-a").assertDoesNotExist()
        compose.onNodeWithTag("place-row-b").performClick()
        compose.runOnIdle { assertEquals(home, opened) }
    }
    @Test fun dragHandleMovesAPlaceAndSavesTheResult() {
        var saved: List<String>? = null
        compose.setContent { IzTheme { PlacesScreen(DiaryState(places = places.take(2)), {}, {}, { _, order -> saved = order }) } }
        compose.onNodeWithTag("places-sort").performClick()
        compose.onNodeWithTag("place-drag-a").performScrollTo().performTouchInput {
            down(center); moveBy(Offset(0f, with(compose.density) { 90.dp.toPx() }), 400); up()
        }
        compose.onNodeWithTag("places-sort-save").performScrollTo().performClick()
        compose.waitUntil { saved != null }
        assertEquals(listOf("b", "a"), saved)
    }
}
