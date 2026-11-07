package org.iz.navigation.ui

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.ui.history.UiEntry
import org.iz.navigation.ui.history.UiPage

internal const val OSM_GPS_JOURNEY_ENTRY = "gps"

internal fun osmGpsJourneyFromEntry(entry: UiEntry, parent: UiEntry?): String? =
    parent?.takeIf { entry.page == UiPage.COMMUNITY && entry.entityId == OSM_GPS_JOURNEY_ENTRY &&
        entry.parentKey == it.key && it.page == UiPage.JOURNEY }?.entityId

internal fun osmGpsEligibleJourney(journey: Journey): Boolean =
    journey.status == JourneyStatus.CONFIRMED && journey.endedAt != null
