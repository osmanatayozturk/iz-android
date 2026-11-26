package org.iz.navigation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Transport
import org.iz.navigation.lean.LeanCurveResult
import org.iz.navigation.lean.LeanJourneyReport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeanHandlebarTimingReportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun flagsEstimatedTimingInTheHandlebarSummaryAndOnlyOnAffectedCurves() {
        val journey = Journey(transport = Transport.MOTORCYCLE, startedAt = 1_000L, endedAt = 20_000L)
        val estimated = LeanCurveResult(1_000_000_000L, 3_000_000_000L, 24.0, null, null, null,
            timingEstimated = true)
        val measured = estimated.copy(startElapsedNanos = 3_000_000_000L, endElapsedNanos = 5_000_000_000L,
            leftDegrees = 18.0, timingEstimated = false)
        val handlebar = LeanJourneyReport(
            pending = false, leftDegrees = 24.0, rightDegrees = null,
            validMillis = 4_000L, observedMillis = 19_000L,
            partialReasons = setOf("HANDLEBAR_TIMING_ESTIMATED"), curves = listOf(estimated, measured),
            algorithmVersions = emptySet(),
        )
        val body = handlebar.copy(leftDegrees = 15.0, partialReasons = emptySet(), curves = emptyList())
        var report by mutableStateOf(body.copy(handlebar = handlebar))

        compose.setContent {
            IzTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LeanJourneyReportContent(journey, report)
                }
            }
        }

        val summaryWarning = compose.onNodeWithText(
            "Düşük güven · GPS zaman belirsizliği bildirilmedi. İşaretli virajlarda yatış, GPS hareketi ve telefon sensörlerinin uyumundan tahmin edildi.",
        )
        val curveWarning = compose.onAllNodesWithText(
            "Düşük güven · GPS zaman belirsizliği bildirilmedi. Yatış, hareket ve sensör uyumundan tahmin edildi.",
        )
        summaryWarning.performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("En büyük geçerli sol tahmin: 24°").assertExists()
        compose.onNodeWithText("En büyük geçerli sol tahmin: 15°").assertExists()
        assertEquals(0, curveWarning.fetchSemanticsNodes().size)

        val coverage = compose.onNodeWithTag("handlebar-lean-report-coverage")
        coverage.performScrollTo().performClick()
        compose.onNodeWithText(
            "GPS zaman belirsizliği bildirilmediğinde yatış, GPS hareketi ve telefon sensörlerinin uyumundan tahmin edildi.",
        ).performScrollTo().assertIsDisplayed()
        coverage.performScrollTo().performClick()

        compose.onNodeWithText("Gidon virajlarını göster (2)").performScrollTo().performClick()
        compose.onNodeWithText("Saat eşlenemedi · Sol 24° · Sağ Kullanılamıyor").assertExists()
        compose.onNodeWithText("Saat eşlenemedi · Sol 18° · Sağ Kullanılamıyor").assertExists()
        assertEquals(1, curveWarning.fetchSemanticsNodes().size)

        compose.runOnIdle {
            report = body.copy(handlebar = handlebar.copy(partialReasons = emptySet(), curves = listOf(measured)))
        }
        summaryWarning.assertDoesNotExist()
        assertEquals(0, curveWarning.fetchSemanticsNodes().size)
        compose.onNodeWithText("Gidon bağlantısından tahmini yatış").assertExists()

        compose.runOnIdle { report = body }
        summaryWarning.assertDoesNotExist()
        compose.onNodeWithText("En büyük geçerli sol tahmin: 15°").assertExists()
    }
}
