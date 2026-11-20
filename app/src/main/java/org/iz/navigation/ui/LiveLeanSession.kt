package org.iz.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import org.iz.navigation.data.Transport
import org.iz.navigation.lean.*
import org.iz.navigation.navigation.NavigationState

internal typealias LiveLeanReading = org.iz.navigation.lean.LiveLeanReading

@Composable
internal fun liveLeanReadings(state: NavigationState): StateFlow<LiveLeanReading?> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val optedIn by LiveLeanDisplaySettings.get(context).enabled.collectAsStateWithLifecycle()
    val settings by LeanSettingsStore.get(context).state.collectAsStateWithLifecycle()
    val session = state.sessionId?.takeIf {
        optedIn && state.sessionTransport == Transport.MOTORCYCLE && (state.recording || state.guidance) &&
            !state.simulation && !state.paused && !state.gpsStale && state.plannedBreak == null && state.approximateOrigin == null
    }
    val reference = settings.calibration?.takeIf { LiveLeanGate.allows(settings.mount) }?.takeIf {
        when (settings.mount) {
            LeanMount.BODY -> validLeanReference(it)
            LeanMount.HANDLEBAR -> validHandlebarReference(it)
            LeanMount.UNKNOWN -> false
        }
    }
    LaunchedEffect(session, reference, lifecycle) {
        if (session != null && reference != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val detach = LiveLeanCapture.attach(context, session, reference)
            try { awaitCancellation() } finally { detach() }
        }
    }
    return LiveLeanCapture.readings
}
