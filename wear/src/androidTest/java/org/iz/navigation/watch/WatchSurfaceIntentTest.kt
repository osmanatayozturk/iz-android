package org.iz.navigation.watch

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises Android's real intent reuse path, rather than calling a route callback directly. */
class WatchSurfaceIntentTest {
    @get:Rule val compose = createAndroidComposeRule<WatchActivity>()

    @Test fun singleTopSurfaceTapUpdatesTheAlreadyOpenActivity() {
        compose.onNodeWithTag("watch-list").assertExists()
        val original = compose.activity
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity.intent).setClass(activity, WatchActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("surface", "DAILY"))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("surface-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("surface-detail").assertExists()
        compose.onNodeWithTag("watch-list").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { activity ->
            assertSame("The existing activity must handle the tap", original, activity)
            assertEquals("DAILY", activity.intent.getStringExtra("surface"))
        }
    }
}
