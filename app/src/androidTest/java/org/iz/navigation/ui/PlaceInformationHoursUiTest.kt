package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.placeinfo.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PlaceInformationHoursUiTest {
    @get:Rule val compose = createComposeRule()
    private val key = PlaceInfoKey("https://example.invalid/api/0.6", OsmRef(OsmType.WAY, 42))
    private var actions = 0
    private val instant = mutableStateOf(Instant.parse("2026-11-12T07:00:00Z"))
    private val override = mutableStateOf<String?>(null)
    private fun show(raw: String, largeLandscape: Boolean = false) {
        val snapshot = PublicPlaceSnapshot(key, 1, mapOf("name" to "Uzun sentetik kamusal yer adı ".repeat(12), "opening_hours" to raw), 1000)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, if (largeLandscape) 2f else 1f)) { IzTheme {
                Box(if (largeLandscape) Modifier.requiredSize(640.dp, 320.dp) else Modifier.fillMaxSize()) {
                    PlaceInformationContent(PlaceInfoState(key, snapshot), key, "Fallback", instant.value, ZoneId.of("UTC"), override.value,
                        { override.value = it }, { actions++ }, onDirections = { actions++ }, onSurvey = { actions++ },
                        onCorrect = { actions++ }, onNote = { actions++ }, onExternal = { _, _ -> actions++ },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                }
            } }
        }
    }
    @Test fun visibleInstantAndOverrideRecomputeHoursWithoutExternalActions() {
        show("Th 09:00-11:00")
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("şu an kapalı", substring = true)
        compose.runOnIdle { override.value = "Europe/Istanbul" }
        compose.onNodeWithTag("place-info-hours").assertTextContains("şu an açık", substring = true)
        compose.runOnIdle { instant.value = Instant.parse("2026-11-12T08:00:00Z") }
        compose.onNodeWithTag("place-info-hours").assertTextContains("şu an kapalı", substring = true)
        compose.runOnIdle { assertEquals(0, actions) }
    }
    @Test fun unsupportedWholeExpressionShowsRawDetailWithoutInventedWeek() {
        val raw = "Mo-Fr 09:00-17:00; PH off"
        show(raw)
        compose.onNodeWithTag("place-info-hours").performScrollTo().assertTextContains("Açıklık durumu belirlenemedi", substring = true).performClick()
        compose.onNodeWithText(raw).assertIsDisplayed()
        compose.onNodeWithText("Pazartesi").assertDoesNotExist()
        compose.onNodeWithTag("place-info-hours-close").performClick()
        compose.runOnIdle { assertEquals(0, actions) }
    }
    @Test fun landscapeFontTwoKeepsAllSectionsAndControlsReachableByVerticalScroll() {
        show("24/7", largeLandscape = true)
        listOf("place-info-contact", "place-info-hours", "place-info-wheelchair", "place-info-payments", "place-info-contribute")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        listOf("place-info-route", "place-info-zone-change", "place-info-survey", "place-info-note")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
