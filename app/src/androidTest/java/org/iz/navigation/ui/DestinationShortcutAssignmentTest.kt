package org.iz.navigation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.DestinationShortcutKind
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.Place
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DestinationShortcutAssignmentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyValidPlacesCanBeChosenAndNewPlaceAndCancelAreExplicitActions() {
        val ready = Place(id = "located", name = "Atölye", latitude = 41.0, longitude = 29.0)
        val missing = Place(id = "unlocated", name = "Konumsuz kayıt")
        val actions = mutableListOf<String>()
        compose.setContent {
            IzTheme {
                DestinationShortcutAssignmentScreen(DestinationShortcutKind.WORK, listOf(missing, ready),
                    busy = false, error = null, onPlace = { actions += it.id },
                    onNewPlace = { actions += "new" }, onCancel = { actions += "cancel" })
            }
        }
        compose.onNodeWithText("İş adresi ekle").assertIsDisplayed()
        compose.onNodeWithText(missing.name).assertDoesNotExist()
        val list = compose.onNodeWithTag("shortcut-assignment-list")
        val row = hasTestTag("shortcut-assignment-place-located")
        list.performScrollToNode(row)
        compose.onNode(row).performClick()
        compose.onNodeWithTag("shortcut-assignment-new").performClick()
        compose.onNodeWithTag("shortcut-assignment-cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("located", "new", "cancel"), actions) }
    }

    @Test fun errorAndEmptyStateStayVisibleAndBusyPreventsRepeatedActions() {
        compose.setContent {
            IzTheme {
                DestinationShortcutAssignmentScreen(DestinationShortcutKind.HOME, emptyList(), busy = true,
                    error = "Adres kaydedilemedi.", onPlace = {}, onNewPlace = {}, onCancel = {})
            }
        }
        compose.onNodeWithText("Ev adresi ekle").assertIsDisplayed()
        compose.onNodeWithText("Konumlu kayıtlı yer bulunamadı.").assertExists()
        compose.onNodeWithText("Adres kaydedilemedi.").assertIsDisplayed()
        compose.onNodeWithTag("shortcut-assignment-new").assertIsNotEnabled()
        compose.onNodeWithTag("shortcut-assignment-cancel").assertIsNotEnabled()
    }

    @Test fun newAddressRequiresLocationAndOmitsVisitInput() {
        var location by mutableStateOf<GeoCoordinate?>(null)
        val saved = mutableListOf<String>()
        compose.setContent {
            IzTheme {
                PlaceEditor(location, null, emptyList(), onDismiss = {}, onSearch = {}, onLocate = {},
                    assignmentKind = DestinationShortcutKind.HOME, onSave = { name, note, existing ->
                        check(existing == null && note.isEmpty())
                        saved += name
                    })
            }
        }
        compose.onNodeWithText("Ev adresi ekle").assertIsDisplayed()
        compose.onNodeWithText("Bu ziyarete özel not").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Ev")
        val save = compose.onNodeWithTag("shortcut-assignment-editor-save")
        save.assertIsNotEnabled()
        compose.runOnIdle { location = GeoCoordinate(41.0, 29.0) }
        save.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Ev"), saved) }
    }
}
