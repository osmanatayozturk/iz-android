package org.iz.navigation.car

import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState

internal fun canSelectCarMode(state: NavigationState): Boolean = (!state.recording && state.sessionId == null) ||
    (state.journey?.status == JourneyStatus.TEMPORARY && state.journey.transport == Transport.UNKNOWN)

internal fun effectiveCarMode(state: NavigationState, selected: Transport): Transport =
    if (canSelectCarMode(state)) selected else state.sessionTransport ?: state.journey?.transport ?: selected
