package org.iz.navigation.tracking

import org.iz.navigation.data.Journey

/** Shared by attachment and queued callbacks so they obey the same live-session boundaries. */
internal fun canMeasurePhoneSteps(journey: Journey, runningJourneyId: String?, permissionGranted: Boolean): Boolean =
    journey.transport.supportsSteps && journey.endedAt == null && runningJourneyId == journey.id && permissionGranted
