package org.iz.navigation.ui

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.ui.history.UiHistory
import org.iz.navigation.ui.history.UiPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmGpsNavigationTest {
    @Test fun shortcutAndCommunityMessageTargetsStayDistinct() {
        val history = UiHistory()
        val journeyId = "1".repeat(160)
        val journey = history.push(UiPage.JOURNEY, journeyId)
        val entry = history.push(UiPage.COMMUNITY, OSM_GPS_JOURNEY_ENTRY)
        assertEquals(journeyId, osmGpsJourneyFromEntry(entry, journey))
        assertNull(entry.entityId?.toLongOrNull())
        assertNull(osmGpsJourneyFromEntry(entry.copy(entityId = "123"), journey))
        assertNull(osmGpsJourneyFromEntry(entry, null))
    }

    @Test fun onlyConfirmedCompletedJourneysAreCandidates() {
        val completed = Journey(id = "journey", startedAt = 1L, endedAt = 2L)
        assertTrue(osmGpsEligibleJourney(completed))
        assertFalse(osmGpsEligibleJourney(completed.copy(endedAt = null)))
        assertFalse(osmGpsEligibleJourney(completed.copy(status = JourneyStatus.TEMPORARY)))
    }
}
