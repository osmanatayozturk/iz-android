package org.iz.navigation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.iz.navigation.data.*
import org.iz.navigation.health.HealthConnectionState
import org.junit.Rule
import org.junit.Test

class HealthUiTest {
    @get:Rule val compose = createComposeRule()
    private val journey = Journey(id = "health-ui", transport = Transport.RUN, startedAt = 1_000, endedAt = 121_000, stepCount = 70)
    private val enabled = HealthConnectionState(enabled = true, grantedMetrics = HealthMetric.entries.toSet())

    @Test fun emptyDataIsNotPresentedAsZero() {
        compose.setContent { IzTheme { HealthSummaryCard(journey, null, emptyList(), enabled, 121_000) } }
        compose.onNodeWithText("Sağlık").assertIsDisplayed()
        compose.onAllNodesWithText("Eşitleme bekleniyor.").assertCountEquals(3)
        compose.onNodeWithText("0 kcal").assertDoesNotExist()
        compose.onNodeWithText("0 adım").assertDoesNotExist()
    }

    @Test fun partialCaloriesAndWatchStepsAreDistinct() {
        val summary = JourneyHealthSummary(journeyId = journey.id, totalCaloriesKcal = 12.0,
            calorieCoverageMillis = 60_000, watchSteps = 80, stepCoverageMillis = 60_000)
        compose.setContent { IzTheme { HealthSummaryCard(journey, summary, emptyList(), enabled, 121_000) } }
        compose.onNodeWithText("12 kcal").assertExists()
        compose.onNodeWithText("80 adım").assertExists()
        compose.onNodeWithText("150 adım").assertDoesNotExist()
        compose.onAllNodes(hasText("Kısmi veri", substring = true)).assertCountEquals(2)
    }

    @Test fun deniedPermissionsHaveExplicitMissingState() {
        compose.setContent { IzTheme { HealthSummaryCard(journey, null, emptyList(), enabled.copy(grantedMetrics = emptySet()), 121_000) } }
        compose.onAllNodesWithText("Okuma izni verilmedi.").assertCountEquals(3)
    }

    @Test fun recordedHeartRateShowsOnlyRealPoints() {
        val samples = listOf(ImportedHealthSample("heart", HealthRules.SAMSUNG_HEALTH_PACKAGE,
            HealthRules.WATCH_DEVICE_TYPE, metric = HealthMetric.HEART_RATE_BPM, startAt = 20_000, endAt = 20_000, value = 110.0).forJourney(journey.id))
        val summary = HealthRules.summarize(journey, samples, now = 121_000)
        compose.setContent { IzTheme { HealthSummaryCard(journey, summary, samples, enabled, 121_000) } }
        compose.onNodeWithContentDescription("Nabız grafiği: 1 ölçüm noktası").assertExists()
        compose.onNodeWithText("Son ölçüm: 110 atım/dk").assertExists()
    }
}
