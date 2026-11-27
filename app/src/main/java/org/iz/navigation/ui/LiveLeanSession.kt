package org.iz.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.iz.navigation.lean.LeanCalibration
import org.iz.navigation.lean.LiveLeanCapture

internal typealias LiveLeanReading = org.iz.navigation.lean.LiveLeanReading

@Composable
internal fun LiveRideLeanDial(
    session: String,
    reference: LeanCalibration,
    journeyId: String?,
    fixAvailable: Boolean,
    measurementAllowed: Boolean,
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shouldAttach = measurementAllowed && (!reference.bodyConfirmed || fixAvailable)
    val latestFixAvailable by rememberUpdatedState(fixAvailable)
    key(session, reference, journeyId, shouldAttach, lifecycle) {
        val readings = remember { MutableStateFlow<LiveLeanReading?>(null) }
        if (shouldAttach) LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val detach = LiveLeanCapture.attach(context, session, reference,
                    journeyId = journeyId, hasInitialFix = latestFixAvailable) { readings.value = it }
                try { awaitCancellation() } finally { detach() }
            }
        }
        val reading by readings.collectAsState()
        LiveLeanDial(session, reading.takeIf { !reference.bodyConfirmed || fixAvailable })
    }
}
