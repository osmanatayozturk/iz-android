package com.atay.iz.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.Journey
import com.atay.iz.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class GpxMapUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullscreenPreviewPreservesTrimmingWhenExportingGpx() {
        val startedAt = 1_700_000_000_000L
        val journey = Journey(id = "gpx-preview", startedAt = startedAt, endedAt = startedAt + 40_000L)
        val points = listOf(
            TrackPoint(journeyId = journey.id, latitude = 39.9, longitude = 32.8, recordedAt = startedAt, accuracy = 5f),
            TrackPoint(journeyId = journey.id, latitude = 39.901, longitude = 32.801, recordedAt = startedAt + 10_000L, accuracy = 5f),
            TrackPoint(journeyId = journey.id, latitude = 39.902, longitude = 32.802, recordedAt = startedAt + 20_000L, accuracy = 5f),
            TrackPoint(journeyId = journey.id, latitude = 39.903, longitude = 32.803, recordedAt = startedAt + 30_000L, accuracy = 5f),
        )
        var exported: String? = null
        compose.setContent {
            IzTheme { GpxExportSheet(journey, points, onDismiss = {}, onExport = { exported = it }) }
        }

        val sliders = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        sliders[0].performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        sliders[1].performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithContentDescription("Haritayı büyüt").performScrollTo().performClick()
        compose.onNodeWithTag("fullscreen_map").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tam ekran haritayı kapat").performClick()
        compose.onNodeWithTag("fullscreen_map").assertDoesNotExist()
        compose.onNodeWithText("GPX dosyasını kaydet").performScrollTo().performClick()

        compose.runOnIdle {
            assertNotNull(exported)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(exported!!.byteInputStream(Charsets.UTF_8))
            val trackPoints = document.getElementsByTagName("trkpt")
            val coordinates = (0 until trackPoints.length).map { index ->
                val point = trackPoints.item(index) as Element
                point.getAttribute("lat") to point.getAttribute("lon")
            }
            assertEquals("Only the two untrimmed points should be exported",
                listOf("39.901" to "32.801", "39.902" to "32.802"), coordinates)
        }
    }
}
