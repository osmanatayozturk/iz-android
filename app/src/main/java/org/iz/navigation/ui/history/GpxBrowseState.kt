package org.iz.navigation.ui.history

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import org.iz.navigation.gpx.TrackFollowSelection

class GpxBrowseState(val segment: MutableIntState, val reversed: MutableState<Boolean>,
    val point: MutableIntState, val fraction: MutableDoubleState, val transport: MutableState<String>)
private class GpxBrowseReference { lateinit var state: GpxBrowseState }

/** Provider captures only these five primitive browsing/preparation values, never the track/name/permission dialog. */
@Composable
internal fun rememberUiGpxBrowse(holder: SaveableStateHolder, entry: String, trackId: String?, initial: TrackFollowSelection): GpxBrowseState {
    val reference = remember(entry, trackId) { GpxBrowseReference() }
    holder.SaveableStateProvider("gpx/$entry") { key(trackId) {
        val segment = rememberSaveable { mutableIntStateOf(initial.segmentIndex) }
        val reversed = rememberSaveable { mutableStateOf(initial.reversed) }
        val point = rememberSaveable { mutableIntStateOf(initial.startPointIndex) }
        val fraction = rememberSaveable { mutableDoubleStateOf(initial.startFraction) }
        val transport = rememberSaveable { mutableStateOf("WALK") }
        reference.state = remember(segment, reversed, point, fraction, transport) { GpxBrowseState(segment, reversed, point, fraction, transport) }
    } }
    return reference.state
}
