package org.iz.navigation.moto

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.GarageVehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GarageMainMenuAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mainMenuAndJourneysOpenFreshGarageAndReturnToTheirCaller() {
        val context = requireGarageMenuQaTarget()
        val garage = (context.applicationContext as IzApplication).garage
        val name = "QA Garaj ${UUID.randomUUID().toString().take(8)}"
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                compose.waitUntil(8_000) { compose.onAllNodesWithTag("home-menu").fetchSemanticsNodes().isNotEmpty() }
                openMenu()
                val journeys = compose.onNodeWithTag("menu-journeys").assertTextContains("Yolculuklar")
                    .assertIsDisplayed().assertHasClickAction()
                val garageEntry = compose.onNodeWithTag("menu-garage").assertTextContains("Garaj")
                    .assertIsDisplayed().assertHasClickAction().assertIsEnabled()
                val journeysNode = journeys.fetchSemanticsNode()
                val garageNode = garageEntry.fetchSemanticsNode()
                assertEquals(journeysNode.positionInRoot.x, garageNode.positionInRoot.x, 1f)
                val journeysBottom = journeysNode.positionInRoot.y + journeysNode.size.height
                val gap = garageNode.positionInRoot.y - journeysBottom
                assertTrue("Garaj must immediately follow Yolculuklar", gap in -1f..(journeysNode.size.height * .25f))
                garageEntry.performClick()

                compose.onNodeWithTag("garage-screen").assertIsDisplayed()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("garage-add-vehicle").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("garage-add-vehicle").assertTextContains("Araç ekle").performClick()
                dialogText("Araç ekle").assertIsDisplayed()
                assertManualThresholdFieldsAbsent()
                field("Araç adı").performTextReplacement(name)
                field("Depo kapasitesi (L)").performTextReplacement("12")
                dialogText("Kaydet").assertIsEnabled().performClick()
                val bike = runBlocking {
                    withTimeout(5_000) { garage.vehicles.first { rows -> rows.any { it.name == name } } }
                        .single { it.name == name }
                }
                assertEquals(12.0, bike.capacityLitres, .001)
                compose.onNodeWithText("Tüm araçlar").assertIsDisplayed()
                val addFill = hasTestTag("garage-add-fill")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(addFill)
                compose.onNode(addFill).performClick()
                if (compose.onAllNodesWithTag("garage-vehicle-choice").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("garage-choose-${bike.id}").performClick()
                }
                dialogText("Dolum ekle").assertIsDisplayed()
                dialogText(name).assertIsDisplayed()
                field("Alınan litre").assertIsDisplayed()
                field("Litre fiyatı (TL)").assertIsDisplayed()
                dialogText("Vazgeç").performClick()
                dialogText("Dolum ekle").assertDoesNotExist()

                val vehicleTag = "garage-vehicle-${bike.id}"
                openMenu()
                compose.onNodeWithTag("menu-garage").assertIsDisplayed().performClick()
                assertGarageList(vehicleTag)
                garageBack()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Tüm araçlar"))
                compose.onNodeWithText("Tüm araçlar").assertIsDisplayed()
                garageBack()
                assertGarageList(vehicleTag)
                garageBack()
                assertMap()

                openMenu()
                compose.onNodeWithTag("menu-journeys").assertIsDisplayed().performClick()
                assertJourneys()
                val shortcut = hasText("Garaj") and hasClickAction()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(shortcut)
                compose.onNode(shortcut).performClick()
                assertGarageList(vehicleTag)
                compose.onNodeWithTag(vehicleTag).performClick()
                compose.onNodeWithText("Tüm araçlar").assertIsDisplayed()
                pressBack()
                assertGarageList(vehicleTag)
                pressBack()
                assertJourneys()

                openMenu()
                compose.onNodeWithTag("menu-garage").assertIsDisplayed().performClick()
                assertGarageList(vehicleTag)
                garageBack()
                assertJourneys()
                compose.onNodeWithTag("return-home-map").performClick()
                assertMap()
            }
        } finally {
            runBlocking {
                garage.vehicles.first().filter { it.name == name }.forEach { garage.deleteVehicle(it.id) }
            }
        }
    }

    @Test fun fifthAndSixthFullFillUpdateBothLearnedValuesThroughTheRealForm() {
        val context = requireGarageMenuQaTarget()
        val garage = (context.applicationContext as IzApplication).garage
        val bike = GarageVehicle(name = "QA Öğrenilen eşik", transport = Transport.MOTORCYCLE,
            capacityLitres = 12.0, odometerAt = 0L)
        val zone = ZoneId.systemDefault()
        val spacing = hasText("Dolumlar arası ortalama", substring = true)
        val range = hasText("Dolum öncesi ortalama kalan menzil", substring = true)
        try {
            runBlocking {
                garage.saveVehicle(bike)
                for (day in 1..4) {
                    val date = LocalDateTime.of(2025, 1, day, 12, 0).atZone(zone).toInstant().toEpochMilli()
                    garage.saveFill(FuelFill(vehicleId = bike.id, at = date,
                        odometerKm = 1000.0 + (day - 1) * 200.0, litres = 8.0, pricePerLitre = 50.0))
                }
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                compose.waitUntil(8_000) { compose.onAllNodesWithTag("home-menu").fetchSemanticsNodes().isNotEmpty() }
                openMenu()
                compose.onNodeWithTag("menu-garage").assertIsDisplayed().performClick()
                val tag = "garage-vehicle-${bike.id}"
                assertGarageList(tag)
                compose.onNodeWithTag(tag).performClick()
                waitForProgress("4/5")
                compose.onNodeWithTag("fuel-learning-progress").assertTextContains("4/5", substring = true)
                compose.onNode(spacing).assertDoesNotExist()
                compose.onNode(range).assertDoesNotExist()

                val edit = hasText("Aracı düzenle") and hasClickAction()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(edit)
                compose.onNode(edit).assertIsEnabled().performClick()
                dialogText("Aracı düzenle").assertIsDisplayed()
                assertManualThresholdFieldsAbsent()
                field("Toplam kilometre").assertIsDisplayed()
                dialogText("Vazgeç").performClick()

                // The sixth reserve is 2 L at the same 4 L/100 km consumption.
                // It carries more order weight than the five earlier 4 L observations.
                saveFullFill(bike.id, "1800", "8", "2025-01-05 12:00")
                assertLearnedValues("200,00", "100,00")
                saveFullFill(bike.id, "2050", "10", "2025-01-06 12:00")
                assertLearnedValues("211,82", "89,73")
                assertEquals(6, runBlocking { garage.fills(bike.id).first().size })
            }
        } finally {
            runBlocking { garage.deleteVehicle(bike.id) }
        }
    }

    private fun assertLearnedValues(average: String, remaining: String) {
        val learned = "Dolumlar arası ortalama $average km"
        val range = "Dolum öncesi ortalama kalan menzil $remaining km"
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("fuel-learning-progress"))
        waitText(learned)
        compose.onNodeWithTag("fuel-learning-progress").assertTextContains("5/5", substring = true)
        compose.onNodeWithText(learned).assertExists()
        compose.onNodeWithText(range).assertExists()
    }

    private fun saveFullFill(vehicleId: String, km: String, litres: String, at: String) {
        val button = hasTestTag("garage-add-fill")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(button)
        compose.onNode(button).performClick()
        if (compose.onAllNodesWithTag("garage-vehicle-choice").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("garage-choose-$vehicleId").performClick()
        }
        field("Toplam kilometre").performTextReplacement(km)
        field("Alınan litre").performTextReplacement(litres)
        field("Litre fiyatı (TL)").performTextReplacement("50")
        field("Dolum zamanı").performTextReplacement(at)
        dialogText("Kaydet").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Dolum ekle") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForProgress(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag("fuel-learning-progress") and hasText(text, substring = true))
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun assertManualThresholdFieldsAbsent() {
        compose.onNode(hasText("eşiği", substring = true) and hasSetTextAction() and hasAnyAncestor(isDialog()))
            .assertDoesNotExist()
    }

    private fun assertGarageList(vehicleTag: String) {
        compose.onNodeWithTag("garage-screen").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(vehicleTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(vehicleTag).assertIsDisplayed()
        compose.onNodeWithText("Tüm araçlar").assertDoesNotExist()
        compose.onNodeWithTag("garage-add-fill").assertDoesNotExist()
        dialogText("Dolum ekle").assertDoesNotExist()
    }

    private fun assertJourneys() {
        compose.onNodeWithTag("garage-screen").assertDoesNotExist()
        compose.onNodeWithText("Sürüşlerim").assertExists()
    }

    private fun assertMap() {
        compose.onNodeWithTag("garage-screen").assertDoesNotExist()
        compose.onNodeWithTag("return-home-map").assertDoesNotExist()
        compose.onNodeWithTag("home-menu").assertIsDisplayed()
    }

    private fun openMenu() = compose.onNodeWithTag("home-menu").performClick()

    private fun garageBack() = compose.onNode(
        hasContentDescription("Geri") and hasAnyAncestor(hasTestTag("garage-screen")),
    ).performClick()

    private fun field(name: String) = compose.onNode(
        hasText(name) and hasSetTextAction() and hasAnyAncestor(isDialog()),
    ).performScrollTo()

    private fun dialogText(name: String) = compose.onNode(hasText(name) and hasAnyAncestor(isDialog()))
}
