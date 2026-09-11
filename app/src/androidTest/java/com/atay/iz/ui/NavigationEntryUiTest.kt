package com.atay.iz.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.IzApplication
import com.atay.iz.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationEntryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun openingAndClosingNavigationDoesNotChangeJourneyOrGuidance() {
        val owner = (compose.activity.application as IzApplication).navigation
        val before = owner.state.value
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("navigation-screen").assertIsDisplayed()
        compose.onNodeWithTag("close-directions-planner").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-screen").assertDoesNotExist()
        val after = owner.state.value
        assertEquals(before.journey?.id, after.journey?.id)
        assertEquals(before.route?.id, after.route?.id)
        assertEquals(before.guidance, after.guidance)
    }

    @Test fun incomingDirectionsReplacesOpenGroupPageWithoutStartingTrip() {
        val owner = (compose.activity.application as IzApplication).navigation
        val before = owner.state.value
        val activity = compose.activity
        val launchIntent = Intent(activity.intent)
        compose.onNodeWithTag("home-group").performClick()
        compose.onNodeWithTag("navigation-home-map").assertDoesNotExist()
        try {
            compose.runOnUiThread {
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("geo:0,0?q=41.0,29.0(Grup%20sonrasi%20hedef)"))
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("navigation-screen").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Hedef: Grup sonrasi hedef").performScrollTo().assertIsDisplayed()
            assertEquals(before.journey?.id, owner.state.value.journey?.id)
            assertEquals(before.guidance, owner.state.value.guidance)
        } finally { compose.runOnUiThread { activity.intent = launchIntent } }
    }
    @Test fun incomingGeoLinkShowsDestinationWithoutStartingRecording() {
        val owner = (compose.activity.application as IzApplication).navigation
        val before = owner.state.value
        val activity = compose.activity
        val launchIntent = Intent(activity.intent)
        try {
            compose.runOnUiThread {
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("geo:0,0?q=41.0,29.0(Test%20hedefi)"))
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("navigation-screen").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Hedef: Test hedefi").performScrollTo().assertIsDisplayed()
            assertEquals(before.journey?.id, owner.state.value.journey?.id)
            assertEquals(before.guidance, owner.state.value.guidance)
        } finally {
            // ActivityScenario matches lifecycle events against its launch intent.
            // MainActivity legitimately replaces that intent for a new deep link.
            compose.runOnUiThread { activity.intent = launchIntent }
        }
    }
}


