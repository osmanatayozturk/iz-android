package org.iz.navigation.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Baseline-compilable RED contract; no real search client, GPS or permission request. */
@RunWith(AndroidJUnit4::class)
class SharedSearchContractUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contextPickerUsesTheCommonNereyeHeading() {
        compose.setContent { IzTheme {
            OsmSearchDialog(onDismiss = {}, onSelect = {}, searchPlaces = { emptyList() })
        } }
        compose.onNodeWithText("Nereye?").assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("osm-search-submit").assertIsNotEnabled()
    }

    @Test fun explicitImeSearchPreservesTheSelectedOsmIdentityAndContextCallback() {
        val calls = mutableListOf<String>()
        val place = SelectedOsmPlace("Sentetik kafe", 41.0, 29.0, OsmRef(OsmType.WAY, 700),
            displayName = "Sentetik Sokak 1")
        var selected: SelectedOsmPlace? = null
        compose.setContent { IzTheme {
            OsmSearchDialog(onDismiss = {}, onSelect = { selected = it },
                searchPlaces = { query -> calls += query; listOf(place) })
        } }
        val input = compose.onNodeWithTag("osm-search-query")
        input.performScrollTo().performTextReplacement("  sentetik  ")
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
            .performImeAction()
        compose.onNodeWithTag("osm-search-results").performScrollToNode(hasText("Sentetik kafe"))
        compose.onNodeWithText("Sentetik kafe").performClick()
        compose.runOnIdle {
            assertEquals(listOf("sentetik"), calls)
            assertSame(place, selected)
            assertEquals(OsmRef(OsmType.WAY, 700), selected?.osmRef)
        }
    }
}
