package org.iz.navigation.watch

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WatchSurfaceDetailTest {
    @get:Rule val compose = createComposeRule()
    @Test fun detailShowsNavigationWithoutJourneyAndOpensExistingControls() {
        val now = 1_000_000L
        val route = WearNavigationSummary("n", true, now, false, false, false, false, false, 10,
            "Sağa dön", 150.0, 2200.0, now + 600000, "Ev")
        val data = WatchSurfaceData("p", 5, WearSnapshot(now, navigation = route), true)
        val size = mutableStateOf(180)
        var controls = false
        compose.setContent { Box(Modifier.size(size.value.dp).clip(CircleShape).testTag("round-fixture")) {
            WatchSurfaceDetail(data, WatchSurfaceRoute.NAVIGATION, now) { controls = true }
        } }
        for (width in listOf(180, 227)) {
            compose.runOnIdle { size.value = width }
            compose.waitForIdle()
            compose.onNodeWithText("→ 150 m").assertExists()
            val bitmap = compose.onNodeWithTag("round-fixture").captureToImage().asAndroidBitmap()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(context.getExternalFilesDir(null), "surface-fixtures").apply { mkdirs() }
            File(folder, "detail_navigation_${width}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNode(hasScrollAction() and (hasTestTag("surface-detail") or hasAnyAncestor(hasTestTag("surface-detail"))), useUnmergedTree = true)
            .performScrollToNode(hasTestTag("surface-controls"))
        compose.onNodeWithTag("surface-controls").performClick()
        compose.runOnIdle { assertTrue(controls) }
    }
}
