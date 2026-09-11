package com.atay.iz.car

import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.Transport
import com.atay.iz.navigation.NavigationState

internal fun canSelectCarMode(state: NavigationState): Boolean = (!state.recording && state.sessionId == null) ||
    (state.journey?.status == JourneyStatus.TEMPORARY && state.journey.transport == Transport.UNKNOWN)

internal fun effectiveCarMode(state: NavigationState, selected: Transport): Transport =
    if (canSelectCarMode(state)) selected else state.sessionTransport ?: state.journey?.transport ?: selected
