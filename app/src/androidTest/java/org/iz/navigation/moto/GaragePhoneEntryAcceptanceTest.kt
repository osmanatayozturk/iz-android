package org.iz.navigation.moto

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.GarageVehicle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GaragePhoneEntryAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun garageFormRequiresPositiveCapacityAndFillChoiceUpdatesOnlyTheSelectedVehiclesMonth() {
        val context = requireMotorcycleQaTarget()
        val garage = (context.applicationContext as IzApplication).garage
        val other = GarageVehicle(name = "QA Diğer", transport = Transport.MOTORCYCLE, capacityLitres = 15.0)
        val bikeName = "QA Yeni 12L"
        fun date(day: Int) = LocalDateTime.of(2025, 1, day, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(runBlocking { garage.vehicles.first() }.isEmpty())
        try {
            runBlocking {
                garage.saveVehicle(other)
                garage.saveFill(FuelFill(vehicleId = other.id, at = date(7), odometerKm = 5_000.0,
                    litres = 1.0, pricePerLitre = 99.0))
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                compose.waitUntil(8_000) { compose.onAllNodesWithTag("home-menu").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-journeys").performScrollTo().performClick()
                pageRow("Garaj").performClick()
                compose.onNodeWithTag("garage-screen").assertIsDisplayed()
                compose.onNodeWithTag("garage-add-vehicle").performClick()
                field("Araç adı").performTextReplacement(bikeName)
                field("Depo kapasitesi (L)").performTextReplacement("0")
                dialogButton("Kaydet").assertIsNotEnabled()
                field("Depo kapasitesi (L)").performTextReplacement("12")
                dialogButton("Kaydet").assertIsEnabled().performClick()
                val bike = runBlocking {
                    withTimeout(5_000) { garage.vehicles.first { rows -> rows.any { it.name == bikeName } } }
                        .single { it.name == bikeName }
                }
                assertEquals(12.0, bike.capacityLitres, .001)
                assertEquals(Transport.MOTORCYCLE, bike.transport)
                runBlocking {
                    garage.saveFill(FuelFill(vehicleId = bike.id, at = date(7), odometerKm = 1_000.0,
                        litres = 3.0, pricePerLitre = 40.0))
                }
                pageRow("Tüm araçlar").performClick()
                compose.onNodeWithTag("garage-vehicle-${other.id}").performClick()
                val add = hasTestTag("garage-add-fill")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(add)
                compose.onNode(add).performClick()
                compose.onNodeWithTag("garage-vehicle-choice").assertIsDisplayed()
                compose.onNodeWithTag("garage-choose-${bike.id}").performClick()
                field("Toplam kilometre").performTextReplacement("1200")
                field("Alınan litre").performTextReplacement("8,5")
                field("Litre fiyatı (TL)").performTextReplacement("50,20")
                field("Dolum zamanı").performTextReplacement("2025-01-10 12:00")
                dialogButton("Kaydet").assertIsEnabled().performClick()
                val filled = runBlocking { withTimeout(5_000) { garage.fills(bike.id).first { it.size == 2 } }.last() }
                assertEquals(date(10), filled.at)
                assertEquals(426.70, filled.totalTl, .001)
                assertTrue("Full is the default for a new fill", filled.full)
                assertEquals(1, runBlocking { garage.fills(other.id).first() }.size)
                pageRow("Ocak 2025 · 99,00 TL").assertIsDisplayed()
                pageRow("Tüm araçlar").performClick()
                compose.onNodeWithTag("garage-vehicle-${bike.id}").performClick()
                pageRow("Ocak 2025 · 546,70 TL").assertIsDisplayed()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("fuel-learning-progress"))
                compose.onNodeWithTag("fuel-learning-progress").assertTextContains("2/5 tam dolum")
            }
        } finally {
            runBlocking {
                garage.vehicles.first().filter { it.id == other.id || it.name == bikeName }
                    .forEach { garage.deleteVehicle(it.id) }
            }
        }
    }

    private fun pageRow(name: String): SemanticsNodeInteraction {
        val row = hasText(name)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(row)
        return compose.onNode(row)
    }

    private fun field(name: String): SemanticsNodeInteraction {
        val field = compose.onNode(hasText(name) and hasSetTextAction() and hasAnyAncestor(isDialog()))
        return field.performScrollTo()
    }

    private fun dialogButton(name: String) = compose.onNode(hasText(name) and hasAnyAncestor(isDialog()))
}
