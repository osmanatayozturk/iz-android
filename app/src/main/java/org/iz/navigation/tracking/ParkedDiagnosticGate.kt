package org.iz.navigation.tracking

import org.iz.navigation.navigation.NavigationState

/** Uses existing state only; never requests a fix to decide whether a diagnostic may start. */
internal fun parkedDiagnosticBlock(state: NavigationState, serviceRunning: Boolean, nowMillis: Long): ComparisonIssue? =
    parkedDiagnosticBlock(state.recording || state.recordingPending || state.guidance || state.loading || serviceRunning,
        state.gpsStale, state.fix?.recordedAt, state.fix?.speedMps, state.fix?.accuracyMeters, nowMillis)
