package org.iz.navigation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Source-only allocation: runtime NOT_RUN until exact source/APKs, Security and device GO.
 * Intended mutations: drop ACTION_SEND/onNewIntent, omit consumption, replay saved launch intent,
 * publish an older delivery, or activate real navigation/recording from share receipt.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SharedMapIntentLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldGoogleShareRestoresItsTargetButConsumedIntentDoesNotReplayAfterDismissal() {
        SharedMapIntentTestFixture(compose).use { host ->
            host.launchCold("Sentetik paylaşım\nhttps://www.google.com/maps/search/?api=1&query=0.001%2C0.002%28Cold-Synthetic-A%29")
            expectTarget("Cold-Synthetic-A")
            host.recreate()
            expectTarget("Cold-Synthetic-A")
            dismissSharedEntry()
            host.assertNoSessionEffect()
            host.recreate()
            compose.onNodeWithTag("shared-map-entry").assertDoesNotExist()
            compose.onNodeWithTag("quick-directions").assertDoesNotExist()
            host.assertNoSessionEffect()
        }
    }

    @Test fun warmAppleShareAndRepeatedDeliveryRemainReceiptOnlyAndDoNotReplayOnRecreation() {
        SharedMapIntentTestFixture(compose).use { host ->
            host.launchIdle()
            compose.onNodeWithTag("shared-map-entry").assertDoesNotExist()
            val payload = "https://maps.apple.com/place?coordinate=0.003%2C0.004&name=Warm-Synthetic-B"
            host.deliverWarm(payload)
            expectTarget("Warm-Synthetic-B")
            // Two real warm callbacks with fresh Intent objects; no parser/callback fake.
            host.deliverWarm(payload, payload)
            expectTarget("Warm-Synthetic-B")
            host.assertNoSessionEffect()
            dismissSharedEntry()
            host.recreate()
            compose.onNodeWithTag("shared-map-entry").assertDoesNotExist()
            compose.onNodeWithTag("quick-directions").assertDoesNotExist()
            host.assertNoSessionEffect()
        }
    }

    @Test fun latestWarmDeliveryWinsBeforeCompositionAndSurvivesRecreationWithoutSessionEffects() {
        SharedMapIntentTestFixture(compose).use { host ->
            host.launchCold("https://maps.apple.com/place?coordinate=0.005%2C0.006&name=Initial-Synthetic-C")
            expectTarget("Initial-Synthetic-C")
            // Both callbacks precede the next composition. Only the newest received target may publish.
            host.deliverWarm(
                "https://maps.apple.com/place?coordinate=0.007%2C0.008&name=Older-Synthetic-D",
                "https://maps.apple.com/place?coordinate=0.009%2C0.010&name=Latest-Synthetic-E")
            expectTarget("Latest-Synthetic-E")
            compose.onNodeWithText("Older-Synthetic-D", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Initial-Synthetic-C", substring = true).assertDoesNotExist()
            host.recreate()
            expectTarget("Latest-Synthetic-E")
            compose.onNodeWithText("Older-Synthetic-D", substring = true).assertDoesNotExist()
            dismissSharedEntry()
            host.recreate()
            compose.onNodeWithTag("shared-map-entry").assertDoesNotExist()
            compose.onNodeWithTag("quick-directions").assertDoesNotExist()
            host.assertNoSessionEffect()
        }
    }

    private fun expectTarget(label: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("quick-route-options").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasText(label, substring = true))
        compose.onNodeWithText(label, substring = true).assertIsDisplayed()
        compose.onAllNodesWithTag("quick-route-options").assertCountEquals(1)
        compose.onNodeWithTag("shared-map-entry").assertDoesNotExist()
        compose.onNodeWithTag("shared-preview").assertIsEnabled()
        compose.onNodeWithTag("shared-start").assertDoesNotExist()
    }

    private fun dismissSharedEntry() {
        compose.onNodeWithTag("quick-back").performClick()
        compose.onNodeWithTag("shared-map-entry").assertDoesNotExist()
        compose.onNodeWithTag("quick-directions").assertDoesNotExist()
    }
}

