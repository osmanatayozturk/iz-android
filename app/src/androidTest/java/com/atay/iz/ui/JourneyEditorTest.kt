package com.atay.iz.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.Journey
import com.atay.iz.data.Transport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun passengerReclassificationPreservesWholeJourneyIdentityAndTimes() {
        val journey = Journey(transport = Transport.CAR, startedAt = 100, endedAt = 200, title = "Eve dönüş")
        var saved: Journey? = null
        compose.setContent { MaterialTheme { JourneyEditor(journey, {}, { saved = it }) } }
        compose.onNodeWithText("Yolcu").performClick()
        compose.onNodeWithText("Kaydet").performClick()
        compose.runOnIdle {
            assertEquals(journey.id, saved?.id)
            assertEquals(journey.startedAt, saved?.startedAt)
            assertEquals(journey.endedAt, saved?.endedAt)
            assertEquals(Transport.PASSENGER, saved?.transport)
        }
    }
}
