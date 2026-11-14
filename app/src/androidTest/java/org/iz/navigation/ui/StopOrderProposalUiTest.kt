package org.iz.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StopOrderProposalUiTest {
    @get:Rule val compose = createComposeRule()
    private val stops = listOf("Başlangıç", "Kahve molası", "Sahil", "Varış").mapIndexed { i, label ->
        RouteStop(label, WeatherCoordinate(41.0, 29.0 + i * .001))
    }
    private val route = PlannedRoute("current", stops, stops.mapIndexed { i, stop -> RouteVertex(stop.coordinate, i * 60.0) },
        4000.0, 1200.0, 1_700_000_000_000L, transport = Transport.MOTORCYCLE)
    private val proposal = StopOrderProposal(stops, listOf(stops[0], stops[2], stops[1], stops[3]), route,
        route.copy(id = "proposed", stops = listOf(stops[0], stops[2], stops[1], stops[3]), durationSeconds = 900.0, distanceMeters = 3500.0),
        1_700_000_120_000L, false, 1_700_000_000_000L, "fixture", heuristic = true)

    @Test fun motorcycleHeuristicComparisonNeedsExplicitApplyWithoutAutomobileMatrixClaim() {
        var applied = 0
        var dismissed = 0
        compose.setContent { IzTheme { Box(Modifier.requiredWidth(340.dp)) {
            StopOrderProposalCard(proposal, true, { applied++ }, { dismissed++ })
        } } }
        compose.onNodeWithTag("matrix-comparison-distances").assertIsDisplayed()
        compose.onNodeWithTag("heuristic-order-explanation").assertIsDisplayed()
        compose.onNodeWithText("Sıra önerisi otomobil Matrix tahmininden üretildi. Yukarıdaki süreler motosiklet rotasıyla yeniden hesaplandı.").assertDoesNotExist()
        assertEquals(0, applied)
        compose.onNodeWithText("Mevcut sırayı koru").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, applied)
        compose.onNodeWithTag("matrix-apply-order").performClick()
        assertEquals(1, applied)
    }
}
